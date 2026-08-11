package com.opentypeless.android.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public final class PersonalizationStoreInstrumentedTest {
    private static final String DATABASE = "opentypeless_android.db";

    private Context context;
    private PersonalizationStore store;

    @Before
    public void createFreshStore() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DATABASE);
        store = new PersonalizationStore(context);
    }

    @After
    public void closeStore() {
        if (store != null) store.close();
        context.deleteDatabase(DATABASE);
    }

    @Test
    public void unicodeIdentityAndAppScopeAreEnforcedByRealSqlite() {
        store.addTerm("ＯｐｅｎＴｙｐｅｌｅｓｓ", "open type less", "open type less", "com.chat.app");
        assertThrows(IllegalArgumentException.class, () -> store.addTerm(
                "OpenTypeless", "", "", "com.chat.app"));

        assertEquals(1, store.snapshot("com.chat.app").terms().size());
        assertEquals(0, store.snapshot("com.mail.app").terms().size());
    }

    @Test
    public void historyIsEncryptedAtRestAndDecryptsForTheUser() {
        String raw = "private raw transcript 123";
        String result = "Private raw transcript 123.";
        String applied = "open type less → OpenTypeless";
        store.addHistory(new HistoryEntry(
                0, System.currentTimeMillis(), "com.example", "LONG_TEXT", "SMART",
                "OPENAI_COMPATIBLE", raw, result, 1_250, applied));

        try (Cursor cursor = store.getReadableDatabase().query(
                "dictation_history",
                new String[]{"raw_text", "final_text", "applied_rules"},
                null, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertFalse(cursor.getString(0).contains(raw));
            assertFalse(cursor.getString(1).contains(result));
            assertFalse(cursor.getString(2).contains(applied));
        }
        HistoryEntry decoded = store.listHistory(1).get(0);
        assertEquals(raw, decoded.rawText());
        assertEquals(result, decoded.finalText());
        assertEquals(applied, decoded.appliedRules());
    }

    @Test
    public void legacyPlaintextHistoryMigratesOnNextOpen() throws Exception {
        try (Cursor cursor = store.getWritableDatabase().rawQuery("PRAGMA secure_delete", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(1, cursor.getInt(0));
        }
        ContentValues values = new ContentValues();
        values.put("created_at", System.currentTimeMillis());
        values.put("app_package", "legacy.app");
        values.put("field_kind", "GENERAL");
        values.put("mode", "VERBATIM");
        values.put("backend", "OPENAI_COMPATIBLE");
        values.put("raw_text", "legacy raw");
        values.put("final_text", "legacy final");
        values.put("duration_ms", 500);
        store.getWritableDatabase().insertOrThrow("dictation_history", null, values);
        store.close();
        assertTrue(contains(
                Files.readAllBytes(context.getDatabasePath(DATABASE).toPath()),
                "legacy raw".getBytes(StandardCharsets.UTF_8)));

        // Simulate an upgrade from the pre-encryption build. Production old installs do not have
        // this marker; this test created its schema through the new helper before injecting rows.
        assertTrue(context.getSharedPreferences(
                        PersonalizationStore.PRIVACY_MIGRATIONS,
                        Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PersonalizationStore.HISTORY_STORAGE_SANITIZED, false)
                .commit());

        store = new PersonalizationStore(context);
        assertEquals("legacy raw", store.listHistory(1).get(0).rawText());
        try (Cursor cursor = store.getReadableDatabase().query(
                "dictation_history", new String[]{"raw_text"},
                null, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertTrue(cursor.getString(0).startsWith("opentypeless-encrypted-history:v1:"));
        }
        assertStorageDoesNotContain("legacy raw", "legacy final");
    }

    @Test
    public void legacyPlaintextPersonalizationMigratesAndSanitizesWal() throws Exception {
        long termId = store.addTerm("Secret Project", "see kret", "秘密项目", "com.secret.app");
        long correctionId = store.addCorrection("old secret", "new secret", "com.secret.app");

        ContentValues term = new ContentValues();
        term.put("canonical", "Secret Project");
        term.put("canonical_key", PersonalizationStore.identity("Secret Project"));
        term.put("pronunciation", "see kret");
        term.put("aliases", "秘密项目");
        term.put("app_scope", "com.secret.app");
        term.put("app_scope_key", PersonalizationStore.identity("com.secret.app"));
        store.getWritableDatabase().update(
                "personal_terms", term, "id = ?", new String[]{Long.toString(termId)});

        ContentValues correction = new ContentValues();
        correction.put("pattern", "old secret");
        correction.put("replacement", "new secret");
        correction.put("app_scope", "com.secret.app");
        correction.put("app_scope_key", "com.secret.app");
        correction.put("identity_key", PersonalizationStore.correctionIdentity(
                "old secret", "new secret", "com.secret.app"));
        store.getWritableDatabase().update(
                "correction_rules",
                correction,
                "id = ?",
                new String[]{Long.toString(correctionId)});
        assertTrue(context.getSharedPreferences(
                        PersonalizationStore.PRIVACY_MIGRATIONS,
                        Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PersonalizationStore.PERSONALIZATION_STORAGE_SANITIZED, false)
                .commit());
        store.close();
        assertTrue(storageContains("Secret Project"));

        store = new PersonalizationStore(context);
        assertEquals("Secret Project", store.listTerms().get(0).canonical());
        assertEquals("old secret", store.listCorrections().get(0).pattern());
        try (Cursor cursor = store.getReadableDatabase().query(
                "personal_terms",
                new String[]{"canonical", "canonical_key", "app_scope_key"},
                "id = ?",
                new String[]{Long.toString(termId)},
                null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertTrue(cursor.getString(0).startsWith(
                    "opentypeless-encrypted-personalization:v1:"));
            assertTrue(cursor.getString(1).startsWith("h1:"));
            assertTrue(cursor.getString(2).startsWith("h1:"));
        }
        assertStorageDoesNotContain(
                "Secret Project", "see kret", "秘密项目", "com.secret.app",
                "old secret", "new secret");
    }

    @Test
    public void previewAndCommitUseOneValidatedAtomicPlan() {
        store.addTerm("Existing", "", "", "");
        String json = "{\"format\":\"opentypeless-personalization-v1\","
                + "\"terms\":[{\"canonical\":\"Existing\"},{\"canonical\":\"New Name\"}],"
                + "\"corrections\":[{\"pattern\":\"wrong\",\"replacement\":\"right\"}]}";

        PersonalizationStore.ImportPreview preview = store.previewPersonalization(json);
        assertEquals(1, preview.acceptedTerms());
        assertEquals(1, preview.duplicateTerms());
        assertEquals(1, preview.acceptedCorrections());
        assertTrue(preview.fitsCapacity());

        PersonalizationStore.ImportReport report = store.commitPersonalization(preview);
        assertEquals(2, report.importedTotal());
        assertEquals(2, store.listTerms().size());
        assertEquals(1, store.listCorrections().size());

        String portable = store.exportPersonalization();
        assertTrue(portable.contains("\"format\": \"opentypeless_dictionary\""));
        assertTrue(portable.contains("\"version\": 1"));
        PersonalizationStore.ImportPlan roundTrip =
                PersonalizationStore.parseImportPlan(portable);
        assertEquals(2, roundTrip.terms().size());
        assertEquals(1, roundTrip.corrections().size());
    }

    @Test
    public void managementListsPageWithoutInflatingTheWholeDatabase() {
        store.addTerm("Alpha", "", "", "");
        store.addTerm("Beta", "", "", "");
        store.addTerm("Gamma", "", "", "");
        assertEquals("Beta", store.listTerms(2, 1).get(0).canonical());
        assertEquals("Gamma", store.listTerms(2, 1).get(1).canonical());

        store.addCorrection("alpha wrong", "alpha right", "");
        store.addCorrection("beta wrong", "beta right", "");
        assertEquals("beta wrong", store.listCorrections(1, 1).get(0).pattern());

        store.addHistory(new HistoryEntry(
                0, 100, "app", "GENERAL", "VERBATIM", "SYSTEM_DEFAULT", "one", "one", 1));
        store.addHistory(new HistoryEntry(
                0, 200, "app", "GENERAL", "VERBATIM", "SYSTEM_DEFAULT", "two", "two", 1));
        store.addHistory(new HistoryEntry(
                0, 300, "app", "GENERAL", "VERBATIM", "SYSTEM_DEFAULT", "three", "three", 1));
        assertEquals("two", store.listHistory(1, 1).get(0).rawText());
    }

    @Test
    public void searchFindsUnicodeTermsAliasesCorrectionsAndScopesWithoutWildcardInjection() {
        long termId = store.addTerm(
                "ＯｐｅｎＴｙｐｅｌｅｓｓ", "open type less", "开放无类型", "com.chat.app");
        store.addTerm("Literal%_percent", "", "", "com.notes.app");
        long correctionId = store.addCorrection("雪昭", "学昭", "com.chat.app");

        assertEquals("ＯｐｅｎＴｙｐｅｌｅｓｓ", store.searchTerms("opentypeless", 10, 0).get(0).canonical());
        assertEquals("ＯｐｅｎＴｙｐｅｌｅｓｓ", store.searchTerms("开放", 10, 0).get(0).canonical());
        assertEquals("ＯｐｅｎＴｙｐｅｌｅｓｓ", store.searchTerms("chat.app", 10, 0).get(0).canonical());
        assertEquals("雪昭", store.searchCorrections("学昭", 10, 0).get(0).pattern());
        assertEquals(1, store.searchTerms("%", 10, 0).size());
        assertEquals("Literal%_percent", store.searchTerms("_", 10, 0).get(0).canonical());
        assertEquals(
                java.util.List.of("ＯｐｅｎＴｙｐｅｌｅｓｓ", "雪昭 → 学昭"),
                store.describeMatches(
                        java.util.List.of(termId, termId, -1L),
                        java.util.List.of(correctionId)));
        try (Cursor cursor = store.getReadableDatabase().query(
                "personal_terms",
                new String[]{"canonical", "pronunciation", "aliases", "app_scope",
                        "canonical_key", "app_scope_key"},
                "id = ?", new String[]{Long.toString(termId)}, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            for (int index = 0; index < 4; index++) {
                assertTrue(cursor.getString(index).startsWith(
                        "opentypeless-encrypted-personalization:v1:"));
            }
            assertTrue(cursor.getString(4).startsWith("h1:"));
            assertTrue(cursor.getString(5).startsWith("h1:"));
        }
    }

    private void assertStorageDoesNotContain(String... plaintexts) throws IOException {
        File database = context.getDatabasePath(DATABASE);
        File wal = new File(database.getPath() + "-wal");
        for (File file : new File[]{database, wal}) {
            if (!file.exists()) continue;
            byte[] bytes = Files.readAllBytes(file.toPath());
            for (String plaintext : plaintexts) {
                assertFalse(
                        file.getName() + " still contains migrated plaintext: " + plaintext,
                        contains(bytes, plaintext.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    private boolean storageContains(String plaintext) throws IOException {
        File database = context.getDatabasePath(DATABASE);
        File wal = new File(database.getPath() + "-wal");
        byte[] needle = plaintext.getBytes(StandardCharsets.UTF_8);
        for (File file : new File[]{database, wal}) {
            if (file.exists() && contains(Files.readAllBytes(file.toPath()), needle)) return true;
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return true;
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            int offset = 0;
            while (offset < needle.length && haystack[start + offset] == needle[offset]) offset++;
            if (offset == needle.length) return true;
        }
        return false;
    }
}
