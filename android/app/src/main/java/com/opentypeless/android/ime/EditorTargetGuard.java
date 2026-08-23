package com.opentypeless.android.ime;

/** Pure target identity check used before an asynchronous voice result may mutate an editor. */
public final class EditorTargetGuard {
    public record Snapshot(
            long epoch,
            Object connectionIdentity,
            String packageName,
            int fieldId,
            String selectedText,
            String beforeFingerprint,
            String afterFingerprint) {
        public Snapshot {
            packageName = safe(packageName);
            selectedText = safe(selectedText);
            beforeFingerprint = safe(beforeFingerprint);
            afterFingerprint = safe(afterFingerprint);
        }
    }

    private EditorTargetGuard() {}

    public static boolean matches(Snapshot captured, Snapshot current, boolean sensitive) {
        if (captured == null || current == null || sensitive) return false;
        return captured.epoch() == current.epoch()
                && captured.connectionIdentity() != null
                && captured.connectionIdentity() == current.connectionIdentity()
                && captured.fieldId() == current.fieldId()
                && captured.packageName().equals(current.packageName())
                && captured.selectedText().equals(current.selectedText())
                && captured.beforeFingerprint().equals(current.beforeFingerprint())
                && captured.afterFingerprint().equals(current.afterFingerprint());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
