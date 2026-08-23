package com.opentypeless.android;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.os.Process;

import com.opentypeless.android.config.AppPickerModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Current-user launchable-app adapter; deliberately does not request broad package visibility. */
final class InstalledAppCatalog {
    private static final int MAX_LAUNCHER_ACTIVITIES = 4_096;

    private InstalledAppCatalog() {}

    static Snapshot load(Context context) {
        try {
            Context safeContext = Objects.requireNonNull(context, "context");
            LauncherApps launcherApps = safeContext.getSystemService(LauncherApps.class);
            if (launcherApps == null) throw new CatalogUnavailableException();
            List<LauncherActivityInfo> activities = launcherApps.getActivityList(
                    null, Process.myUserHandle());
            if (activities == null || activities.size() > MAX_LAUNCHER_ACTIVITIES) {
                throw new CatalogUnavailableException();
            }

            Map<String, AppPickerModel.Entry> entries = new LinkedHashMap<>();
            Map<String, LauncherActivityInfo> icons = new LinkedHashMap<>();
            for (LauncherActivityInfo activity : activities) {
                if (activity == null || activity.getComponentName() == null) continue;
                String packageName = activity.getComponentName().getPackageName();
                try {
                    String label = boundedLabel(activity.getLabel(), packageName);
                    AppPickerModel.Entry entry = new AppPickerModel.Entry(label, packageName);
                    entries.putIfAbsent(entry.packageName(), entry);
                    icons.putIfAbsent(entry.packageName(), activity);
                    if (entries.size() > AppPickerModel.MAX_ENTRIES) {
                        throw new CatalogUnavailableException();
                    }
                } catch (RuntimeException ignored) {
                    // A hostile or malformed third-party manifest cannot break the entire picker.
                }
            }
            return new Snapshot(new AppPickerModel(new ArrayList<>(entries.values())), icons);
        } catch (CatalogUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new CatalogUnavailableException();
        }
    }

    private static String boundedLabel(CharSequence value, String fallback) {
        String materialized;
        try {
            if (value == null
                    || value.length() > AppPickerModel.MAX_LABEL_CODE_POINTS * 2) {
                return fallback;
            }
            materialized = value.toString().strip();
            if (materialized.length() > AppPickerModel.MAX_LABEL_CODE_POINTS * 2) {
                return fallback;
            }
        } catch (RuntimeException error) {
            materialized = "";
        }
        if (materialized.isEmpty()) return fallback;
        int codePoints = materialized.codePointCount(0, materialized.length());
        if (codePoints <= AppPickerModel.MAX_LABEL_CODE_POINTS) return materialized;
        int end = materialized.offsetByCodePoints(
                0, AppPickerModel.MAX_LABEL_CODE_POINTS - 1);
        return materialized.substring(0, end) + "…";
    }

    static final class Snapshot {
        private final AppPickerModel model;
        private final Map<String, LauncherActivityInfo> icons;

        Snapshot(AppPickerModel model, Map<String, LauncherActivityInfo> icons) {
            this.model = Objects.requireNonNull(model, "model");
            this.icons = Map.copyOf(Objects.requireNonNull(icons, "icons"));
        }

        AppPickerModel model() {
            return model;
        }

        Drawable iconFor(Context context, AppPickerModel.Entry entry) {
            LauncherActivityInfo activity = icons.get(entry.packageName());
            if (activity != null) {
                try {
                    Drawable icon = activity.getBadgedIcon(0);
                    if (icon != null) return icon;
                } catch (RuntimeException ignored) {
                    // Use the local generic application icon below.
                }
            }
            return Objects.requireNonNull(context, "context")
                    .getDrawable(android.R.drawable.sym_def_app_icon);
        }

        @Override
        public String toString() {
            return "InstalledAppCatalog.Snapshot{apps=<redacted>, count="
                    + model.entries().size() + "}";
        }
    }

    static final class CatalogUnavailableException extends RuntimeException {
        CatalogUnavailableException() {
            super("Installed app catalog is unavailable");
        }
    }
}
