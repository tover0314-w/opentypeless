package com.opentypeless.android.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.opentypeless.android.security.SecurePreferences;

public final class SettingsRepository {
    private static final String STORE = "opentypeless_settings";
    private static final String STT_KEY = "stt_api_key";
    private static final String LLM_KEY = "llm_api_key";

    private final SharedPreferences preferences;
    private final SecurePreferences secrets;

    public SettingsRepository(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        secrets = new SecurePreferences(context);
    }

    public AppSettings load() {
        return new AppSettings(
                preferences.getString("stt_base_url", "https://api.openai.com/v1"),
                secrets.get(STT_KEY),
                preferences.getString("stt_model", "whisper-1"),
                preferences.getString("language", ""),
                preferences.getBoolean("polish_enabled", true),
                preferences.getString("llm_base_url", "https://api.openai.com/v1"),
                secrets.get(LLM_KEY),
                preferences.getString("llm_model", "gpt-4o-mini"));
    }

    public void save(AppSettings settings) {
        preferences.edit()
                .putString("stt_base_url", settings.sttBaseUrl().trim())
                .putString("stt_model", settings.sttModel().trim())
                .putString("language", settings.language().trim())
                .putBoolean("polish_enabled", settings.polishEnabled())
                .putString("llm_base_url", settings.llmBaseUrl().trim())
                .putString("llm_model", settings.llmModel().trim())
                .apply();
        secrets.put(STT_KEY, settings.sttApiKey().trim());
        secrets.put(LLM_KEY, settings.llmApiKey().trim());
    }
}
