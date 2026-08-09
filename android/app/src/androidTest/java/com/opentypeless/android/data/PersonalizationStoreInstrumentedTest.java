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
        store.addHistory(new HistoryEntry(
                0, System.currentTimeMillis(), "com.example", "LONG_TEXT", "SMART",
                "OPENAI_COMPATIBLE", raw, result, 1_250));

        try (Cursor cursor = store.getReadableDatabase().query(
                "dictation_history",
                new String[]{"raw_text", "final_text"},
                null, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertFalse(cursor.getString(0).contains(raw));
            assertFalse(cursor.getString(1).contains(result));
        }
        HistoryEntry decoded = store.listHistory(1).get(0);
        assertEquals(raw, decoded.rawText());
        assertEquals(result, decoded.finalText());
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

        store.addHistory(new HistoryEntry(
                0, 100, "app", "GENERAL", "VERBATIM", "SYSTEM_DEFAULT", "one", "one", 1));
        store.addHistory(new HistoryEntry(
                0, 200, "app", "GENERAL", "VERBATIM", "SYSTEM_DEFAULT", "two", "two", 1));
        store.addHistory(new HistoryEntry(
                0, 300, "app", "GENERAL", "VERBATIM", "SYSTEM_DEFAULT", "three", "three", 1));
        assertEquals("two", store.listHistory(1, 1).get(0).rawText());
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
