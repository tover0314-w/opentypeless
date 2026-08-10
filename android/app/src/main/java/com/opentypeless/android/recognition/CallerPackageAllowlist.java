package com.opentypeless.android.recognition;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class CallerPackageAllowlist {
    private static final int MAX_PACKAGES = 100;
    private static final int MAX_TEXT_CODE_POINTS = 20_000;
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private CallerPackageAllowlist() {}

    static Set<String> parse(String value) {
        Set<String> packages = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return packages;
        if (value.codePointCount(0, value.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("Caller package list is too long");
        }
        for (String token : value.split("[,\\s]+")) {
            String packageName = token.trim();
            if (packageName.isEmpty()) continue;
            if (!PACKAGE_NAME.matcher(packageName).matches()) {
                throw new IllegalArgumentException("Invalid caller package: " + packageName);
            }
            String normalized = packageName.toLowerCase(Locale.ROOT);
            if (!packages.contains(normalized) && packages.size() >= MAX_PACKAGES) {
                throw new IllegalArgumentException(
                        "Caller package list supports at most " + MAX_PACKAGES + " apps");
            }
            packages.add(normalized);
        }
        return packages;
    }
}
