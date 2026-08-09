package com.opentypeless.android.recognition;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class CallerPackageAllowlist {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private CallerPackageAllowlist() {}

    static Set<String> parse(String value) {
        Set<String> packages = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return packages;
        for (String token : value.split("[,\\s]+")) {
            String packageName = token.trim();
            if (packageName.isEmpty()) continue;
            if (!PACKAGE_NAME.matcher(packageName).matches()) {
                throw new IllegalArgumentException("Invalid caller package: " + packageName);
            }
            packages.add(packageName.toLowerCase(Locale.ROOT));
        }
        return packages;
    }
}
