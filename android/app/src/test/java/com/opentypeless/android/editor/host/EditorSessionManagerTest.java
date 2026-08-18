package com.opentypeless.android.editor.host;

import static com.opentypeless.android.editor.host.EditorSessionManager.CaptureFailure.CONNECTION_CHANGED;
import static com.opentypeless.android.editor.host.EditorSessionManager.CaptureFailure.EVIDENCE_LIMIT_EXCEEDED;
import static com.opentypeless.android.editor.host.EditorSessionManager.CaptureFailure.NO_ACTIVE_SESSION;
import static com.opentypeless.android.editor.host.EditorSessionManager.CaptureFailure.SELECTED_TEXT_UNAVAILABLE;
import static com.opentypeless.android.editor.host.EditorSessionManager.CaptureFailure.SELECTION_MISMATCH;
import static com.opentypeless.android.editor.host.EditorSessionManager.CaptureFailure.SENSITIVE_EVIDENCE_PRESENT;
import static com.opentypeless.android.editor.host.EditorSessionManager.CaptureFailure.SURROUNDING_TEXT_UNAVAILABLE;
import static com.opentypeless.android.editor.host.EditorSessionManager.CaptureFailure.UNREPRESENTABLE_SELECTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.editor.EditorSessionLimits;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.EditorTransactionResult;
import com.opentypeless.android.editor.TargetChangeReason;
import com.opentypeless.android.editor.TextRange;
import com.opentypeless.android.editor.TransactionReceipt;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;

public final class EditorSessionManagerTest {
    @Test
    public void startAndFinishAdvanceExactEpochAndRevokeSession() {
        EditorSessionManager manager = managerAt(7);
        InputConnection first = fakeConnection("first");

        assertEquals(1, manager.start(descriptor("app.one", 10, 2, 2), first));
        EditorSessionSnapshot firstSnapshot = captured(
                manager.captureFromEvidence(first, "", "before", "after"));
        assertEquals(1, firstSnapshot.epoch());

        assertEquals(2, manager.onFinishInput());
        assertRejected(NO_ACTIVE_SESSION, manager.captureFromEvidence(first, "", "", ""));

        InputConnection second = fakeConnection("second");
        assertEquals(3, manager.start(descriptor("app.two", 11, 4, 4), second));
        EditorSessionSnapshot secondSnapshot = captured(
                manager.captureFromEvidence(second, "", "", ""));
        assertEquals(3, secondSnapshot.epoch());
        assertNotEquals(firstSnapshot.connectionToken(), secondSnapshot.connectionToken());
        assertEquals("app.two", secondSnapshot.packageName());
        assertEquals(11, secondSnapshot.fieldId());
    }

    @Test
    public void everyStartRotatesTokenEvenForSameConnectionAndField() {
        EditorSessionManager manager = managerAt(9);
        InputConnection connection = fakeConnection("reused");
        EditorSessionManager.EditorDescriptor descriptor = descriptor("app", 1, 0, 0);

        manager.start(descriptor, connection);
        EditorSessionSnapshot first = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        manager.start(descriptor, connection);
        EditorSessionSnapshot second = captured(
                manager.captureFromEvidence(connection, "", "", ""));

        assertEquals(2, second.epoch());
        assertNotEquals(first.connectionToken(), second.connectionToken());
    }

    @Test
    public void nullDescriptorOrConnectionCreatesNoActiveSessionButStillRotatesEpoch() {
        EditorSessionManager manager = managerAt(0);
        InputConnection connection = fakeConnection("value");

        assertEquals(1, manager.start(null, connection));
        assertRejected(NO_ACTIVE_SESSION, manager.captureFromEvidence(connection, "", "", ""));
        assertEquals(2, manager.start(descriptor("app", 1, 0, 0), null));
        assertRejected(NO_ACTIVE_SESSION, manager.captureFromEvidence(connection, "", "", ""));
    }

    @Test
    public void invalidMetadataRevokesTheOldCapabilityBeforeFailingClosed() {
        EditorSessionManager manager = managerAt(0);
        InputConnection oldConnection = fakeConnection("old");
        manager.start(descriptor("valid.app", 1, 0, 0), oldConnection);
        captured(manager.captureFromEvidence(oldConnection, "", "", ""));

        InputConnection replacement = fakeConnection("replacement");
        assertEquals(2, manager.start(descriptor(" ", 2, 0, 0), replacement));
        assertRejected(NO_ACTIVE_SESSION,
                manager.captureFromEvidence(oldConnection, "", "", ""));
        assertRejected(NO_ACTIVE_SESSION,
                manager.captureFromEvidence(replacement, "", "", ""));
    }

    @Test
    public void publicAndroidStartRevokesOldSessionBeforeInvalidPackageIsParsed() {
        EditorSessionManager manager = managerAt(0);
        InputConnection oldConnection = fakeConnection("old-public");
        manager.start(descriptor("valid.app", 1, 0, 0), oldConnection);
        captured(manager.captureFromEvidence(oldConnection, "", "", ""));

        EditorInfo invalid = new EditorInfo();
        invalid.packageName = " ";
        invalid.fieldId = 2;
        invalid.initialSelStart = 0;
        invalid.initialSelEnd = 0;
        assertEquals(2, manager.onStartInput(invalid, fakeConnection("replacement-public")));
        assertRejected(NO_ACTIVE_SESSION,
                manager.captureFromEvidence(oldConnection, "", "", ""));
    }

