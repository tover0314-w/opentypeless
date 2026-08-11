package com.opentypeless.android.data;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.opentypeless.android.security.LocalTextCipher;
import com.opentypeless.android.security.LocalPersonalizationCipher;
import com.opentypeless.android.security.LocalLookupKey;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PersonalizationStore extends SQLiteOpenHelper {
    private static final String DATABASE = "opentypeless_android.db";
    private static final int VERSION = 4;
    static final int MAX_IMPORT_BYTES = 1_048_576;
    static final int MAX_IMPORT_ROWS = 10_000;
    static final int MAX_TERMS = 2_000;
    static final int MAX_CORRECTIONS = 2_000;
    static final int MAX_IMPORT_PREVIEW_SAMPLES = 5;
    private static final int MAX_HISTORY = 500;
    static final String PRIVACY_MIGRATIONS = "opentypeless_privacy_migrations";
    static final String HISTORY_STORAGE_SANITIZED = "history_text_v1_storage_sanitized";
    static final String PERSONALIZATION_STORAGE_SANITIZED =
            "personalization_text_v1_storage_sanitized";

    private final LocalTextCipher historyCipher;
    private final LocalPersonalizationCipher personalizationCipher;
    private final LocalLookupKey lookupKey;
    private final SharedPreferences privacyMigrations;

    public record ImportTerm(
            String canonical,
            String pronunciation,
            String aliases,
            String appScope,
            boolean enabled) {
        public ImportTerm {
            canonical = requireText(canonical, 120, "Term");
            pronunciation = optionalText(pronunciation, 200, "Pronunciation");
            aliases = optionalText(aliases, 500, "Aliases");
            validateAliases(aliases, canonical);
            appScope = optionalText(appScope, 200, "App scope");
        }
    }

    public record ImportCorrection(
            String pattern,
            String replacement,
            String appScope,
            boolean enabled) {
        public ImportCorrection {
            pattern = requireText(pattern, 160, "Wrong phrase");
            replacement = requireText(replacement, 160, "Correct phrase");
            appScope = optionalText(appScope, 200, "App scope");
            if (pattern.equals(replacement)) {
                throw new IllegalArgumentException("Wrong and correct phrases must be different");
            }
        }
    }

    public record ImportIssue(String section, int row, String message) {
        public ImportIssue {
            section = section == null ? "" : section;
            message = message == null ? "Invalid row" : message;
        }
    }

    public record ImportPlan(
            List<ImportTerm> terms,
            List<ImportCorrection> corrections,
            List<ImportIssue> issues) {
        public ImportPlan {
            terms = List.copyOf(terms == null ? List.of() : terms);
            corrections = List.copyOf(corrections == null ? List.of() : corrections);
            issues = List.copyOf(issues == null ? List.of() : issues);
            if ((long) terms.size() + corrections.size() + issues.size() > MAX_IMPORT_ROWS) {
                throw new IllegalArgumentException("Personalization backup has too many rows");
            }
        }
    }

    public record ImportPreview(
            ImportPlan plan,
            int acceptedTerms,
            int duplicateTerms,
            int acceptedCorrections,
            int duplicateCorrections,
            int invalidRows,
            int remainingTermCapacity,
            int remainingCorrectionCapacity,
            boolean fitsCapacity,
            List<ImportTerm> termSamples,
            List<ImportCorrection> correctionSamples) {
        public ImportPreview {
            termSamples = List.copyOf(termSamples == null ? List.of() : termSamples);
            correctionSamples = List.copyOf(
                    correctionSamples == null ? List.of() : correctionSamples);
        }

        public int acceptedTotal() {
            return acceptedTerms + acceptedCorrections;
        }
    }

    public record ImportReport(
            int importedTerms,
            int importedCorrections,
            int duplicateTerms,
            int duplicateCorrections,
            int invalidRows) {
        public int importedTotal() {
            return importedTerms + importedCorrections;
        }
    }

    record ExistingImportState(
            Set<String> termIdentities,
            Set<String> correctionIdentities,
            int termCount,
            int correctionCount) {
        ExistingImportState {
            termIdentities = Set.copyOf(termIdentities);
            correctionIdentities = Set.copyOf(correctionIdentities);
        }
    }

    interface ImportTransaction {
        void begin();

        ExistingImportState readExisting();

        void insertTerm(ImportTerm term);

        void insertCorrection(ImportCorrection correction);

        void setSuccessful();

        void end();
    }

    private record ImportSelection(
            List<ImportTerm> terms,
            List<ImportCorrection> corrections,
            ImportPreview preview) {}

    public PersonalizationStore(Context context) {
        super(context.getApplicationContext(), DATABASE, null, VERSION);
        Context application = context.getApplicationContext();
        historyCipher = new LocalTextCipher();
        personalizationCipher = new LocalPersonalizationCipher();
        lookupKey = new LocalLookupKey();
        privacyMigrations = application.getSharedPreferences(
                PRIVACY_MIGRATIONS, Context.MODE_PRIVATE);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        super.onConfigure(database);
        try (Cursor cursor = database.rawQuery("PRAGMA secure_delete = ON", null)) {
            if (!cursor.moveToFirst() || cursor.getInt(0) != 1) {
                throw new IllegalStateException("Unable to enable secure database deletion");
            }
        }
        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE personal_terms ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "canonical TEXT NOT NULL,"
                + "canonical_key TEXT NOT NULL,"
                + "pronunciation TEXT NOT NULL DEFAULT '',"
                + "aliases TEXT NOT NULL DEFAULT '',"
                + "app_scope TEXT NOT NULL DEFAULT '',"
                + "app_scope_key TEXT NOT NULL DEFAULT '',"
                + "use_count INTEGER NOT NULL DEFAULT 0,"
                + "enabled INTEGER NOT NULL DEFAULT 1,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        database.execSQL("CREATE UNIQUE INDEX personal_terms_identity "
                + "ON personal_terms(canonical_key, app_scope_key)");
        database.execSQL("CREATE TABLE correction_rules ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "pattern TEXT NOT NULL,"
                + "replacement TEXT NOT NULL,"
                + "app_scope TEXT NOT NULL DEFAULT '',"
                + "app_scope_key TEXT NOT NULL DEFAULT '',"
                + "identity_key TEXT NOT NULL,"
                + "use_count INTEGER NOT NULL DEFAULT 0,"
                + "enabled INTEGER NOT NULL DEFAULT 1,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        database.execSQL("CREATE UNIQUE INDEX correction_rules_identity "
                + "ON correction_rules(identity_key)");
        database.execSQL("CREATE TABLE dictation_history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "created_at INTEGER NOT NULL,"
                + "app_package TEXT NOT NULL DEFAULT '',"
                + "field_kind TEXT NOT NULL DEFAULT 'GENERAL',"
                + "mode TEXT NOT NULL,"
                + "backend TEXT NOT NULL,"
                + "raw_text TEXT NOT NULL,"
                + "final_text TEXT NOT NULL,"
                + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "applied_rules TEXT NOT NULL DEFAULT '')");
        database.execSQL("CREATE INDEX dictation_history_created_at "
                + "ON dictation_history(created_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            database.execSQL("DROP INDEX IF EXISTS personal_terms_identity");
            database.execSQL("DROP INDEX IF EXISTS correction_rules_identity");
            database.execSQL("ALTER TABLE personal_terms ADD COLUMN canonical_key TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE personal_terms ADD COLUMN app_scope_key TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE correction_rules ADD COLUMN identity_key TEXT NOT NULL DEFAULT ''");
            try (Cursor cursor = database.rawQuery(
                    "SELECT id, canonical, app_scope FROM personal_terms", null)) {
                while (cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    values.put("canonical_key", identity(cursor.getString(1)));
                    values.put("app_scope_key", identity(cursor.getString(2)));
                    database.update("personal_terms", values, "id = ?",
                            new String[]{Long.toString(cursor.getLong(0))});
                }
            }
            try (Cursor cursor = database.rawQuery(
                    "SELECT id, pattern, replacement, app_scope FROM correction_rules", null)) {
                while (cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    values.put("identity_key", correctionIdentity(
                            cursor.getString(1), cursor.getString(2), cursor.getString(3)));
                    database.update("correction_rules", values, "id = ?",
                            new String[]{Long.toString(cursor.getLong(0))});
                }
            }
            database.execSQL("DELETE FROM personal_terms WHERE id NOT IN "
                    + "(SELECT MIN(id) FROM personal_terms GROUP BY canonical_key, app_scope_key)");
            database.execSQL("DELETE FROM correction_rules WHERE id NOT IN "
                    + "(SELECT MIN(id) FROM correction_rules GROUP BY identity_key)");
            database.execSQL("CREATE UNIQUE INDEX personal_terms_identity "
                    + "ON personal_terms(canonical_key, app_scope_key)");
            database.execSQL("CREATE UNIQUE INDEX correction_rules_identity "
                    + "ON correction_rules(identity_key)");
        }
        if (oldVersion < 3) {
            database.execSQL("ALTER TABLE dictation_history "
                    + "ADD COLUMN applied_rules TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 4) {
            database.execSQL("ALTER TABLE correction_rules "
                    + "ADD COLUMN app_scope_key TEXT NOT NULL DEFAULT ''");
        }
    }

    @Override
    @SuppressLint("ApplySharedPref") // The marker gates an at-rest privacy migration on next open.
    public void onOpen(SQLiteDatabase database) {
        super.onOpen(database);
        boolean historySanitized = privacyMigrations.getBoolean(
                HISTORY_STORAGE_SANITIZED, false);
        boolean personalizationSanitized = privacyMigrations.getBoolean(
                PERSONALIZATION_STORAGE_SANITIZED, false);
        if (historySanitized && personalizationSanitized) return;
        if (!historySanitized) migrateLegacyHistoryText(database);
        if (!personalizationSanitized) migrateLegacyPersonalization(database);
        truncateWriteAheadLog(database);
        // Synchronous persistence is intentional: after these bits are true, a later open may
        // skip the bounded scans. A failed write leaves both markers conservative and retryable.
        if (!privacyMigrations.edit()
                .putBoolean(HISTORY_STORAGE_SANITIZED, true)
                .putBoolean(PERSONALIZATION_STORAGE_SANITIZED, true)
                .commit()) {
            throw new IllegalStateException("Unable to finish secure local-data migration");
        }
    }

    public synchronized long addTerm(
            String canonical,
            String pronunciation,
            String aliases,
            String appScope) {
        String cleanCanonical = requireText(canonical, 120, "Term");
        String cleanPronunciation = optionalText(pronunciation, 200, "Pronunciation");
        String cleanAliases = optionalText(aliases, 500, "Aliases");
        validateAliases(cleanAliases, cleanCanonical);
        String cleanScope = optionalText(appScope, 200, "App scope");
        ensureCapacity("personal_terms", MAX_TERMS, "Personal dictionary is full");
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("canonical", personalizationCipher.encrypt(cleanCanonical));
        values.put("canonical_key", canonicalLookup(cleanCanonical));
        values.put("pronunciation", personalizationCipher.encrypt(cleanPronunciation));
        values.put("aliases", personalizationCipher.encrypt(cleanAliases));
        values.put("app_scope", personalizationCipher.encrypt(cleanScope));
        values.put("app_scope_key", scopeLookup(cleanScope));
        values.put("created_at", now);
        values.put("updated_at", now);
        try {
            return getWritableDatabase().insertOrThrow("personal_terms", null, values);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("That term already exists for this app scope", error);
        }
    }

    public synchronized long addCorrection(String pattern, String replacement, String appScope) {
        String cleanPattern = requireText(pattern, 160, "Wrong phrase");
        String cleanReplacement = requireText(replacement, 160, "Correct phrase");
        if (cleanPattern.equals(cleanReplacement)) {
            throw new IllegalArgumentException("Wrong and correct phrases must be different");
        }
        String cleanScope = optionalText(appScope, 200, "App scope");
        ensureCapacity("correction_rules", MAX_CORRECTIONS, "Correction list is full");
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("pattern", personalizationCipher.encrypt(cleanPattern));
        values.put("replacement", personalizationCipher.encrypt(cleanReplacement));
        values.put("app_scope", personalizationCipher.encrypt(cleanScope));
        values.put("app_scope_key", scopeLookup(cleanScope));
        values.put("identity_key", correctionLookup(
                cleanPattern, cleanReplacement, cleanScope));
        values.put("created_at", now);
        values.put("updated_at", now);
        try {
            return getWritableDatabase().insertOrThrow("correction_rules", null, values);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("That correction already exists for this app scope", error);
        }
    }

    public synchronized void deleteTerm(long id) {
        getWritableDatabase().delete("personal_terms", "id = ?", new String[]{Long.toString(id)});
    }

    public synchronized void deleteCorrection(long id) {
        getWritableDatabase().delete("correction_rules", "id = ?", new String[]{Long.toString(id)});
    }

    public synchronized void setTermEnabled(long id, boolean enabled) {
        updateEnabled("personal_terms", id, enabled);
    }

    public synchronized void setCorrectionEnabled(long id, boolean enabled) {
        updateEnabled("correction_rules", id, enabled);
    }

    public synchronized List<PersonalTerm> listTerms() {
        return listTerms(MAX_TERMS, 0);
    }

    public synchronized List<PersonalTerm> listTerms(int limit, int offset) {
        return searchTerms("", limit, offset);
    }

    public synchronized List<PersonalTerm> searchTerms(String query, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_TERMS));
        int safeOffset = Math.max(0, Math.min(offset, MAX_TERMS));
        String normalizedQuery = identity(query);
        List<PersonalTerm> matches = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "personal_terms",
                new String[]{"id", "canonical", "pronunciation", "aliases", "app_scope",
                        "use_count", "enabled"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                PersonalTerm term = readTerm(cursor);
                if (matchesTerm(term, normalizedQuery)) matches.add(term);
            }
        }
        matches.sort(Comparator
                .comparing((PersonalTerm term) -> identity(term.appScope()))
                .thenComparing(term -> identity(term.canonical())));
        return page(matches, safeOffset, safeLimit);
    }

    public synchronized List<CorrectionRule> listCorrections() {
        return listCorrections(MAX_CORRECTIONS, 0);
    }

    public synchronized List<CorrectionRule> listCorrections(int limit, int offset) {
        return searchCorrections("", limit, offset);
    }

    public synchronized List<CorrectionRule> searchCorrections(String query, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_CORRECTIONS));
        int safeOffset = Math.max(0, Math.min(offset, MAX_CORRECTIONS));
        String normalizedQuery = identity(query);
        List<CorrectionRule> matches = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "correction_rules",
                new String[]{"id", "pattern", "replacement", "app_scope", "use_count", "enabled"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                CorrectionRule correction = readCorrection(cursor);
                if (matchesCorrection(correction, normalizedQuery)) matches.add(correction);
            }
        }
        matches.sort(Comparator
                .comparing((CorrectionRule rule) -> identity(rule.appScope()))
                .thenComparing(rule -> identity(rule.pattern())));
        return page(matches, safeOffset, safeLimit);
    }

    public synchronized PersonalizationSnapshot snapshot(String appPackage) {
        String scopeKey = scopeLookup(appPackage);
        String globalScopeKey = scopeLookup("");
        List<PersonalTerm> terms = new ArrayList<>();
        String termSql = "SELECT id, canonical, pronunciation, aliases, app_scope, use_count, enabled "
                + "FROM personal_terms WHERE enabled = 1 AND app_scope_key IN (?, ?) "
                + "ORDER BY CASE WHEN app_scope_key = ? THEN 0 ELSE 1 END, use_count DESC, id ASC LIMIT 80";
        try (Cursor cursor = getReadableDatabase().rawQuery(
                termSql, new String[]{globalScopeKey, scopeKey, scopeKey})) {
            while (cursor.moveToNext()) terms.add(readTerm(cursor));
        }

        List<CorrectionRule> corrections = new ArrayList<>();
        String correctionSql = "SELECT id, pattern, replacement, app_scope, use_count, enabled "
                + "FROM correction_rules WHERE enabled = 1 AND app_scope_key IN (?, ?) "
                + "ORDER BY CASE WHEN app_scope_key = ? THEN 0 ELSE 1 END, "
                + "use_count DESC, id ASC LIMIT 100";
        try (Cursor cursor = getReadableDatabase().rawQuery(
                correctionSql,
                new String[]{globalScopeKey, scopeKey, scopeKey})) {
            while (cursor.moveToNext()) corrections.add(readCorrection(cursor));
        }
        return new PersonalizationSnapshot(List.copyOf(terms), List.copyOf(corrections));
    }

    public synchronized void markTermsUsed(List<Long> ids) {
        incrementUseCount("personal_terms", ids);
    }

    public synchronized void markCorrectionsUsed(List<Long> ids) {
        incrementUseCount("correction_rules", ids);
    }

    /** Human-readable, bounded evidence shown after a deterministic personal rule is applied. */
    public synchronized List<String> describeMatches(
            List<Long> termIds,
            List<Long> correctionIds) {
        List<String> descriptions = new ArrayList<>();
        appendTermDescriptions(descriptions, boundedPositiveIds(termIds, 80));
        appendCorrectionDescriptions(descriptions, boundedPositiveIds(correctionIds, 100));
        return List.copyOf(descriptions);
    }

    public synchronized long addHistory(HistoryEntry entry) {
        ContentValues values = new ContentValues();
        values.put("created_at", entry.createdAt());
        values.put("app_package", safe(entry.appPackage()));
        values.put("field_kind", safe(entry.fieldKind()));
        values.put("mode", safe(entry.mode()));
        values.put("backend", safe(entry.backend()));
        values.put("raw_text", historyCipher.encrypt(limit(safe(entry.rawText()), 20_000)));
        values.put("final_text", historyCipher.encrypt(limit(safe(entry.finalText()), 20_000)));
        values.put("duration_ms", Math.max(0L, entry.durationMs()));
        values.put("applied_rules", historyCipher.encrypt(limit(safe(entry.appliedRules()), 2_000)));
        long id = getWritableDatabase().insertOrThrow("dictation_history", null, values);
        getWritableDatabase().execSQL(
                "DELETE FROM dictation_history WHERE id NOT IN "
                        + "(SELECT id FROM dictation_history ORDER BY created_at DESC, id DESC LIMIT ?)",
                new Object[]{MAX_HISTORY});
        return id;
    }

    public synchronized List<HistoryEntry> listHistory(int limit) {
        return listHistory(limit, 0);
    }

    public synchronized List<HistoryEntry> listHistory(int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY));
        int safeOffset = Math.max(0, Math.min(offset, MAX_HISTORY));
        try (Cursor cursor = getReadableDatabase().query(
                "dictation_history",
                new String[]{"id", "created_at", "app_package", "field_kind", "mode", "backend",
                        "raw_text", "final_text", "duration_ms", "applied_rules"},
                null,
                null,
                null,
                null,
                "created_at DESC, id DESC",
                paginationLimit(safeLimit, safeOffset))) {
            List<HistoryEntry> result = new ArrayList<>();
            while (cursor.moveToNext()) result.add(readHistory(cursor));
            return result;
        }
    }

    public synchronized HistoryEntry history(long id) {
        try (Cursor cursor = getReadableDatabase().query(
                "dictation_history",
                new String[]{"id", "created_at", "app_package", "field_kind", "mode", "backend",
                        "raw_text", "final_text", "duration_ms", "applied_rules"},
                "id = ?",
                new String[]{Long.toString(id)},
                null,
                null,
                null,
                "1")) {
            return cursor.moveToFirst() ? readHistory(cursor) : null;
        }
    }

    public synchronized void clearHistory() {
        getWritableDatabase().delete("dictation_history", null, null);
    }

    public synchronized void deleteHistory(long id) {
        if (id <= 0) return;
        getWritableDatabase().delete(
                "dictation_history",
                "id = ?",
                new String[]{Long.toString(id)});
    }

    public synchronized String exportPersonalization() {
        try {
            JSONObject root = new JSONObject();
            // Use the desktop dictionary v1 envelope so names and corrections can move in both
            // directions. Android-only fields are additive and ignored by older desktop builds.
            root.put("format", "opentypeless_dictionary");
            root.put("version", 1);
            JSONArray terms = new JSONArray();
            for (PersonalTerm term : listTerms()) {
                terms.put(new JSONObject()
                        .put("word", term.canonical())
                        .put("pronunciation", term.pronunciation())
                        .put("aliases", term.aliases())
                        .put("appScope", term.appScope())
                        .put("enabled", term.enabled()));
            }
            JSONArray corrections = new JSONArray();
            for (CorrectionRule rule : listCorrections()) {
                corrections.put(new JSONObject()
                        .put("pattern", rule.pattern())
                        .put("replacement", rule.replacement())
                        .put("appScope", rule.appScope())
                        .put("enabled", rule.enabled()));
            }
            root.put("dictionary", terms);
            root.put("correctionRules", corrections);
            return root.toString(2);
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to export personalization", error);
        }
    }

    /** Parses and validates an import without mutating the database. */
    public synchronized ImportPreview previewPersonalization(String json) {
        ImportPlan plan = parseImportPlan(json);
        return selectImport(plan, readExisting(getReadableDatabase())).preview();
    }

    /**
     * Commits exactly the immutable plan returned by {@link #previewPersonalization(String)}.
     * Database state is re-read inside the transaction, so duplicates or capacity changes between
     * preview and commit cannot produce a partial import.
     */
    public synchronized ImportReport commitPersonalization(ImportPreview preview) {
        if (preview == null || preview.plan() == null) {
            throw new IllegalArgumentException("Import preview is required");
        }
        return commitPlan(preview.plan(), new SQLiteImportTransaction(getWritableDatabase()));
    }

    /** Compatibility helper for callers that do not present an import preview UI yet. */
    public synchronized int importPersonalization(String json) {
        return commitPersonalization(previewPersonalization(json)).importedTotal();
    }

    static void validateImportDocument(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Personalization backup is required");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("Personalization backup exceeds 1 MiB");
        }
    }

    static ImportPlan parseImportPlan(String json) {
        validateImportDocument(json);
        try {
            JSONObject root = new JSONObject(json);
            String format = root.optString("format", "");
            boolean legacyAndroid = "opentypeless-personalization-v1".equals(format);
            boolean desktopDictionary = "opentypeless_dictionary".equals(format);
            boolean unmarkedDesktopSubset = format.isEmpty()
                    && (root.has("dictionary")
                    || root.has("correctionRules")
                    || root.has("correction_rules"));
            if (!legacyAndroid && !desktopDictionary && !unmarkedDesktopSubset) {
                throw new IllegalArgumentException("Unsupported personalization backup");
            }
            if (desktopDictionary && (!root.has("version")
                    || !(root.get("version") instanceof Number version)
                    || version.intValue() != 1
                    || version.doubleValue() != 1.0d)) {
                throw new IllegalArgumentException("Unsupported personalization backup");
            }
            boolean desktopShape = desktopDictionary || unmarkedDesktopSubset;
            JSONArray terms = optionalArray(root, desktopShape ? "dictionary" : "terms");
            JSONArray corrections = desktopShape
                    ? optionalArrayAlias(root, "correctionRules", "correction_rules")
                    : optionalArray(root, "corrections");
            int termCount = terms == null ? 0 : terms.length();
            int correctionCount = corrections == null ? 0 : corrections.length();
            if ((long) termCount + correctionCount > MAX_IMPORT_ROWS) {
                throw new IllegalArgumentException("Personalization backup has too many rows");
            }

            List<ImportTerm> parsedTerms = new ArrayList<>();
            List<ImportCorrection> parsedCorrections = new ArrayList<>();
            List<ImportIssue> issues = new ArrayList<>();
            if (terms != null) {
                for (int index = 0; index < terms.length(); index++) {
                    try {
                        Object row = terms.get(index);
                        if (desktopShape && row instanceof String word) {
                            parsedTerms.add(new ImportTerm(word, "", "", "", true));
                        } else {
                            JSONObject term = requireObject(terms, index);
                            parsedTerms.add(new ImportTerm(
                                    requireString(term, desktopShape ? "word" : "canonical"),
                                    optionalString(term, "pronunciation"),
                                    optionalString(term, "aliases"),
                                    optionalString(term, "appScope"),
                                    optionalBoolean(term, "enabled", true)));
                        }
                    } catch (IllegalArgumentException | JSONException error) {
                        issues.add(new ImportIssue("terms", index + 1, safeMessage(error)));
                    }
                }
            }
            if (corrections != null) {
                for (int index = 0; index < corrections.length(); index++) {
                    try {
                        JSONObject correction = requireObject(corrections, index);
                        parsedCorrections.add(new ImportCorrection(
                                requireStringAlias(
                                        correction,
                                        "pattern",
                                        "wrong_phrase",
                                        "wrongPhrase"),
                                requireStringAlias(
                                        correction,
                                        "replacement",
                                        "corrected_phrase",
                                        "correctedPhrase",
                                        "correct_phrase"),
                                optionalString(correction, "appScope"),
                                optionalBoolean(correction, "enabled", true)));
                    } catch (IllegalArgumentException | JSONException error) {
                        issues.add(new ImportIssue("corrections", index + 1, safeMessage(error)));
                    }
                }
            }
            return new ImportPlan(parsedTerms, parsedCorrections, issues);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid personalization backup", error);
        }
    }

    private static JSONArray optionalArray(JSONObject root, String name) throws JSONException {
        if (!root.has(name) || root.isNull(name)) return null;
        Object value = root.get(name);
        if (value instanceof JSONArray array) return array;
        throw new JSONException(name + " must be an array");
    }

    private static JSONArray optionalArrayAlias(
            JSONObject root,
            String primary,
            String fallback) throws JSONException {
        if (root.has(primary)) return optionalArray(root, primary);
        return optionalArray(root, fallback);
    }

    private static JSONObject requireObject(JSONArray array, int index) throws JSONException {
        Object value = array.get(index);
        if (value instanceof JSONObject object) return object;
        throw new JSONException("Row must be an object");
    }

    private static String requireString(JSONObject object, String name) throws JSONException {
        if (!object.has(name) || object.isNull(name)) {
            throw new JSONException(name + " is required");
        }
        Object value = object.get(name);
        if (value instanceof String text) return text;
        throw new JSONException(name + " must be a string");
    }

    private static String requireStringAlias(
            JSONObject object,
            String... names) throws JSONException {
        for (String name : names) {
            if (object.has(name) && !object.isNull(name)) return requireString(object, name);
        }
        throw new JSONException(names[0] + " is required");
    }

    private static String optionalString(JSONObject object, String name) throws JSONException {
        if (!object.has(name) || object.isNull(name)) return "";
        Object value = object.get(name);
        if (value instanceof String text) return text;
        throw new JSONException(name + " must be a string");
    }

    private static boolean optionalBoolean(JSONObject object, String name, boolean fallback)
            throws JSONException {
        if (!object.has(name) || object.isNull(name)) return fallback;
        Object value = object.get(name);
        if (value instanceof Boolean bool) return bool;
        throw new JSONException(name + " must be a boolean");
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "Invalid row" : message;
    }

    static ImportPreview previewPlan(ImportPlan plan, ExistingImportState state) {
        return selectImport(plan, state).preview();
    }

    private static ImportSelection selectImport(ImportPlan plan, ExistingImportState state) {
        Set<String> termIdentities = new LinkedHashSet<>(state.termIdentities());
        List<ImportTerm> acceptedTerms = new ArrayList<>();
        int duplicateTerms = 0;
        for (ImportTerm term : plan.terms()) {
            if (termIdentities.add(termIdentity(term.canonical(), term.appScope()))) {
                acceptedTerms.add(term);
            } else {
                duplicateTerms++;
            }
        }

        Set<String> correctionIdentities = new LinkedHashSet<>(state.correctionIdentities());
        List<ImportCorrection> acceptedCorrections = new ArrayList<>();
        int duplicateCorrections = 0;
        for (ImportCorrection correction : plan.corrections()) {
            String key = correctionIdentity(
                    correction.pattern(), correction.replacement(), correction.appScope());
            if (correctionIdentities.add(key)) {
                acceptedCorrections.add(correction);
            } else {
                duplicateCorrections++;
            }
        }

        int remainingTerms = Math.max(0, MAX_TERMS - state.termCount());
        int remainingCorrections = Math.max(0, MAX_CORRECTIONS - state.correctionCount());
        boolean fitsCapacity = acceptedTerms.size() <= remainingTerms
                && acceptedCorrections.size() <= remainingCorrections;
        ImportPreview preview = new ImportPreview(
                plan,
                acceptedTerms.size(),
                duplicateTerms,
                acceptedCorrections.size(),
                duplicateCorrections,
                plan.issues().size(),
                remainingTerms,
                remainingCorrections,
                fitsCapacity,
                firstSamples(acceptedTerms),
                firstSamples(acceptedCorrections));
        return new ImportSelection(
                List.copyOf(acceptedTerms), List.copyOf(acceptedCorrections), preview);
    }

    static ImportReport commitPlan(ImportPlan plan, ImportTransaction transaction) {
        transaction.begin();
        try {
            ImportSelection selection = selectImport(plan, transaction.readExisting());
            ImportPreview preview = selection.preview();
            if (!preview.fitsCapacity()) {
                throw new IllegalArgumentException(
                        "Personalization backup exceeds local capacity: terms need "
                                + preview.acceptedTerms() + " of "
                                + preview.remainingTermCapacity() + ", corrections need "
                                + preview.acceptedCorrections() + " of "
                                + preview.remainingCorrectionCapacity());
            }
            for (ImportTerm term : selection.terms()) transaction.insertTerm(term);
            for (ImportCorrection correction : selection.corrections()) {
                transaction.insertCorrection(correction);
            }
            transaction.setSuccessful();
            return new ImportReport(
                    selection.terms().size(),
                    selection.corrections().size(),
                    preview.duplicateTerms(),
                    preview.duplicateCorrections(),
                    preview.invalidRows());
        } finally {
            transaction.end();
        }
    }

    private static <T> List<T> firstSamples(List<T> values) {
        return List.copyOf(values.subList(
                0, Math.min(values.size(), MAX_IMPORT_PREVIEW_SAMPLES)));
    }

    private ExistingImportState readExisting(SQLiteDatabase database) {
        Set<String> terms = new LinkedHashSet<>();
        int termCount = 0;
        try (Cursor cursor = database.query(
                "personal_terms",
                new String[]{"canonical", "app_scope"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                termCount++;
                terms.add(termIdentity(
                        personalizationCipher.decryptOrLegacy(cursor.getString(0)),
                        personalizationCipher.decryptOrLegacy(cursor.getString(1))));
            }
        }

        Set<String> corrections = new LinkedHashSet<>();
        int correctionCount = 0;
        try (Cursor cursor = database.query(
                "correction_rules",
                new String[]{"pattern", "replacement", "app_scope"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                correctionCount++;
                corrections.add(correctionIdentity(
                        personalizationCipher.decryptOrLegacy(cursor.getString(0)),
                        personalizationCipher.decryptOrLegacy(cursor.getString(1)),
                        personalizationCipher.decryptOrLegacy(cursor.getString(2))));
            }
        }
        return new ExistingImportState(terms, corrections, termCount, correctionCount);
    }

    private final class SQLiteImportTransaction implements ImportTransaction {
        private final SQLiteDatabase database;

        private SQLiteImportTransaction(SQLiteDatabase database) {
            this.database = database;
        }

        @Override
        public void begin() {
            database.beginTransaction();
        }

        @Override
        public ExistingImportState readExisting() {
            return PersonalizationStore.this.readExisting(database);
        }

        @Override
        public void insertTerm(ImportTerm term) {
            database.insertOrThrow("personal_terms", null, termValues(term));
        }

        @Override
        public void insertCorrection(ImportCorrection correction) {
            database.insertOrThrow("correction_rules", null, correctionValues(correction));
        }

        @Override
        public void setSuccessful() {
            database.setTransactionSuccessful();
        }

        @Override
        public void end() {
            database.endTransaction();
        }
    }

    private ContentValues termValues(
            String canonical,
            String pronunciation,
            String aliases,
            String appScope,
            boolean enabled) {
        return termValues(new ImportTerm(
                canonical, pronunciation, aliases, appScope, enabled));
    }

    private ContentValues termValues(ImportTerm term) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("canonical", personalizationCipher.encrypt(term.canonical()));
        values.put("canonical_key", canonicalLookup(term.canonical()));
        values.put("pronunciation", personalizationCipher.encrypt(term.pronunciation()));
        values.put("aliases", personalizationCipher.encrypt(term.aliases()));
        values.put("app_scope", personalizationCipher.encrypt(term.appScope()));
        values.put("app_scope_key", scopeLookup(term.appScope()));
        values.put("enabled", term.enabled() ? 1 : 0);
        values.put("created_at", now);
        values.put("updated_at", now);
        return values;
    }

    private ContentValues correctionValues(
            String pattern,
            String replacement,
            String appScope,
            boolean enabled) {
        return correctionValues(new ImportCorrection(pattern, replacement, appScope, enabled));
    }

    private ContentValues correctionValues(ImportCorrection correction) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("pattern", personalizationCipher.encrypt(correction.pattern()));
        values.put("replacement", personalizationCipher.encrypt(correction.replacement()));
        values.put("app_scope", personalizationCipher.encrypt(correction.appScope()));
        values.put("app_scope_key", scopeLookup(correction.appScope()));
        values.put("identity_key", correctionLookup(
                correction.pattern(), correction.replacement(), correction.appScope()));
        values.put("enabled", correction.enabled() ? 1 : 0);
        values.put("created_at", now);
        values.put("updated_at", now);
        return values;
    }

    private void updateEnabled(String table, long id, boolean enabled) {
        ContentValues values = new ContentValues();
        values.put("enabled", enabled ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(table, values, "id = ?", new String[]{Long.toString(id)});
    }

    private void incrementUseCount(String table, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Set<Long> uniqueIds = new LinkedHashSet<>(ids);
            for (Long id : uniqueIds) {
                if (id != null && id > 0) {
                    database.execSQL("UPDATE " + table
                            + " SET use_count = use_count + 1, updated_at = ? WHERE id = ?",
                            new Object[]{System.currentTimeMillis(), id});
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private void ensureCapacity(String table, int maximum, String message) {
        if (tableCount(table) >= maximum) throw new IllegalArgumentException(message);
    }

    private int tableCount(String table) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private PersonalTerm readTerm(Cursor cursor) {
        return new PersonalTerm(
                cursor.getLong(0),
                personalizationCipher.decryptOrLegacy(cursor.getString(1)),
                personalizationCipher.decryptOrLegacy(cursor.getString(2)),
                personalizationCipher.decryptOrLegacy(cursor.getString(3)),
                personalizationCipher.decryptOrLegacy(cursor.getString(4)),
                cursor.getInt(5), cursor.getInt(6) != 0);
    }

    private CorrectionRule readCorrection(Cursor cursor) {
        return new CorrectionRule(
                cursor.getLong(0),
                personalizationCipher.decryptOrLegacy(cursor.getString(1)),
                personalizationCipher.decryptOrLegacy(cursor.getString(2)),
                personalizationCipher.decryptOrLegacy(cursor.getString(3)),
                cursor.getInt(4), cursor.getInt(5) != 0);
    }

    private HistoryEntry readHistory(Cursor cursor) {
        return new HistoryEntry(
                cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5),
                historyCipher.decryptOrLegacy(cursor.getString(6)),
                historyCipher.decryptOrLegacy(cursor.getString(7)), cursor.getLong(8),
                historyCipher.decryptOrLegacy(cursor.getString(9)));
    }

    private void appendTermDescriptions(List<String> output, List<Long> ids) {
        if (ids.isEmpty()) return;
        try (Cursor cursor = getReadableDatabase().query(
                "personal_terms",
                new String[]{"canonical"},
                "id IN (" + placeholders(ids.size()) + ")",
                stringIds(ids),
                null,
                null,
                "id ASC")) {
            while (cursor.moveToNext()) {
                output.add(personalizationCipher.decryptOrLegacy(cursor.getString(0)));
            }
        }
    }

    private void appendCorrectionDescriptions(List<String> output, List<Long> ids) {
        if (ids.isEmpty()) return;
        try (Cursor cursor = getReadableDatabase().query(
                "correction_rules",
                new String[]{"pattern", "replacement"},
                "id IN (" + placeholders(ids.size()) + ")",
                stringIds(ids),
                null,
                null,
                "id ASC")) {
            while (cursor.moveToNext()) {
                output.add(personalizationCipher.decryptOrLegacy(cursor.getString(0))
                        + " → "
                        + personalizationCipher.decryptOrLegacy(cursor.getString(1)));
            }
        }
    }

    private static List<Long> boundedPositiveIds(List<Long> values, int maximum) {
        if (values == null || values.isEmpty()) return List.of();
        Set<Long> unique = new LinkedHashSet<>();
        for (Long value : values) {
            if (value != null && value > 0L) unique.add(value);
            if (unique.size() >= maximum) break;
        }
        return List.copyOf(unique);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String[] stringIds(List<Long> values) {
        String[] result = new String[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = Long.toString(values.get(index));
        }
        return result;
    }

    private boolean migrateLegacyHistoryText(SQLiteDatabase database) {
        List<Long> legacyIds = new ArrayList<>();
        List<String> rawTexts = new ArrayList<>();
        List<String> finalTexts = new ArrayList<>();
        database.beginTransaction();
        try {
            try (Cursor cursor = database.query(
                    "dictation_history",
                    new String[]{"id", "raw_text", "final_text"},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    String raw = cursor.getString(1);
                    String finalText = cursor.getString(2);
                    if (!historyCipher.isEncrypted(raw) || !historyCipher.isEncrypted(finalText)) {
                        legacyIds.add(cursor.getLong(0));
                        rawTexts.add(raw);
                        finalTexts.add(finalText);
                    }
                }
            }
            for (int index = 0; index < legacyIds.size(); index++) {
                String raw = rawTexts.get(index);
                String finalText = finalTexts.get(index);
                ContentValues values = new ContentValues();
                values.put("raw_text", historyCipher.isEncrypted(raw)
                        ? raw
                        : historyCipher.encrypt(raw));
                values.put("final_text", historyCipher.isEncrypted(finalText)
                        ? finalText
                        : historyCipher.encrypt(finalText));
                database.update(
                        "dictation_history",
                        values,
                        "id = ?",
                        new String[]{Long.toString(legacyIds.get(index))});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return !legacyIds.isEmpty();
    }

    private boolean migrateLegacyPersonalization(SQLiteDatabase database) {
        boolean[] changed = {false};
        database.beginTransaction();
        try {
            try (Cursor cursor = database.query(
                    "personal_terms",
                    new String[]{"id", "canonical", "canonical_key", "pronunciation", "aliases",
                            "app_scope", "app_scope_key"},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    String canonicalStored = cursor.getString(1);
                    String pronunciationStored = cursor.getString(3);
                    String aliasesStored = cursor.getString(4);
                    String scopeStored = cursor.getString(5);
                    boolean protectedRow = personalizationCipher.isEncrypted(canonicalStored)
                            && personalizationCipher.isEncrypted(pronunciationStored)
                            && personalizationCipher.isEncrypted(aliasesStored)
                            && personalizationCipher.isEncrypted(scopeStored)
                            && lookupKey.isDigest(cursor.getString(2))
                            && lookupKey.isDigest(cursor.getString(6));
                    if (protectedRow) continue;
                    String canonical = personalizationCipher.decryptOrLegacy(canonicalStored);
                    String pronunciation = personalizationCipher.decryptOrLegacy(pronunciationStored);
                    String aliases = personalizationCipher.decryptOrLegacy(aliasesStored);
                    String scope = personalizationCipher.decryptOrLegacy(scopeStored);
                    ContentValues values = new ContentValues();
                    values.put("canonical", personalizationCipher.encrypt(canonical));
                    values.put("canonical_key", canonicalLookup(canonical));
                    values.put("pronunciation", personalizationCipher.encrypt(pronunciation));
                    values.put("aliases", personalizationCipher.encrypt(aliases));
                    values.put("app_scope", personalizationCipher.encrypt(scope));
                    values.put("app_scope_key", scopeLookup(scope));
                    database.update("personal_terms", values, "id = ?",
                            new String[]{Long.toString(cursor.getLong(0))});
                    changed[0] = true;
                }
            }
            try (Cursor cursor = database.query(
                    "correction_rules",
                    new String[]{"id", "pattern", "replacement", "app_scope",
                            "app_scope_key", "identity_key"},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    String patternStored = cursor.getString(1);
                    String replacementStored = cursor.getString(2);
                    String scopeStored = cursor.getString(3);
                    boolean protectedRow = personalizationCipher.isEncrypted(patternStored)
                            && personalizationCipher.isEncrypted(replacementStored)
                            && personalizationCipher.isEncrypted(scopeStored)
                            && lookupKey.isDigest(cursor.getString(4))
                            && lookupKey.isDigest(cursor.getString(5));
                    if (protectedRow) continue;
                    String pattern = personalizationCipher.decryptOrLegacy(patternStored);
                    String replacement = personalizationCipher.decryptOrLegacy(replacementStored);
                    String scope = personalizationCipher.decryptOrLegacy(scopeStored);
                    ContentValues values = new ContentValues();
                    values.put("pattern", personalizationCipher.encrypt(pattern));
                    values.put("replacement", personalizationCipher.encrypt(replacement));
                    values.put("app_scope", personalizationCipher.encrypt(scope));
                    values.put("app_scope_key", scopeLookup(scope));
                    values.put("identity_key", correctionLookup(pattern, replacement, scope));
                    database.update("correction_rules", values, "id = ?",
                            new String[]{Long.toString(cursor.getLong(0))});
                    changed[0] = true;
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return changed[0];
    }

    private static void truncateWriteAheadLog(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            if (!cursor.moveToFirst() || cursor.getInt(0) != 0) {
                throw new IllegalStateException("Unable to sanitize migrated history storage");
            }
        }
    }

    private static String requireText(String value, int maximum, String label) {
        String clean = optionalText(value, maximum, label);
        if (clean.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return clean;
    }

    private static String optionalText(String value, int maximum, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.codePointCount(0, clean.length()) > maximum) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return clean;
    }

    private static void validateAliases(String aliases, String canonical) {
        if (aliases == null || aliases.isBlank()) return;
        Set<String> unique = new LinkedHashSet<>();
        String canonicalKey = identity(canonical);
        for (String candidate : aliases.split("[,，;；\\n]")) {
            String key = identity(candidate);
            if (!key.isEmpty() && !key.equals(canonicalKey)) unique.add(key);
            if (unique.size() > PersonalTerm.MAX_ALIASES) {
                throw new IllegalArgumentException(
                        "Aliases are limited to " + PersonalTerm.MAX_ALIASES + " per term");
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String paginationLimit(int limit, int offset) {
        // Android 8.0's SQLiteQueryBuilder rejects the newer "LIMIT n OFFSET m" spelling.
        // SQLite's equivalent "LIMIT offset,count" form passes every supported API validator.
        return offset + "," + limit;
    }

    private static boolean matchesTerm(PersonalTerm term, String query) {
        if (query.isEmpty()) return true;
        return identity(term.canonical()).contains(query)
                || identity(term.pronunciation()).contains(query)
                || identity(term.aliases()).contains(query)
                || identity(term.appScope()).contains(query);
    }

    private static boolean matchesCorrection(CorrectionRule rule, String query) {
        if (query.isEmpty()) return true;
        return identity(rule.pattern()).contains(query)
                || identity(rule.replacement()).contains(query)
                || identity(rule.appScope()).contains(query);
    }

    private static <T> List<T> page(List<T> values, int offset, int limit) {
        if (offset >= values.size()) return List.of();
        return List.copyOf(values.subList(offset, Math.min(values.size(), offset + limit)));
    }

    private String canonicalLookup(String canonical) {
        return lookupKey.digest("canonical", identity(canonical));
    }

    private String scopeLookup(String appScope) {
        return lookupKey.digest("scope", identity(appScope));
    }

    private String correctionLookup(String pattern, String replacement, String appScope) {
        return lookupKey.digest("correction", correctionIdentity(pattern, replacement, appScope));
    }

    private static String limit(String value, int maximum) {
        if (value.codePointCount(0, value.length()) <= maximum) return value;
        return value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    static String identity(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    static String termIdentity(String canonical, String appScope) {
        return termIdentityFromKeys(identity(canonical), identity(appScope));
    }

    private static String termIdentityFromKeys(String canonicalKey, String appScopeKey) {
        return canonicalKey + "\u001f" + appScopeKey;
    }

    static String correctionIdentity(String pattern, String replacement, String appScope) {
        return identity(pattern) + "\u001f" + identity(replacement) + "\u001f" + identity(appScope);
    }
}
