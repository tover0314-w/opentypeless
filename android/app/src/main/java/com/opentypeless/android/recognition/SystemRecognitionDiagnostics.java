package com.opentypeless.android.recognition;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.provider.Settings;

/** Read-only, best-effort facts about the speech service configured by Android. */
public final class SystemRecognitionDiagnostics {
    public record Snapshot(
            boolean systemAvailable,
            boolean onDeviceAvailable,
            String serviceLabel,
            String packageName,
            String versionName) {

        public Snapshot {
            serviceLabel = clean(serviceLabel);
            packageName = clean(packageName);
            versionName = clean(versionName);
        }

        public boolean serviceIdentified() {
            return !packageName.isEmpty();
        }
    }

    private SystemRecognitionDiagnostics() {}

    public static Snapshot inspect(Context context) {
        boolean system = safeSystemAvailable(context);
        boolean onDevice = safeOnDeviceAvailable(context);
        String label = "";
        String packageName = "";
        String versionName = "";
        try {
            String flattened = Settings.Secure.getString(
                    context.getContentResolver(),
                    "voice_recognition_service");
            ComponentName component = flattened == null
                    ? null
                    : ComponentName.unflattenFromString(flattened);
            if (component != null) {
                PackageManager manager = context.getPackageManager();
                ServiceInfo service = manager.getServiceInfo(component, 0);
                CharSequence loadedLabel = service.loadLabel(manager);
                label = loadedLabel == null ? "" : loadedLabel.toString();
                packageName = component.getPackageName();
                PackageInfo info = manager.getPackageInfo(packageName, 0);
                versionName = info.versionName == null ? "" : info.versionName;
            }
        } catch (RuntimeException | PackageManager.NameNotFoundException ignored) {
            // OEMs may hide the setting or replace the component during an update. Availability
            // remains useful even when Android cannot identify the implementation.
        }
        return new Snapshot(system, onDevice, label, packageName, versionName);
    }

    private static boolean safeSystemAvailable(Context context) {
        try {
            return SystemSpeechRecognizer.systemAvailable(context);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean safeOnDeviceAvailable(Context context) {
        try {
            return SystemSpeechRecognizer.onDeviceAvailable(context);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