    @Test
    public void snapshotCopiesMetadataSelectionAndCaptureTime() {
        EditorSessionManager manager = managerAt(1234);
        InputConnection connection = fakeConnection("metadata");
        manager.start(new EditorSessionManager.EditorDescriptor(
                "com.example", 42, FieldKind.EMAIL_ADDRESS, 17, 23, 1, 3), connection);

        EditorSessionSnapshot snapshot = captured(
                manager.captureFromEvidence(connection, "ab", "left", "right"));

        assertEquals(1, snapshot.epoch());
        assertEquals("com.example", snapshot.packageName());
        assertEquals(42, snapshot.fieldId());
        assertEquals(FieldKind.EMAIL_ADDRESS, snapshot.fieldKind());
        assertEquals(17, snapshot.inputType());
        assertEquals(23, snapshot.imeOptions());
        assertEquals(new TextRange(1, 3), snapshot.selection());
        assertEquals("ab", snapshot.selectedText());
        assertEquals(1234, snapshot.capturedAtElapsedRealtimeMs());
    }

    @Test
    public void selectionUpdatesAreCapturedAndInvalidCoordinatesBecomeUnknown() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("selection");
        manager.start(descriptor("app", 1, 0, 0), connection);

        manager.onSelectionChanged(4, 7);
        assertEquals(new TextRange(4, 7), captured(
                manager.captureFromEvidence(connection, "xyz", "", "")).selection());

