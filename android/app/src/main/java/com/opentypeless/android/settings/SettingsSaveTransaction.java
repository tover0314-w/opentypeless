package com.opentypeless.android.settings;

/**
 * Coordinates a recoverable settings write spanning ordinary preferences and encrypted secrets.
 *
 * <p>The journal must be durable before either value store is changed. A transaction is only
 * committed after both stores have been written and the durable journal has been cleared. If any
 * later step fails, the previous values are restored. A journal that survives process death can be
 * replayed with {@link #recover(boolean, Runnable, Runnable)} before settings are read.</p>
 */
public final class SettingsSaveTransaction {
    public interface Steps {
        void createJournal();

        void writeSecrets();

        void writeSettings();

        void clearJournal();

        void restoreFromJournal();
    }

    private SettingsSaveTransaction() {
    }

    public static void execute(Steps steps) {
        if (steps == null) throw new IllegalArgumentException("Transaction steps are required");
        steps.createJournal();
        try {
            steps.writeSecrets();
            steps.writeSettings();
            steps.clearJournal();
        } catch (RuntimeException failure) {
            rollback(steps, failure);
            throw failure;
        }
    }

    public static void recover(boolean pending, Runnable restore, Runnable clearJournal) {
        if (!pending) return;
        if (restore == null || clearJournal == null) {
            throw new IllegalArgumentException("Recovery steps are required");
        }
        restore.run();
        clearJournal.run();
    }

    private static void rollback(Steps steps, RuntimeException failure) {
        try {
            steps.restoreFromJournal();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            // Keep the journal so a later repository instance can finish recovery.
            return;
        }
        try {
            steps.clearJournal();
        } catch (RuntimeException clearFailure) {
            failure.addSuppressed(clearFailure);
            // Restoring is idempotent. A surviving journal will restore the same old values again.
        }
    }
}
