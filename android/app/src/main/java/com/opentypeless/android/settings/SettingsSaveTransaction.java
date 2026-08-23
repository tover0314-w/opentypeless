package com.opentypeless.android.settings;

/**
 * Coordinates a recoverable settings write spanning ordinary preferences and encrypted secrets.
 *
 * <p>The journal must be durable before either value store is changed. A transaction is only
 * committed after both stores have been written and the durable journal has been cleared. If any
 * later step fails, the previous values are restored. A journal that survives process death can be
 * replayed with {@link #recover(boolean, Recovery)} before settings are read.</p>
 */
final class SettingsSaveTransaction {
    interface Recovery {
        void restoreFromJournal();

        void verifyRestored();

        void clearJournal();
    }

    interface Steps extends Recovery {
        void createJournal();

        void writeSecrets();

        void writeSettings();

        void verifyCommitted();

    }

    private SettingsSaveTransaction() {
    }

    static void execute(Steps steps) {
        if (steps == null) throw new IllegalArgumentException("Transaction steps are required");
        steps.createJournal();
        try {
            steps.writeSecrets();
            steps.writeSettings();
            steps.verifyCommitted();
            steps.clearJournal();
        } catch (RuntimeException failure) {
            rollback(steps, failure);
            throw failure;
        }
    }

    static void recover(boolean pending, Recovery steps) {
        if (!pending) return;
        if (steps == null) throw new IllegalArgumentException("Recovery steps are required");
        steps.restoreFromJournal();
        steps.verifyRestored();
        steps.clearJournal();
    }

    private static void rollback(Steps steps, RuntimeException failure) {
        try {
            steps.restoreFromJournal();
            steps.verifyRestored();
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
