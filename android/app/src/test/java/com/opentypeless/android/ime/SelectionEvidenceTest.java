package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SelectionEvidenceTest {
    @Test
    public void reportedCollapsedCursorProvesThereIsNoSelection() {
        OpenTypelessImeService.SelectionEvidence evidence = resolve(4, 4, null, -1, -1, false);

        assertTrue(evidence.known());
        assertFalse(evidence.hasSelection());
        assertTrue(evidence.selectedTextAvailable());
        assertEquals(4, evidence.start());
        assertEquals(4, evidence.end());
    }

    @Test
    public void anyNonEmptySelectionIncludingWhitespaceIsPreservedAsASelection() {
        OpenTypelessImeService.SelectionEvidence evidence = resolve(2, 4, " \n", -1, -1, false);

        assertTrue(evidence.known());
        assertTrue(evidence.hasSelection());
        assertTrue(evidence.selectedTextAvailable());
        assertEquals(" \n", evidence.text());
    }

    @Test
    public void knownSelectionWithoutSelectedTextFailsClosed() {
        OpenTypelessImeService.SelectionEvidence evidence = resolve(2, 7, null, -1, -1, false);

        assertTrue(evidence.known());
        assertTrue(evidence.hasSelection());
        assertFalse(evidence.selectedTextAvailable());
    }

    @Test
    public void extractedCursorStateCanSafelyRecoverMissingEditorCoordinates() {
        OpenTypelessImeService.SelectionEvidence evidence = resolve(-1, -1, null, 9, 9, true);

        assertTrue(evidence.known());
        assertFalse(evidence.hasSelection());
        assertEquals(9, evidence.start());
        assertEquals(9, evidence.end());
    }

    @Test
    public void selectedTextAloneProvesASelectionWhenCoordinatesAreUnavailable() {
        OpenTypelessImeService.SelectionEvidence evidence = resolve(
                -1, -1, "OpenTypeless", -1, -1, false);

        assertTrue(evidence.known());
        assertTrue(evidence.hasSelection());
        assertTrue(evidence.selectedTextAvailable());
    }

    @Test
    public void missingCoordinatesAndMissingOrEmptySelectedTextRemainUnknown() {
        OpenTypelessImeService.SelectionEvidence missing = resolve(
                -1, -1, null, -1, -1, false);
        OpenTypelessImeService.SelectionEvidence empty = resolve(
                -1, -1, "", -1, -1, false);

        assertFalse(missing.known());
        assertFalse(empty.known());
    }

    @Test
    public void knownCursorMoveCannotHideBehindAnIdenticalTextFingerprint() {
        assertTrue(OpenTypelessImeService.selectionCoordinatesStillMatch(8, 8, 8, 8));
        assertFalse(OpenTypelessImeService.selectionCoordinatesStillMatch(8, 8, 24, 24));
        assertTrue(OpenTypelessImeService.selectionCoordinatesStillMatch(-1, -1, 24, 24));
        assertTrue(OpenTypelessImeService.selectionCoordinatesStillMatch(8, 8, -1, -1));
    }

    private static OpenTypelessImeService.SelectionEvidence resolve(
            int reportedStart,
            int reportedEnd,
            CharSequence selected,
            int extractedStart,
            int extractedEnd,
            boolean extractedAvailable) {
        return OpenTypelessImeService.resolveSelectionEvidence(
                reportedStart,
                reportedEnd,
                selected,
                extractedStart,
                extractedEnd,
                extractedAvailable);
    }
}
