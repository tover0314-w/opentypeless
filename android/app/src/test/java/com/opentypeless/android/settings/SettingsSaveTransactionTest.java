package com.opentypeless.android.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class SettingsSaveTransactionTest {
    @Test
    public void successCommitsBothStoresBeforeClearingJournal() {
        FakeSteps steps = new FakeSteps();

        SettingsSaveTransaction.execute(steps);

        assertEquals(List.of("journal", "secrets", "settings", "clear"), steps.events);
        assertFalse(steps.journalPending);
        assertEquals("new", steps.secrets);
        assertEquals("new", steps.settings);
    }

    @Test
    public void settingsFailureRestoresBothStoresAndClearsJournal() {
        FakeSteps steps = new FakeSteps();
        steps.failAt = "settings";

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> SettingsSaveTransaction.execute(steps));

        assertEquals("settings", failure.getMessage());
        assertEquals(
                List.of("journal", "secrets", "settings", "restore", "clear"),
                steps.events);
        assertEquals("old", steps.secrets);
        assertEquals("old", steps.settings);
        assertFalse(steps.journalPending);
    }

    @Test
    public void finalJournalFailureRollsBackAndRetriesJournalClear() {
        FakeSteps steps = new FakeSteps();
        steps.clearFailuresRemaining = 1;

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> SettingsSaveTransaction.execute(steps));

        assertEquals("clear", failure.getMessage());
        assertEquals(
                List.of("journal", "secrets", "settings", "clear", "restore", "clear"),
                steps.events);
        assertEquals("old", steps.secrets);
        assertEquals("old", steps.settings);
        assertFalse(steps.journalPending);
    }

    @Test
    public void rollbackFailureKeepsJournalForNextRepositoryInstance() {
        FakeSteps steps = new FakeSteps();
        steps.failAt = "settings";
        steps.restoreFails = true;

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> SettingsSaveTransaction.execute(steps));

        assertTrue(steps.journalPending);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of("journal", "secrets", "settings", "restore"), steps.events);
    }

    @Test
    public void pendingJournalIsRecoveredBeforeUse() {
        List<String> events = new ArrayList<>();

        SettingsSaveTransaction.recover(
                true,
                () -> events.add("restore"),
                () -> events.add("clear"));

        assertEquals(List.of("restore", "clear"), events);
    }

    @Test
    public void absentJournalDoesNotRunRecoveryCallbacks() {
        List<String> events = new ArrayList<>();

        SettingsSaveTransaction.recover(
                false,
                () -> events.add("restore"),
                () -> events.add("clear"));

        assertTrue(events.isEmpty());
    }

    private static final class FakeSteps implements SettingsSaveTransaction.Steps {
        private final List<String> events = new ArrayList<>();
        private String secrets = "old";
        private String settings = "old";
        private String failAt = "";
        private boolean journalPending;
        private boolean restoreFails;
        private int clearFailuresRemaining;

        @Override
        public void createJournal() {
            events.add("journal");
            if (failAt.equals("journal")) throw new IllegalStateException("journal");
            journalPending = true;
        }

        @Override
        public void writeSecrets() {
            events.add("secrets");
            secrets = "new";
            if (failAt.equals("secrets")) throw new IllegalStateException("secrets");
        }

        @Override
        public void writeSettings() {
            events.add("settings");
            settings = "new";
            if (failAt.equals("settings")) throw new IllegalStateException("settings");
        }

        @Override
        public void clearJournal() {
            events.add("clear");
            if (clearFailuresRemaining > 0) {
                clearFailuresRemaining--;
                throw new IllegalStateException("clear");
            }
            journalPending = false;
        }

        @Override
        public void restoreFromJournal() {
            events.add("restore");
            if (restoreFails) throw new IllegalStateException("restore");
            secrets = "old";
            settings = "old";
        }
    }
}
