package com.opentypeless.android.rime.importer;

import android.content.Context;
import android.content.SharedPreferences;

import com.opentypeless.android.keyboard.rime.RimeRuntimeConfig;

import java.util.List;
import java.util.Objects;

/** Private, content-free persistence for the selected local Schema and three closed options. */
public final class RimeRuntimePreferences {
    private static final String FILE_NAME = "opentypeless_rime_runtime_config_v1";
    private static final String KEY_SCHEMA = "schema_id";
    private static final String KEY_SIMPLIFIED = "simplified_output";
    private static final String KEY_ASCII_PUNCTUATION = "ascii_punctuation";
    private static final String KEY_FULL_SHAPE = "full_shape";

    private final SharedPreferences preferences;

    public RimeRuntimePreferences(Context context) {
        preferences = Objects.requireNonNull(context, "context")
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public RimeRuntimeConfig load(List<String> availableSchemas) {
        List<String> schemas = List.copyOf(Objects.requireNonNull(
                availableSchemas, "availableSchemas"));
        RimeRuntimeConfig config = RimeRuntimeConfig.resolved(
                schemas,
                preferences.getString(KEY_SCHEMA, null),
                preferences.getBoolean(KEY_SIMPLIFIED, true),
                preferences.getBoolean(KEY_ASCII_PUNCTUATION, true),
                preferences.getBoolean(KEY_FULL_SHAPE, false));
        if (!config.schemaId().equals(preferences.getString(KEY_SCHEMA, null))) {
            persist(config);
        }
        return config;
    }

    public void save(RimeRuntimeConfig config, List<String> availableSchemas) {
        Objects.requireNonNull(config, "config");
        List<String> schemas = List.copyOf(Objects.requireNonNull(
                availableSchemas, "availableSchemas"));
        if (!schemas.contains(config.schemaId())) {
            throw new IllegalArgumentException("selected Schema is not installed");
        }
        RimeRuntimeConfig resolved = RimeRuntimeConfig.resolved(
                schemas,
                config.schemaId(),
                config.simplifiedOutput(),
                config.asciiPunctuation(),
                config.fullShape());
        persist(resolved);
    }

    public void clear() {
        if (!preferences.edit().clear().commit()) {
            throw new IllegalStateException("unable to clear Rime runtime configuration");
        }
    }

    private void persist(RimeRuntimeConfig config) {
        boolean committed = preferences.edit()
                .putString(KEY_SCHEMA, config.schemaId())
                .putBoolean(KEY_SIMPLIFIED, config.simplifiedOutput())
                .putBoolean(KEY_ASCII_PUNCTUATION, config.asciiPunctuation())
                .putBoolean(KEY_FULL_SHAPE, config.fullShape())
                .commit();
        if (!committed) {
            throw new IllegalStateException("unable to persist Rime runtime configuration");
        }
    }
}
