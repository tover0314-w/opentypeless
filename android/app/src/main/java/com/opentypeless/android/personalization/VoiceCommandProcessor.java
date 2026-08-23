package com.opentypeless.android.personalization;

import java.util.Locale;
import java.util.Map;

public final class VoiceCommandProcessor {
    private static final Map<String, String> EXACT_COMMANDS = Map.ofEntries(
            Map.entry("new line", "\n"),
            Map.entry("newline", "\n"),
            Map.entry("换行", "\n"),
            Map.entry("空格", " "),
            Map.entry("space", " "),
            Map.entry("comma", ","),
            Map.entry("逗号", "，"),
            Map.entry("period", "."),
            Map.entry("full stop", "."),
            Map.entry("句号", "。"),
            Map.entry("question mark", "?"),
            Map.entry("问号", "？"),
            Map.entry("exclamation mark", "!"),
            Map.entry("感叹号", "！"),
            Map.entry("colon", ":"),
            Map.entry("冒号", "："),
            Map.entry("semicolon", ";"),
            Map.entry("分号", "；"));

    private VoiceCommandProcessor() {}

    public static String exactReplacement(String transcript) {
        if (transcript == null) return null;
        String normalized = transcript.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[.。!！?？,，:：;；]+$", "")
                .trim();
        return EXACT_COMMANDS.get(normalized);
    }
}
