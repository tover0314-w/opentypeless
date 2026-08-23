package com.opentypeless.android.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.opentypeless.android.config.AppRule;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Stores a bounded set of non-secret, explicit per-app processing profiles. */
public final class AppProfileRepository {
    private static final String STORE = "opentypeless_app_profiles";
    private static final int MAX_PROFILES = LegacyAppProfileMigration.MAX_PROFILES;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");

    private final SharedPreferences preferences;

    public AppProfileRepository(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public synchronized AppProfile get(String packageName) {
        String key = cleanPackage(packageName, false);
        if (key.isEmpty()) return null;
        for (AppProfile profile : readAll()) {
            if (profile.packageName().equals(key)) return profile;
        }
        return null;
    }

    public synchronized List<AppProfile> list() {
        List<AppProfile> profiles = readAll();
        profiles.sort(Comparator.comparing(AppProfile::packageName));
        return List.copyOf(profiles);
    }

    /** Returns the validated CFG-007 compatibility shadow without making it runtime authority. */
    public List<AppRule> loadMigratedAppRules() {
        return LegacyAppProfileMigration.migrate(preferences);
    }

    public synchronized void save(AppProfile profile) {
        synchronized (LegacyAppProfileMigration.class) {
            String packageName = cleanPackage(profile.packageName(), true);
            String target = limit(profile.targetLanguage(), 80, "Target language");
            String instructions = limit(profile.customInstructions(), 1_000, "Writing preference");
            List<AppProfile> profiles = new java.util.ArrayList<>(
                    LegacyAppProfileMigration.readProfilesForUpdate(preferences));
            profiles.removeIf(existing -> existing.packageName().equals(packageName));
            if (profiles.size() >= MAX_PROFILES) {
                throw new IllegalArgumentException("At most 100 app profiles can be stored");
            }
            profiles.add(new AppProfile(
                    packageName,
                    profile.mode(),
                    target,
                    instructions,
                    profile.sendContext()));
            writeAll(profiles);
        }
    }

    public synchronized void delete(String packageName) {
        synchronized (LegacyAppProfileMigration.class) {
            String key = cleanPackage(packageName, false);
            List<AppProfile> profiles = new java.util.ArrayList<>(
                    LegacyAppProfileMigration.readProfilesForUpdate(preferences));
            if (profiles.removeIf(profile -> profile.packageName().equals(key))) {
                writeAll(profiles);
            }
        }
    }

    public AppSettings apply(AppSettings base, AppProfile profile) {
        return applyProfile(base, profile);
    }

    static AppSettings applyProfile(AppSettings base, AppProfile profile) {
        if (profile == null) return base;
        return new AppSettings(
                base.recognitionBackend(),
                base.sttBaseUrl(),
                base.sttApiKey(),
                base.sttModel(),
                base.streamingBaseUrl(),
                base.streamingApiKey(),
                base.streamingModel(),
                base.streamingVocabularyId(),
                base.language(),
                profile.mode(),
                base.polishEnabled(),
                base.llmBaseUrl(),
                base.llmApiKey(),
                base.llmModel(),
                profile.targetLanguage().isBlank()
                        ? base.targetLanguage()
                        : profile.targetLanguage(),
                profile.customInstructions().isBlank()
                        ? base.customInstructions()
                        : profile.customInstructions(),
                base.personalizationEnabled(),
                base.historyEnabled(),
                profile.sendContext(),
                base.maxRecordingSeconds());
    }

    private List<AppProfile> readAll() {
        return new java.util.ArrayList<>(
                LegacyAppProfileMigration.readLegacyProfiles(preferences));
    }

    private void writeAll(List<AppProfile> profiles) {
        LegacyAppProfileMigration.writeProfiles(preferences, profiles);
    }

    private static String cleanPackage(String value, boolean required) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() && !required) return "";
        if (clean.length() > 200 || !PACKAGE.matcher(clean).matches()) {
            if (required) throw new IllegalArgumentException("Enter a valid Android package name");
            return "";
        }
        return clean;
    }

    private static String limit(String value, int maximum, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.codePointCount(0, clean.length()) > maximum) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return clean;
    }

}