        manager.onSelectionChanged(-1, 7);
        assertEquals(TextRange.UNKNOWN, captured(
                manager.captureFromEvidence(connection, "", "", "")).selection());
        assertRejected(UNREPRESENTABLE_SELECTION,
                manager.captureFromEvidence(connection, "x", "", ""));
    }

    @Test
    public void selectionFailuresAreExplicitAndDoNotExposeEvidence() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("selection-errors");
        manager.start(descriptor("app", 1, 2, 5), connection);

        assertRejected(SELECTED_TEXT_UNAVAILABLE,
                manager.captureFromEvidence(connection, null, "", ""));
        assertRejected(SELECTED_TEXT_UNAVAILABLE,
                manager.captureFromEvidence(connection, "", "", ""));
        EditorSessionManager.CaptureResult mismatch =
                manager.captureFromEvidence(connection, "xy", "", "");
        assertRejected(SELECTION_MISMATCH, mismatch);
        assertFalse(mismatch.toString().contains("xy"));

        manager.onSelectionChanged(3, 3);
        assertRejected(SELECTION_MISMATCH,
                manager.captureFromEvidence(connection, "unexpected", "", ""));
    }

    @Test
    public void connectionIdentityMustStillMatchCurrentRegistration() {
        EditorSessionManager manager = managerAt(1);
        InputConnection current = fakeConnection("current");
        InputConnection stale = fakeConnection("stale");
        manager.start(descriptor("app", 1, 0, 0), current);

        assertRejected(CONNECTION_CHANGED,
                manager.captureFromEvidence(stale, "", "secret-before", "secret-after"));
        assertRejected(CONNECTION_CHANGED,
                manager.captureFromEvidence(null, "", "", ""));
        captured(manager.captureFromEvidence(current, "", "", ""));
    }

    @Test
    public void unavailableSurroundingEvidenceIsDifferentFromAValidEmptyBoundary() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("unavailable");
        manager.start(descriptor("app", 1, 0, 0), connection);

        captured(manager.captureFromEvidence(connection, null, "", ""));
        assertRejected(SURROUNDING_TEXT_UNAVAILABLE,
                manager.captureFromEvidence(connection, "", null, ""));
        assertRejected(SURROUNDING_TEXT_UNAVAILABLE,
                manager.captureFromEvidence(connection, "", "", null));
    }

    @Test
    public void sensitiveSessionAcceptsOnlyRedactedEvidenceAndAlwaysDisablesLearning() {
        EditorSessionManager manager = managerAt(5);
        InputConnection connection = fakeConnection("sensitive");
        manager.start(new EditorSessionManager.EditorDescriptor(
                "app", 1, FieldKind.SENSITIVE,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0, 1, 4), connection);

        EditorSessionSnapshot redacted = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        assertTrue(redacted.sensitive());
        assertFalse(redacted.learningAllowed());
        assertEquals("", redacted.selectedText());

        for (CharSequence[] evidence : new CharSequence[][]{
                {"pin", "", ""},
                {"", "before-secret", ""},
                {"", "", "after-secret"}}) {
            EditorSessionManager.CaptureResult result = manager.captureFromEvidence(
                    connection, evidence[0], evidence[1], evidence[2]);
            assertRejected(SENSITIVE_EVIDENCE_PRESENT, result);
            assertFalse(result.toString().contains("secret"));
            assertFalse(result.toString().contains("pin"));
        }

        CharSequence sensitiveSentinel = new FixedLengthCharSequence(1, true);
        assertRejected(SENSITIVE_EVIDENCE_PRESENT,
                manager.captureFromEvidence(connection, sensitiveSentinel, "", ""));
        captured(manager.captureFromEvidence(
                connection, new FixedLengthCharSequence(0, true), "", ""));
    }

    @Test
    public void noPersonalizedLearningFlagIsCopiedWithoutChangingOtherEvidence() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("no-learning");
        manager.start(new EditorSessionManager.EditorDescriptor(
                "app", 1, FieldKind.GENERAL, 1,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, 0, 0), connection);

        EditorSessionSnapshot snapshot = captured(
                manager.captureFromEvidence(connection, "", "before", "after"));
        assertFalse(snapshot.learningAllowed());
        assertFalse(snapshot.sensitive());
    }

    @Test
    public void fingerprintsChangeWithSelectedBeforeAndAfterEvidence() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("fingerprints");
        manager.start(descriptor("app", 1, 0, 2), connection);
        EditorSessionSnapshot baseline = captured(
                manager.captureFromEvidence(connection, "ab", "before", "after"));
        EditorSessionSnapshot selectedChanged = captured(
                manager.captureFromEvidence(connection, "cd", "before", "after"));
        EditorSessionSnapshot beforeChanged = captured(
                manager.captureFromEvidence(connection, "ab", "changed", "after"));
        EditorSessionSnapshot afterChanged = captured(
                manager.captureFromEvidence(connection, "ab", "before", "changed"));

        assertNotEquals(baseline.selectedTextFingerprint(), selectedChanged.selectedTextFingerprint());
        assertNotEquals(baseline.beforeFingerprint(), beforeChanged.beforeFingerprint());
        assertNotEquals(baseline.afterFingerprint(), afterChanged.afterFingerprint());
    }

    @Test
    public void hostValidationUsesFreshAuthorityTwiceAndBindsEvidenceToExactConnection() {
        EditorSessionManager manager = managerAt(12);
        InputConnection connection = fakeConnection("validated");
        EditorInfo info = editorInfo("app", 1, 2, 4, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "ab", "left", "right"));
        AtomicInteger authorityReads = new AtomicInteger();
        AtomicInteger evidenceReads = new AtomicInteger();

        EditorSessionManager.HostValidationResult result = manager.validateCurrentSession(
                expected,
                () -> {
                    authorityReads.incrementAndGet();
                    return new EditorSessionManager.LiveAuthority(info, connection);
                },
                (authorized, request) -> {
                    assertTrue(authorized == connection);
                    evidenceReads.incrementAndGet();
                    return evidence(2, 4, true, "ab", "left", "right");
                });

        EditorSessionManager.Validated validated = validated(result);
        assertEquals(2, authorityReads.get());
        assertEquals(1, evidenceReads.get());
        assertFalse(validated.lease().sensitive());
        assertEquals("ab", validated.evidence().selected());
        assertEquals("left", validated.evidence().before());
        assertEquals("right", validated.evidence().after());
        assertEquals("ValidatedEvidence{<redacted>}", validated.evidence().toString());
        assertFalse(validated.toString().contains("left"));
        assertFalse(validated.toString().contains("right"));
        assertTrue(validated.lease().authorityStillCurrent(
                () -> new EditorSessionManager.LiveAuthority(info, connection)));
        assertFalse(validated.lease().authorityStillCurrent(
                () -> new EditorSessionManager.LiveAuthority(info, connection)));
    }

    @Test
    public void allPreflightFailuresAvoidEvidenceAndUseStablePrecedence() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("preflight");
        AtomicInteger evidenceReads = new AtomicInteger();
        EditorSessionManager.CurrentEvidenceReader reader = (authorized, request) -> {
            evidenceReads.incrementAndGet();
            return evidence(true, "", "", "");
        };
        EditorSessionSnapshot expected = EditorSessionSnapshot.capture(
                1, 1, "app", 1, FieldKind.GENERAL, InputType.TYPE_CLASS_TEXT, 0,
                new TextRange(0, 0), "", "", "", true, false, 1);

        assertInvalid(TargetChangeReason.NO_ACTIVE_SESSION,
                manager.validateCurrentSession(expected,
                        () -> new EditorSessionManager.LiveAuthority(
                                editorInfo("app", 1, 0, 0, false, false), connection), reader));
        assertEquals(0, evidenceReads.get());

        EditorInfo current = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(current, connection);
        EditorSessionSnapshot currentSnapshot = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        manager.onStartInput(current, connection);
        assertInvalid(TargetChangeReason.EPOCH_CHANGED,
                manager.validateCurrentSession(currentSnapshot,
                        () -> new EditorSessionManager.LiveAuthority(current, connection), reader));
        assertEquals(0, evidenceReads.get());
    }

    @Test
    public void securityAndUnknownSelectionFailBeforeAnyEvidenceRead() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("security");
        EditorInfo normal = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(normal, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        AtomicInteger reads = new AtomicInteger();

        EditorInfo password = editorInfo("app", 1, 0, 0, true, false);
        assertInvalid(TargetChangeReason.SECURITY_STATE_CHANGED,
                manager.validateCurrentSession(expected,
                        () -> new EditorSessionManager.LiveAuthority(password, connection),
                        (authorized, request) -> {
                            reads.incrementAndGet();
                            return evidence(true, "", "", "");
                        }));
        assertEquals(0, reads.get());

        manager.onSelectionChanged(-1, -1);
        assertInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE,
                manager.validateCurrentSession(expected,
                        () -> new EditorSessionManager.LiveAuthority(normal, connection),
                        (authorized, request) -> {
                            reads.incrementAndGet();
                            return evidence(true, "", "", "");
                        }));
        assertEquals(0, reads.get());
    }

    @Test
    public void matchingSensitiveSessionUsesNoTextEvidenceButProducesRestrictedLease() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("password");
        EditorInfo password = editorInfo("app", 1, 0, 0, true, false);
        manager.onStartInput(password, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        AtomicInteger authorityReads = new AtomicInteger();
        AtomicInteger evidenceReads = new AtomicInteger();

        EditorSessionManager.Validated result = validated(manager.validateCurrentSession(
                expected,
                () -> {
                    authorityReads.incrementAndGet();
                    return new EditorSessionManager.LiveAuthority(password, connection);
                },
                (authorized, request) -> {
                    evidenceReads.incrementAndGet();
                    throw new AssertionError("sensitive validation must not read text");
                }));
        assertEquals(2, authorityReads.get());
        assertEquals(0, evidenceReads.get());
        assertTrue(result.lease().sensitive());
        assertEquals("", result.evidence().selected());
        assertEquals("", result.evidence().before());
        assertEquals("", result.evidence().after());
        assertFalse(result.toString().contains("app"));
    }

    @Test
    public void scopedConnectionUseReceivesExactConnectionOnceAndRedactsResult() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("scoped-exact");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "left-private", "right-private"));
        EditorSessionManager.Validated validated = validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(true, "", "left-private", "right-private")));
        AtomicInteger calls = new AtomicInteger();

        EditorSessionManager.ConnectionUseResult first =
                validated.lease().consumeWithCurrentConnection(authorized -> {
                    assertTrue(authorized == connection);
                    calls.incrementAndGet();
                    return new EditorTransactionResult.Applied();
                });
        assertTrue(first instanceof EditorSessionManager.ConnectionUsed);
        assertEquals(1, calls.get());
        assertFalse(first.toString().contains("private"));

        EditorSessionManager.ConnectionUseResult second =
                validated.lease().consumeWithCurrentConnection(authorized -> {
                    calls.incrementAndGet();
                    return new EditorTransactionResult.Applied();
                });
        assertTrue(second instanceof EditorSessionManager.ConnectionInvalid);
        assertEquals(1, calls.get());
    }

    @Test
    public void scopedReceiptConnectionUsePreservesExactReceiptAndIsOneShot() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("receipt-exact");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "private-left", "private-right"));
        EditorSessionManager.Validated validated = validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(true, "", "private-left", "private-right")));
        AtomicInteger calls = new AtomicInteger();
        TransactionReceipt expectedReceipt = new TransactionReceipt.WithoutCommit(
                new EditorTransactionResult.Applied());

        EditorSessionManager.ReceiptConnectionUseResult first =
                validated.lease().consumeWithCurrentConnectionForReceipt(authorized -> {
                    assertTrue(authorized == connection);
                    calls.incrementAndGet();
                    return expectedReceipt;
                });

        assertTrue(first instanceof EditorSessionManager.ReceiptConnectionUsed);
        assertTrue(((EditorSessionManager.ReceiptConnectionUsed) first).receipt()
                == expectedReceipt);
        assertEquals(1, calls.get());
        assertFalse(first.toString().contains("private"));

        EditorSessionManager.ReceiptConnectionUseResult second =
                validated.lease().consumeWithCurrentConnectionForReceipt(authorized -> {
                    calls.incrementAndGet();
                    return expectedReceipt;
                });
        assertTrue(second instanceof EditorSessionManager.ReceiptConnectionInvalid);
        assertEquals(1, calls.get());
    }

    @Test
    public void scopedReceiptUseOffOwnerDoesNotConsumeButCallbackFailureDoes() throws Exception {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("receipt-owner");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        EditorSessionManager.Validated validated = validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(true, "", "", "")));
        AtomicInteger calls = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertOffOwner(executor.submit(() ->
                    validated.lease().consumeWithCurrentConnectionForReceipt(authorized -> {
                        calls.incrementAndGet();
                        return new TransactionReceipt.WithoutCommit(
                                new EditorTransactionResult.Applied());
                    })));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, calls.get());

        try {
            validated.lease().consumeWithCurrentConnectionForReceipt(authorized -> {
                calls.incrementAndGet();
                throw new IllegalStateException("PRIVATE_RECEIPT_CALLBACK");
            });
            fail("expected callback failure");
        } catch (IllegalStateException expectedFailure) {
            assertEquals("PRIVATE_RECEIPT_CALLBACK", expectedFailure.getMessage());
        }
        EditorSessionManager.ReceiptConnectionUseResult retry =
                validated.lease().consumeWithCurrentConnectionForReceipt(authorized -> {
                    calls.incrementAndGet();
                    return new TransactionReceipt.WithoutCommit(
                            new EditorTransactionResult.Applied());
                });
        assertTrue(retry instanceof EditorSessionManager.ReceiptConnectionInvalid);
        assertEquals(1, calls.get());
    }

    @Test
    public void scopedConnectionUseClassifiesStaleEpochTokenAndSelection() throws Exception {
        InputConnection epochConnection = fakeConnection("scoped-epoch");
        EditorInfo epochInfo = editorInfo("app", 1, 0, 0, false, false);
        EditorSessionManager epochManager = managerAt(1);
        epochManager.onStartInput(epochInfo, epochConnection);
        EditorSessionSnapshot epochSnapshot = captured(
                epochManager.captureFromEvidence(epochConnection, "", "", ""));
        EditorSessionManager.Validated epochValidated = validated(
                epochManager.validateCurrentSession(
                        epochSnapshot,
                        () -> new EditorSessionManager.LiveAuthority(epochInfo, epochConnection),
                        (authorized, request) -> evidence(true, "", "", "")));
        epochManager.onStartInput(epochInfo, epochConnection);
        assertConnectionInvalid(
                TargetChangeReason.EPOCH_CHANGED,
                epochValidated.lease().consumeWithCurrentConnection(
                        authorized -> new EditorTransactionResult.Applied()));

        InputConnection tokenConnection = fakeConnection("scoped-token");
        EditorInfo tokenInfo = editorInfo("app", 1, 0, 0, false, false);
        EditorSessionManager tokenManager = managerAt(1);
        tokenManager.onStartInput(tokenInfo, tokenConnection);
        EditorSessionSnapshot tokenSnapshot = captured(
                tokenManager.captureFromEvidence(tokenConnection, "", "", ""));
        EditorSessionManager.Validated tokenValidated = validated(
                tokenManager.validateCurrentSession(
                        tokenSnapshot,
                        () -> new EditorSessionManager.LiveAuthority(tokenInfo, tokenConnection),
                        (authorized, request) -> evidence(true, "", "", "")));
        registryOf(tokenManager).register(fakeConnection("scoped-token-replacement"));
        assertConnectionInvalid(
                TargetChangeReason.CONNECTION_CHANGED,
                tokenValidated.lease().consumeWithCurrentConnection(
                        authorized -> new EditorTransactionResult.Applied()));

        InputConnection selectionConnection = fakeConnection("scoped-selection");
        EditorInfo selectionInfo = editorInfo("app", 1, 0, 0, false, false);
        EditorSessionManager selectionManager = managerAt(1);
        selectionManager.onStartInput(selectionInfo, selectionConnection);
        EditorSessionSnapshot selectionSnapshot = captured(
                selectionManager.captureFromEvidence(selectionConnection, "", "", ""));
        EditorSessionManager.Validated selectionValidated = validated(
                selectionManager.validateCurrentSession(
                        selectionSnapshot,
                        () -> new EditorSessionManager.LiveAuthority(
                                selectionInfo, selectionConnection),
                        (authorized, request) -> evidence(true, "", "", "")));
        selectionManager.onSelectionChanged(1, 1);
        assertConnectionInvalid(
                TargetChangeReason.SELECTION_CHANGED,
                selectionValidated.lease().consumeWithCurrentConnection(
                        authorized -> new EditorTransactionResult.Applied()));
    }

    @Test
    public void scopedConnectionUseOffOwnerDoesNotConsumeLease() throws Exception {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("scoped-owner");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        EditorSessionManager.Validated validated = validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(true, "", "", "")));
        AtomicInteger calls = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertOffOwner(executor.submit(() ->
                    validated.lease().consumeWithCurrentConnection(authorized -> {
                        calls.incrementAndGet();
                        return new EditorTransactionResult.Applied();
                    })));
        } finally {
            executor.shutdownNow();
        }

        EditorSessionManager.ConnectionUseResult ownerResult =
                validated.lease().consumeWithCurrentConnection(authorized -> {
                    assertTrue(authorized == connection);
                    calls.incrementAndGet();
                    return new EditorTransactionResult.Applied();
                });
        assertTrue(ownerResult instanceof EditorSessionManager.ConnectionUsed);
        assertEquals(1, calls.get());
    }

    @Test
    public void scopedConnectionCallbackFailureConsumesLeaseAndCannotRetry() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("scoped-throw");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        EditorSessionManager.Validated validated = validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(true, "", "", "")));
        AtomicInteger calls = new AtomicInteger();

        try {
            validated.lease().consumeWithCurrentConnection(authorized -> {
                calls.incrementAndGet();
                throw new IllegalStateException("PRIVATE_CALLBACK_SENTINEL");
            });
            fail("expected callback failure");
        } catch (IllegalStateException expectedFailure) {
            assertEquals("PRIVATE_CALLBACK_SENTINEL", expectedFailure.getMessage());
        }
        EditorSessionManager.ConnectionUseResult retry =
                validated.lease().consumeWithCurrentConnection(authorized -> {
                    calls.incrementAndGet();
                    return new EditorTransactionResult.Applied();
                });
        assertTrue(retry instanceof EditorSessionManager.ConnectionInvalid);
        assertEquals(1, calls.get());
    }

    @Test
    public void evidenceReentrantSelectionAwayAndBackIsRejected() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("selection-aba");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "left", "right"));

        assertInvalid(TargetChangeReason.SELECTION_CHANGED,
                manager.validateCurrentSession(
                        expected,
                        () -> new EditorSessionManager.LiveAuthority(info, connection),
                        (authorized, request) -> {
                            manager.onSelectionChanged(2, 2);
                            manager.onSelectionChanged(0, 0);
                            return evidence(true, "", "left", "right");
                        }));
    }

    @Test
    public void repeatedSameSelectionCallbackDoesNotInvalidateEvidence() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("selection-repeat");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "left", "right"));

        validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> {
                    manager.onSelectionChanged(0, 0);
                    return evidence(true, "", "left", "right");
                }));
    }

    @Test
    public void reentrantUnavailableEvidenceReportsLifecycleReason() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("unavailable-reentrant");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));

        assertInvalid(TargetChangeReason.SELECTION_CHANGED,
                manager.validateCurrentSession(
                        expected,
                        () -> new EditorSessionManager.LiveAuthority(info, connection),
                        (authorized, request) -> {
                            manager.onSelectionChanged(1, 1);
                            return new EditorSessionManager.EvidenceUnavailable();
                        }));
    }

    @Test
    public void evidenceReentrantSameConnectionRestartIsRejected() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("restart");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "left", "right"));

        assertInvalid(TargetChangeReason.EPOCH_CHANGED,
                manager.validateCurrentSession(
                        expected,
                        () -> new EditorSessionManager.LiveAuthority(info, connection),
                        (authorized, request) -> {
                            manager.onStartInput(info, connection);
                            return evidence(true, "", "left", "right");
                        }));
    }

    @Test
    public void leaseFreshAuthorityRejectsPasswordSwitchAndSupplierReentrancy() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("lease-race");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        EditorSessionManager.Validated validated = validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(true, "", "", "")));

        EditorInfo password = editorInfo("app", 1, 0, 0, true, false);
        assertFalse(validated.lease().authorityStillCurrent(
                () -> new EditorSessionManager.LiveAuthority(password, connection)));

        manager.onStartInput(info, connection);
        EditorSessionSnapshot second = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        EditorSessionManager.Validated reentrant = validated(manager.validateCurrentSession(
                second,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(true, "", "", "")));
        assertFalse(reentrant.lease().authorityStillCurrent(() -> {
            manager.onStartInput(info, connection);
            return new EditorSessionManager.LiveAuthority(info, connection);
        }));
    }

    @Test
    public void unavailableAndHostileEvidenceFailContentFree() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("hostile");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        String sentinel = "PRIVATE_EVIDENCE_SENTINEL";

        EditorSessionManager.HostValidationResult unavailable = manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> new EditorSessionManager.EvidenceUnavailable());
        assertInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE, unavailable);
        assertFalse(unavailable.toString().contains(sentinel));

        EditorSessionManager.HostValidationResult hostile = manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(true, "", new ThrowingCharSequence(true, sentinel), ""));
        assertInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE, hostile);
        assertFalse(hostile.toString().contains(sentinel));
    }

    @Test
    public void postflightAuthorityChangesAreClassifiedAfterOneEvidenceRead() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("postflight");
        InputConnection replacement = fakeConnection("postflight-other");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "left", "right"));
        AtomicInteger authorityReads = new AtomicInteger();
        AtomicInteger evidenceReads = new AtomicInteger();
        assertInvalid(TargetChangeReason.CONNECTION_CHANGED,
                manager.validateCurrentSession(
                        expected,
                        () -> new EditorSessionManager.LiveAuthority(
                                info, authorityReads.incrementAndGet() == 1
                                        ? connection : replacement),
                        (authorized, request) -> {
                            evidenceReads.incrementAndGet();
                            return evidence(true, "", "left", "right");
                        }));
        assertEquals(2, authorityReads.get());
        assertEquals(1, evidenceReads.get());

        AtomicInteger metadataReads = new AtomicInteger();
        EditorInfo changedMetadata = editorInfo("other.app", 1, 0, 0, false, false);
        assertInvalid(TargetChangeReason.EDITOR_METADATA_CHANGED,
                manager.validateCurrentSession(
                        expected,
                        () -> new EditorSessionManager.LiveAuthority(
                                metadataReads.incrementAndGet() == 1 ? info : changedMetadata,
                                connection),
                        (authorized, request) -> evidence(true, "", "left", "right")));
        assertEquals(2, metadataReads.get());

        AtomicInteger securityReads = new AtomicInteger();
        EditorInfo noLearning = editorInfo("app", 1, 0, 0, false, true);
        assertInvalid(TargetChangeReason.SECURITY_STATE_CHANGED,
                manager.validateCurrentSession(
                        expected,
                        () -> new EditorSessionManager.LiveAuthority(
                                securityReads.incrementAndGet() == 1 ? info : noLearning,
                                connection),
                        (authorized, request) -> evidence(true, "", "left", "right")));
        assertEquals(2, securityReads.get());
    }

    @Test
    public void collapsedUnavailableSelectionNormalizesEmptyAndSurroundingAvailabilityIsStrict() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("availability");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));

        validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> new EditorSessionManager.CurrentEvidence(
                        true, 0, 0, false, null, true, "", true, "")));

        CharSequence hostile = new FixedLengthCharSequence(1, true);
        assertInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE,
                manager.validateCurrentSession(
                        expected,
                        () -> new EditorSessionManager.LiveAuthority(info, connection),
                        (authorized, request) -> new EditorSessionManager.CurrentEvidence(
                                true, 0, 0, false, null, false, hostile, true, "")));
        assertInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE,
                manager.validateCurrentSession(
                        expected,
                        () -> new EditorSessionManager.LiveAuthority(info, connection),
                        (authorized, request) -> new EditorSessionManager.CurrentEvidence(
                                true, 0, 0, false, null, true, "", false, hostile)));
    }

    @Test
    public void failedLeaseClaimIsTerminalAndDoesNotRetrySupplier() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("lease-terminal");
        EditorInfo info = editorInfo("app", 1, 0, 0, false, false);
        manager.onStartInput(info, connection);
        EditorSessionSnapshot expected = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        EditorSessionManager.Validated validated = validated(manager.validateCurrentSession(
                expected,
                () -> new EditorSessionManager.LiveAuthority(info, connection),
                (authorized, request) -> evidence(false, null, "", "")));
        AtomicInteger calls = new AtomicInteger();
        assertFalse(validated.lease().authorityStillCurrent(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("PRIVATE_LEASE_SENTINEL");
        }));
        assertFalse(validated.lease().authorityStillCurrent(() -> {
            calls.incrementAndGet();
            return new EditorSessionManager.LiveAuthority(info, connection);
        }));
        assertEquals(1, calls.get());
    }

    @Test
    public void evidenceLimitsAreFailClosedAndUnicodeCodePointsRemainIntact() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("bounds");
        manager.start(descriptor("app", 1, 0, 0), connection);
        String emoji = "\uD83D\uDE00";
        captured(manager.captureFromEvidence(
                connection,
                "",
                emoji.repeat(EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS / 2),
                ""));
        assertRejected(EVIDENCE_LIMIT_EXCEEDED, manager.captureFromEvidence(
                connection,
                "",
                emoji.repeat(EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS / 2 + 1),
                ""));
        assertRejected(EVIDENCE_LIMIT_EXCEEDED, manager.captureFromEvidence(
                connection,
                "",
                new FixedLengthCharSequence(
                        EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS + 1, true),
                ""));
    }

    @Test
    public void hostileCharSequenceFailuresUseStableContentFreeReason() {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("hostile");
        manager.start(descriptor("app", 1, 0, 0), connection);

        for (CharSequence hostile : new CharSequence[]{
                new ThrowingCharSequence(true, "private length failure"),
                new ThrowingCharSequence(false, "private materialize failure")}) {
            EditorSessionManager.CaptureResult result =
                    manager.captureFromEvidence(connection, "", hostile, "");
            assertRejected(EditorSessionManager.CaptureFailure.INVALID_EVIDENCE, result);
            assertFalse(result.toString().contains("private"));
        }
    }

    @Test
    public void descriptorAndResultsDoNotRenderMetadataOrEditorEvidence() {
        EditorSessionManager.EditorDescriptor descriptor =
                descriptor("private.package", 991, 0, 0);
        assertFalse(descriptor.toString().contains("private.package"));
        assertFalse(descriptor.toString().contains("991"));

        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("redaction");
        manager.start(descriptor, connection);
        EditorSessionManager.CaptureResult captured =
                manager.captureFromEvidence(connection, "", "private-before", "private-after");
        assertFalse(captured.toString().contains("private.package"));
        assertFalse(captured.toString().contains("private-before"));
        assertFalse(captured.toString().contains("private-after"));
        assertFalse(manager.toString().contains("private.package"));
        assertTrue(manager.toString().contains("<redacted>"));
    }

    @Test
    public void recreationDoesNotResolveOldConnectionOrReuseToken() {
        InputConnection oldConnection = fakeConnection("old");
        EditorSessionManager first = managerAt(1);
        first.start(descriptor("app", 1, 0, 0), oldConnection);
        EditorSessionSnapshot old = captured(
                first.captureFromEvidence(oldConnection, "", "", ""));
        first.close();

        EditorSessionManager recreated = managerAt(2);
        assertRejected(NO_ACTIVE_SESSION,
                recreated.captureFromEvidence(oldConnection, "", "", ""));
        InputConnection newConnection = fakeConnection("new");
        recreated.start(descriptor("app", 1, 0, 0), newConnection);
        EditorSessionSnapshot current = captured(
                recreated.captureFromEvidence(newConnection, "", "", ""));
        assertNotEquals(old.connectionToken(), current.connectionToken());
    }

    @Test
    public void allEntryPointsFailFastOffOwnerThread() throws Exception {
        EditorSessionManager manager = managerAt(1);
        InputConnection connection = fakeConnection("owner");
        manager.start(descriptor("app", 1, 0, 0), connection);
        EditorInfo liveInfo = editorInfo("app", 1, 0, 0, false, false);
        EditorSessionSnapshot snapshot = captured(
                manager.captureFromEvidence(connection, "", "", ""));
        EditorSessionManager.Validated validated = validated(manager.validateCurrentSession(
                snapshot,
                () -> new EditorSessionManager.LiveAuthority(liveInfo, connection),
                (authorized, request) -> evidence(true, "", "", "")));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            EditorInfo info = new EditorInfo();
            info.packageName = "app";
            assertOffOwner(executor.submit(() -> manager.onStartInput(info, connection)));
            assertOffOwner(executor.submit(() -> manager.start(
                    descriptor("app", 1, 0, 0), connection)));
            assertOffOwner(executor.submit(manager::onFinishInput));
            assertOffOwner(executor.submit(() -> {
                manager.onSelectionChanged(1, 1);
                return null;
            }));
            assertOffOwner(executor.submit(() ->
                    manager.captureFromEvidence(connection, "", "", "")));
            assertOffOwner(executor.submit(() -> manager.validateCurrentSession(
                    snapshot,
                    () -> new EditorSessionManager.LiveAuthority(liveInfo, connection),
                    (authorized, request) -> evidence(true, "", "", ""))));
            assertOffOwner(executor.submit(() -> {
                manager.requireOwnerThreadForHost();
                return null;
            }));
            assertOffOwner(executor.submit(validated.lease()::sensitive));
            assertOffOwner(executor.submit(() -> validated.lease().authorityStillCurrent(
                    () -> new EditorSessionManager.LiveAuthority(liveInfo, connection))));
            assertFalse(validated.lease().sensitive());
            assertTrue(validated.lease().authorityStillCurrent(
                    () -> new EditorSessionManager.LiveAuthority(liveInfo, connection)));
            assertOffOwner(executor.submit(() -> {
                manager.close();
                return null;
            }));
        } finally {
            executor.shutdownNow();
        }
        manager.requireOwnerThreadForHost();
        captured(manager.captureFromEvidence(connection, "", "", ""));
    }

    private static EditorSessionManager managerAt(long elapsedRealtimeMs) {
        return new EditorSessionManager(() -> elapsedRealtimeMs);
    }

    private static EditorSessionManager.EditorDescriptor descriptor(
            String packageName, int fieldId, int selectionStart, int selectionEnd) {
        return new EditorSessionManager.EditorDescriptor(
                packageName,
                fieldId,
                FieldKind.GENERAL,
                InputType.TYPE_CLASS_TEXT,
                0,
                selectionStart,
                selectionEnd);
    }

    private static EditorSessionSnapshot captured(EditorSessionManager.CaptureResult result) {
        if (result instanceof EditorSessionManager.Captured captured) return captured.snapshot();
        fail("expected captured result, got " + result);
        throw new AssertionError();
    }

    private static void assertRejected(
            EditorSessionManager.CaptureFailure expected,
            EditorSessionManager.CaptureResult result) {
        if (result instanceof EditorSessionManager.Rejected rejected) {
            assertEquals(expected, rejected.reason());
            return;
        }
        fail("expected rejected result, got " + result);
    }

    private static EditorSessionManager.Validated validated(
            EditorSessionManager.HostValidationResult result) {
        if (result instanceof EditorSessionManager.Validated validated) return validated;
        fail("expected validated result, got " + result);
        throw new AssertionError();
    }

    private static void assertInvalid(
            TargetChangeReason expected,
            EditorSessionManager.HostValidationResult result) {
        if (result instanceof EditorSessionManager.ValidationInvalid invalid) {
            assertEquals(expected, invalid.reason());
            return;
        }
        fail("expected invalid result, got " + result);
    }

    private static void assertConnectionInvalid(
            TargetChangeReason expected,
            EditorSessionManager.ConnectionUseResult result) {
        if (result instanceof EditorSessionManager.ConnectionInvalid invalid) {
            assertEquals(expected, invalid.reason());
            return;
        }
        fail("expected invalid connection use, got " + result);
    }

    private static ProcessInputConnectionRegistry registryOf(EditorSessionManager manager)
            throws Exception {
        Field field = EditorSessionManager.class.getDeclaredField("connections");
        field.setAccessible(true);
        return (ProcessInputConnectionRegistry) field.get(manager);
    }

    private static EditorSessionManager.CurrentEvidence evidence(
            boolean selectedAvailable,
            CharSequence selected,
            CharSequence before,
            CharSequence after) {
        return evidence(0, 0, selectedAvailable, selected, before, after);
    }

    private static EditorSessionManager.CurrentEvidence evidence(
            int selectionStart,
            int selectionEnd,
            boolean selectedAvailable,
            CharSequence selected,
            CharSequence before,
            CharSequence after) {
        return new EditorSessionManager.CurrentEvidence(
                true,
                selectionStart,
                selectionEnd,
                selectedAvailable,
                selected,
                true,
                before,
                true,
                after);
    }

    private static EditorInfo editorInfo(
            String packageName,
            int fieldId,
            int selectionStart,
            int selectionEnd,
            boolean sensitive,
            boolean noLearning) {
        EditorInfo info = new EditorInfo();
        info.packageName = packageName;
        info.fieldId = fieldId;
        info.initialSelStart = selectionStart;
        info.initialSelEnd = selectionEnd;
        info.inputType = InputType.TYPE_CLASS_TEXT
                | (sensitive ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0);
        info.imeOptions = noLearning ? EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING : 0;
        return info;
    }

    private static void assertOffOwner(Future<?> future) throws Exception {
        try {
            future.get();
            fail("expected IllegalStateException");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    private static InputConnection fakeConnection(String label) {
        return (InputConnection) Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(),
                new Class<?>[]{InputConnection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "toString" -> "FakeInputConnection(" + label + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError(
                            "manager must not invoke InputConnection." + method.getName());
                });
    }

    private static final class FixedLengthCharSequence implements CharSequence {
        private final int length;
        private final boolean failOnMaterialize;

        FixedLengthCharSequence(int length, boolean failOnMaterialize) {
            this.length = length;
            this.failOnMaterialize = failOnMaterialize;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            throw new AssertionError("charAt must not be called");
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            throw new AssertionError("subSequence must not be called");
        }

        @Override
        public String toString() {
            if (failOnMaterialize) throw new AssertionError("toString must not be called");
            return "";
        }
    }

    private static final class ThrowingCharSequence implements CharSequence {
        private final boolean throwOnLength;
        private final String message;

        ThrowingCharSequence(boolean throwOnLength, String message) {
            this.throwOnLength = throwOnLength;
            this.message = message;
        }

        @Override public int length() {
            if (throwOnLength) throw new IllegalStateException(message);
            return 1;
        }
        @Override public char charAt(int index) { return 'x'; }
        @Override public CharSequence subSequence(int start, int end) { return this; }
        @Override public String toString() { throw new IllegalStateException(message); }
    }
}
