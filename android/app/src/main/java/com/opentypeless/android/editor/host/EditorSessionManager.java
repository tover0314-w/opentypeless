package com.opentypeless.android.editor.host;

import android.os.SystemClock;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContextClassifier;
import com.opentypeless.android.editor.EditorSessionLimits;
import com.opentypeless.android.editor.EditorAction;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.CommitRecord;
import com.opentypeless.android.editor.EditorOperation;
import com.opentypeless.android.editor.EditorTransactionResult;
import com.opentypeless.android.editor.CommitRecordRequest;
import com.opentypeless.android.editor.CompositionOwner;
import com.opentypeless.android.editor.OperationSource;
import com.opentypeless.android.editor.RejectionReason;
import com.opentypeless.android.editor.Sha256EditorTextHasher;
import com.opentypeless.android.editor.TransactionReceipt;
import com.opentypeless.android.editor.SessionValidationResult;
import com.opentypeless.android.editor.SessionValidator;
import com.opentypeless.android.editor.TargetChangeReason;
import com.opentypeless.android.editor.TextFingerprint;
import com.opentypeless.android.editor.TextRange;
import java.util.Objects;
import java.util.Optional;

/**
 * Android-host adapter that owns the active editor capability and captures immutable evidence.
 *
 * <p>This class is owner-thread confined and never exposes an {@link InputConnection}. Its
 * transaction child retains at most one process-local commit record plus one eligible composition
 * basis; both are bounded, redacted in diagnostics, and cleared on lifecycle revocation. The
 * legacy shadow-capture entry points remain read-only, while package-confined transaction entry
 * points are the sole audited mutation path.
 */
public final class EditorSessionManager {
    /**
     * Synchronous IME composition-root authority. Implementations may only return their current
     * framework objects and must not retain evidence or perform editor mutation.
     */
    public interface KeyboardHost {
        EditorInfo currentEditorInfo();

        InputConnection currentInputConnection();
    }

    /** Stable, content-free reason why a shadow snapshot could not be captured. */
    public enum CaptureFailure {
        NO_ACTIVE_SESSION,
        CONNECTION_CHANGED,
        INVALID_EDITOR_METADATA,
        UNREPRESENTABLE_SELECTION,
        SELECTED_TEXT_UNAVAILABLE,
        SURROUNDING_TEXT_UNAVAILABLE,
        SELECTION_MISMATCH,
        SENSITIVE_EVIDENCE_PRESENT,
        INVALID_EVIDENCE,
        EVIDENCE_LIMIT_EXCEEDED
    }

    /** Explicit result; callers never have to infer a rejection from an empty optional. */
    public sealed interface CaptureResult permits Captured, Rejected {}

    public record Captured(EditorSessionSnapshot snapshot) implements CaptureResult {
        public Captured {
            Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public String toString() {
            return "Captured{<redacted>}";
        }
    }

    public record Rejected(CaptureFailure reason) implements CaptureResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "Rejected{reason=" + reason + '}';
        }
    }

    @FunctionalInterface
    interface ElapsedRealtimeClock {
        long nowMillis();
    }

    @FunctionalInterface
    interface OwnerGuard {
        void requireOwner();
    }

    /** Supplies a fresh framework authority observation; invoked exactly once pre/post evidence. */
    @FunctionalInterface
    interface LiveAuthoritySupplier {
        LiveAuthority get();
    }

    /** Reads ephemeral evidence only after authority and security preflight succeeds. */
    @FunctionalInterface
    interface CurrentEvidenceReader {
        EvidenceReadResult read(
                InputConnection authorizedConnection, CurrentEvidenceRequest request);
    }

    /** Content-free bounds for one exact-connection evidence read. */
    record CurrentEvidenceRequest(int beforeUtf16Units, int afterUtf16Units) {
        private static final int MAX_BEFORE_UTF16_UNITS =
                EditorOperation.MAX_TEXT_CODE_POINTS * 2
                        + EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS;

        CurrentEvidenceRequest {
            if (beforeUtf16Units < 0 || beforeUtf16Units > MAX_BEFORE_UTF16_UNITS) {
                throw new IllegalArgumentException("current before evidence exceeds its bound");
            }
            if (afterUtf16Units < 0
                    || afterUtf16Units > EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS) {
                throw new IllegalArgumentException("current after evidence exceeds its bound");
            }
        }
    }

    /**
     * Reads one bounded Undo proof from the exact registry-owned connection.
     *
     * <p>The request contains lengths only. It never exposes the commit ID, text or fingerprints,
     * and the returned plaintext remains synchronous host-only evidence.
     */
    @FunctionalInterface
    interface UndoEvidenceReader {
        UndoEvidenceReadResult read(
                InputConnection authorizedConnection, UndoEvidenceRequest request);
    }

    record UndoEvidenceRequest(int beforeUtf16Units, int afterUtf16Units) {
        private static final int MAX_BEFORE_UTF16_UNITS =
                EditorOperation.MAX_TEXT_CODE_POINTS * 2
                        + EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS;

        UndoEvidenceRequest {
            if (beforeUtf16Units < 0 || beforeUtf16Units > MAX_BEFORE_UTF16_UNITS) {
                throw new IllegalArgumentException("undo before evidence exceeds its bound");
            }
            if (afterUtf16Units < 0
                    || afterUtf16Units > EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS) {
                throw new IllegalArgumentException("undo after evidence exceeds its bound");
            }
        }
    }

    sealed interface UndoEvidenceReadResult permits UndoEvidence, UndoEvidenceUnavailable {}

    /** Host-only, ephemeral Undo evidence. Diagnostics never materialize its values. */
    record UndoEvidence(
            boolean selectionAvailable,
            int selectionStart,
            int selectionEnd,
            boolean selectedTextAvailable,
            CharSequence selectedText,
            boolean beforeTextAvailable,
            CharSequence beforeText,
            boolean afterTextAvailable,
            CharSequence afterText) implements UndoEvidenceReadResult {
        @Override
        public String toString() {
            return "UndoEvidence{<redacted>}";
        }
    }

    record UndoEvidenceUnavailable() implements UndoEvidenceReadResult {}

    sealed interface EvidenceReadResult permits CurrentEvidence, EvidenceUnavailable {}

    /** Host-only ephemeral evidence; plaintext is omitted from diagnostics. */
    record CurrentEvidence(
            boolean selectionAvailable,
            int selectionStart,
            int selectionEnd,
            boolean selectedTextAvailable,
            CharSequence selectedText,
            boolean beforeTextAvailable,
            CharSequence beforeText,
            boolean afterTextAvailable,
            CharSequence afterText) implements EvidenceReadResult {
        @Override
        public String toString() {
            return "CurrentEvidence{<redacted>}";
        }
    }

    record EvidenceUnavailable() implements EvidenceReadResult {}

    /** Fresh Android-owned authority observed by the composition root. */
    record LiveAuthority(EditorInfo editorInfo, InputConnection connection) {
        @Override
        public String toString() {
            return "LiveAuthority{<redacted>}";
        }
    }

    sealed interface HostValidationResult permits Validated, ValidationInvalid {}

    /** Immutable host-only evidence from the exact successful validation read. */
    record ValidatedEvidence(TextRange selection, String selected, String before, String after) {
        ValidatedEvidence {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(selected, "selected");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }

        @Override
        public String toString() {
            return "ValidatedEvidence{<redacted>}";
        }
    }

    /** Package-confined validation proof and its ephemeral, bounded evidence. */
    record Validated(HostLease lease, ValidatedEvidence evidence) implements HostValidationResult {
        Validated {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(evidence, "evidence");
        }

        @Override
        public String toString() {
            return "Validated{<redacted>}";
        }
    }

    record ValidationInvalid(TargetChangeReason reason) implements HostValidationResult {
        ValidationInvalid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    sealed interface ReplaceValidationResult
            permits ReplaceValidated, ReplaceValidationInvalid {}

    record ReplaceValidated() implements ReplaceValidationResult {}

    record ReplaceValidationInvalid(TargetChangeReason reason)
            implements ReplaceValidationResult {
        ReplaceValidationInvalid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    sealed interface UndoValidationResult permits UndoValidated, UndoValidationInvalid {}

    record UndoValidated(HostLease lease) implements UndoValidationResult {
        UndoValidated {
            Objects.requireNonNull(lease, "lease");
        }

        @Override
        public String toString() {
            return "UndoValidated{<redacted>}";
        }
    }

    record UndoValidationInvalid(TargetChangeReason reason) implements UndoValidationResult {
        UndoValidationInvalid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Synchronous, non-retaining use of the exact registry-owned editor connection. */
    @FunctionalInterface
    interface ScopedConnectionUse {
        EditorTransactionResult use(InputConnection connection);
    }

    sealed interface ConnectionUseResult permits ConnectionUsed, ConnectionInvalid {}

    record ConnectionUsed(EditorTransactionResult result) implements ConnectionUseResult {
        ConnectionUsed {
            Objects.requireNonNull(result, "result");
        }

        @Override
        public String toString() {
            return "ConnectionUsed{<redacted>}";
        }
    }

    record ConnectionInvalid(TargetChangeReason reason) implements ConnectionUseResult {
        ConnectionInvalid {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "ConnectionInvalid{reason=" + reason + '}';
        }
    }

    /** Receipt-preserving counterpart used by the same synchronous exact-connection boundary. */
    @FunctionalInterface
    interface ScopedReceiptConnectionUse {
        TransactionReceipt use(InputConnection connection);
    }

    sealed interface ReceiptConnectionUseResult permits ReceiptConnectionUsed, ReceiptConnectionInvalid {}

    record ReceiptConnectionUsed(TransactionReceipt receipt) implements ReceiptConnectionUseResult {
        ReceiptConnectionUsed {
            Objects.requireNonNull(receipt, "receipt");
        }

        @Override
        public String toString() {
            return "ReceiptConnectionUsed{<redacted>}";
        }
    }

    record ReceiptConnectionInvalid(TargetChangeReason reason) implements ReceiptConnectionUseResult {
        ReceiptConnectionInvalid {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "ReceiptConnectionInvalid{reason=" + reason + '}';
        }
    }

    /**
     * One-shot proof that lifecycle/identity remained stable at validation time.
     *
     * <p>EDT-007 must perform full evidence/fingerprint validation again after beginBatchEdit and
     * immediately before its first mutator. This lease alone never authorizes a text write.
     */
    final class HostLease {
        private final long expectedEpoch;
        private final long expectedToken;
        private final long expectedAuthorityRevision;
        private final ActiveEditor expectedEditor;
        private final TextRange expectedSelection;
        private boolean claimed;

        private HostLease(
                long expectedEpoch,
                long expectedToken,
                long expectedAuthorityRevision,
                ActiveEditor expectedEditor,
                TextRange expectedSelection) {
            this.expectedEpoch = expectedEpoch;
            this.expectedToken = expectedToken;
            this.expectedAuthorityRevision = expectedAuthorityRevision;
            this.expectedEditor = Objects.requireNonNull(expectedEditor, "editor");
            this.expectedSelection = Objects.requireNonNull(expectedSelection, "selection");
        }

        /** Off-owner use fails fast without consuming; every owner-thread attempt is terminal. */
        boolean authorityStillCurrent(LiveAuthoritySupplier authoritySupplier) {
            requireOwnerThread();
            if (claimed) return false;
            claimed = true;
            if (authoritySupplier == null
                    || !matchesLeaseState()) {
                return false;
            }
            try {
                FrozenAuthority live = freezeAuthority(authoritySupplier.get());
                InputConnection resolved = connections.resolve(expectedToken);
                // The supplier is external host code and may synchronously re-enter IME lifecycle
                // callbacks. Recheck every process-local identity value after it returns.
                return matchesLeaseState()
                        && live != null
                        && resolved != null
                        && live.connection() == resolved
                        && metadataMatches(expectedEditor, live.descriptor())
                        && securityMatches(expectedEditor, live.descriptor());
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        /**
         * Consumes this lease to synchronously invoke one transaction callback with the exact
         * current connection. It invokes no external supplier between the completed full
         * validation and transaction code. The connection is neither retained nor returned by
         * this host API.
         *
         * <p>Off-owner calls fail before consumption. Every owner-thread call is terminal,
         * including a callback that throws. Callback exceptions deliberately propagate.
         */
        ConnectionUseResult consumeWithCurrentConnection(ScopedConnectionUse use) {
            requireOwnerThread();
            if (claimed) {
                return new ConnectionInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            claimed = true;

            TargetChangeReason invalidReason = leaseInvalidReason();
            if (invalidReason != null) return new ConnectionInvalid(invalidReason);

            InputConnection resolved = connections.resolve(expectedToken);
            if (resolved == null) {
                return new ConnectionInvalid(TargetChangeReason.SESSION_REVOKED);
            }
            Objects.requireNonNull(use, "use");
            EditorTransactionResult result = Objects.requireNonNull(
                    use.use(resolved), "scoped connection result");
            return new ConnectionUsed(result);
        }

        /** Same one-shot scope as {@link #consumeWithCurrentConnection}, preserving its receipt. */
        ReceiptConnectionUseResult consumeWithCurrentConnectionForReceipt(
                ScopedReceiptConnectionUse use) {
            requireOwnerThread();
            if (claimed) {
                return new ReceiptConnectionInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            claimed = true;

            TargetChangeReason invalidReason = leaseInvalidReason();
            if (invalidReason != null) return new ReceiptConnectionInvalid(invalidReason);

            InputConnection resolved = connections.resolve(expectedToken);
            if (resolved == null) {
                return new ReceiptConnectionInvalid(TargetChangeReason.SESSION_REVOKED);
            }
            Objects.requireNonNull(use, "use");
            TransactionReceipt receipt = Objects.requireNonNull(
                    use.use(resolved), "scoped transaction receipt");
            return new ReceiptConnectionUsed(receipt);
        }

        /** Content-free security state for EDT-007 policy; never grants an operation by itself. */
        boolean sensitive() {
            requireOwnerThread();
            return expectedEditor.sensitive();
        }

        private boolean matchesLeaseState() {
            return !closed
                    && expectedEditor.equals(activeEditor)
                    && expectedSelection.equals(selection)
                    && epoch == expectedEpoch
                    && authorityRevision == expectedAuthorityRevision
                    && connections.currentToken() == expectedToken
                    && connections.resolve(expectedToken) != null;
        }

        private TargetChangeReason leaseInvalidReason() {
            if (closed || activeEditor == null) return TargetChangeReason.NO_ACTIVE_SESSION;
            if (epoch != expectedEpoch) return TargetChangeReason.EPOCH_CHANGED;

            long currentToken = connections.currentToken();
            if (currentToken <= InputConnectionRegistry.NO_CONNECTION_TOKEN) {
                return TargetChangeReason.SESSION_REVOKED;
            }
            if (currentToken != expectedToken) return TargetChangeReason.CONNECTION_CHANGED;
            if (connections.resolve(expectedToken) == null) {
                return TargetChangeReason.SESSION_REVOKED;
            }

            if (!expectedEditor.equals(activeEditor)) {
                if (expectedEditor.sensitive() != activeEditor.sensitive()
                        || expectedEditor.learningAllowed() != activeEditor.learningAllowed()) {
                    return TargetChangeReason.SECURITY_STATE_CHANGED;
                }
                return TargetChangeReason.EDITOR_METADATA_CHANGED;
            }
            if (authorityRevision != expectedAuthorityRevision
                    || !expectedSelection.equals(selection)) {
                return TargetChangeReason.SELECTION_CHANGED;
            }
            return null;
        }

        @Override
        public String toString() {
            return "HostLease{<redacted>}";
        }
    }

    /** Immutable copy of the Android-owned and mutable EditorInfo fields used by the domain model. */
    record EditorDescriptor(
            String packageName,
            int fieldId,
            FieldKind fieldKind,
            int inputType,
            int imeOptions,
            int initialSelectionStart,
            int initialSelectionEnd) {
        EditorDescriptor {
            fieldKind = Objects.requireNonNull(fieldKind, "fieldKind");
        }

        static EditorDescriptor copyOf(EditorInfo info) {
            Objects.requireNonNull(info, "info");
            return new EditorDescriptor(
                    info.packageName,
                    info.fieldId,
                    InputContextClassifier.classify(info),
                    info.inputType,
                    info.imeOptions,
                    info.initialSelStart,
                    info.initialSelEnd);
        }

        @Override
        public String toString() {
            return "EditorDescriptor{<redacted>}";
        }
    }

    private record ActiveEditor(
            long connectionToken,
            String packageName,
            int fieldId,
            FieldKind fieldKind,
            int inputType,
            int imeOptions,
            boolean learningAllowed,
            boolean sensitive) {
        @Override
        public String toString() {
            return "ActiveEditor{<redacted>}";
        }
    }

    private record FrozenAuthority(EditorDescriptor descriptor, InputConnection connection) {
        @Override
        public String toString() {
            return "FrozenAuthority{<redacted>}";
        }
    }

    private record ValidationBasis(
            long epoch,
            long token,
            long authorityRevision,
            InputConnection connection,
            ActiveEditor editor,
            TextRange selection) {
        @Override
        public String toString() {
            return "ValidationBasis{<redacted>}";
        }
    }

    private record MaterializedEvidence(
            TextRange selection, String selected, String before, String after) {
        private MaterializedEvidence {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(selected, "selected");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }

        @Override
        public String toString() {
            return "MaterializedEvidence{<redacted>}";
        }
    }

    private record EvidenceAttempt(
            MaterializedEvidence evidence,
            TargetChangeReason failureReason,
            TextRange observedSelection) {
        private EvidenceAttempt {
            if ((evidence == null) == (failureReason == null)) {
                throw new IllegalArgumentException("evidence attempt must have one outcome");
            }
            observedSelection = Objects.requireNonNull(observedSelection, "observedSelection");
        }

        @Override
        public String toString() {
            return "EvidenceAttempt{<redacted>}";
        }
    }

    private record MaterializedUndoEvidence(
            TextRange selection, String selected, String before, String after) {
        @Override
        public String toString() {
            return "MaterializedUndoEvidence{<redacted>}";
        }
    }

    private record UndoEvidenceAttempt(
            MaterializedUndoEvidence evidence,
            TargetChangeReason failureReason,
            TextRange observedSelection) {
        private UndoEvidenceAttempt {
            if ((evidence == null) == (failureReason == null)) {
                throw new IllegalArgumentException("undo evidence attempt must have one outcome");
            }
            observedSelection = Objects.requireNonNull(observedSelection, "observedSelection");
        }

        @Override
        public String toString() {
            return "UndoEvidenceAttempt{<redacted>}";
        }
    }

    enum UndoProofState {
        COMMITTED,
        ORIGINAL
    }

    enum RawProofState {
        COMMITTED,
        ORIGINAL,
        UNDO,
        RAW
    }

    enum ReplaceProofState {
        ORIGINAL,
        INTENDED
    }

    /**
     * Owner-bound, one-shot observation spanning one exact-ID replacement content mutator.
     *
     * <p>The token contains no editor text or {@link InputConnection}. It accepts either no
     * framework selection callback or exactly one callback to the operation's expected cursor;
     * every other lifecycle/revision transition fails closed before the next content mutator.
     */
    static final class RawTransition {
        private final Object ownerStamp;
        private final long expectedEpoch;
        private final long expectedToken;
        private final long expectedAuthorityRevision;
        private final ActiveEditor expectedEditor;
        private final TextRange managerSelection;
        private final TextRange provenFromSelection;
        private final TextRange targetSelection;
        private final RawProofState targetState;
        private final EditorSessionSnapshot expectedOrigin;
        private final TextFingerprint expectedInsertedFingerprint;
        private final TextFingerprint expectedReplacementFingerprint;
        private boolean claimed;

        private RawTransition(
                Object ownerStamp,
                long expectedEpoch,
                long expectedToken,
                long expectedAuthorityRevision,
                ActiveEditor expectedEditor,
                TextRange managerSelection,
                TextRange provenFromSelection,
                TextRange targetSelection,
                RawProofState targetState,
                EditorSessionSnapshot expectedOrigin,
                TextFingerprint expectedInsertedFingerprint,
                TextFingerprint expectedReplacementFingerprint) {
            this.ownerStamp = Objects.requireNonNull(ownerStamp, "ownerStamp");
            this.expectedEpoch = expectedEpoch;
            this.expectedToken = expectedToken;
            this.expectedAuthorityRevision = expectedAuthorityRevision;
            this.expectedEditor = Objects.requireNonNull(expectedEditor, "expectedEditor");
            this.managerSelection = Objects.requireNonNull(managerSelection, "managerSelection");
            this.provenFromSelection = Objects.requireNonNull(
                    provenFromSelection, "provenFromSelection");
            this.targetSelection = Objects.requireNonNull(targetSelection, "targetSelection");
            this.targetState = Objects.requireNonNull(targetState, "targetState");
            this.expectedOrigin = Objects.requireNonNull(expectedOrigin, "expectedOrigin");
            this.expectedInsertedFingerprint = Objects.requireNonNull(
                    expectedInsertedFingerprint, "expectedInsertedFingerprint");
            this.expectedReplacementFingerprint = Objects.requireNonNull(
                    expectedReplacementFingerprint, "expectedReplacementFingerprint");
        }

        @Override
        public String toString() {
            return "RawTransition{<redacted>}";
        }
    }

    /** Owner-bound, one-shot and content-free proof for a ReplaceSelection outcome. */
    static final class ReplaceTransition {
        private final Object ownerStamp;
        private final long expectedEpoch;
        private final long expectedToken;
        private final long expectedAuthorityRevision;
        private final ActiveEditor expectedEditor;
        private final TextRange managerSelection;
        private final TextRange originalSelection;
        private final TextRange targetSelection;
        private final ReplaceProofState targetState;
        private final int replacementUtf16Units;
        private final TextFingerprint expectedSelectedFingerprint;
        private final TextFingerprint expectedBeforeFingerprint;
        private final TextFingerprint expectedAfterFingerprint;
        private final TextFingerprint expectedContextFingerprint;
        private final TextFingerprint expectedReplacementFingerprint;
        private boolean claimed;

        private ReplaceTransition(
                Object ownerStamp,
                long expectedEpoch,
                long expectedToken,
                long expectedAuthorityRevision,
                ActiveEditor expectedEditor,
                TextRange managerSelection,
                TextRange originalSelection,
                TextRange targetSelection,
                ReplaceProofState targetState,
                int replacementUtf16Units,
                TextFingerprint expectedSelectedFingerprint,
                TextFingerprint expectedBeforeFingerprint,
                TextFingerprint expectedAfterFingerprint,
                TextFingerprint expectedContextFingerprint,
                TextFingerprint expectedReplacementFingerprint) {
            this.ownerStamp = Objects.requireNonNull(ownerStamp, "ownerStamp");
            this.expectedEpoch = expectedEpoch;
            this.expectedToken = expectedToken;
            this.expectedAuthorityRevision = expectedAuthorityRevision;
            this.expectedEditor = Objects.requireNonNull(expectedEditor, "expectedEditor");
            this.managerSelection = Objects.requireNonNull(managerSelection, "managerSelection");
            this.originalSelection = Objects.requireNonNull(originalSelection, "originalSelection");
            this.targetSelection = Objects.requireNonNull(targetSelection, "targetSelection");
            this.targetState = Objects.requireNonNull(targetState, "targetState");
            this.replacementUtf16Units = replacementUtf16Units;
            this.expectedSelectedFingerprint = Objects.requireNonNull(
                    expectedSelectedFingerprint, "expectedSelectedFingerprint");
            this.expectedBeforeFingerprint = Objects.requireNonNull(
                    expectedBeforeFingerprint, "expectedBeforeFingerprint");
            this.expectedAfterFingerprint = Objects.requireNonNull(
                    expectedAfterFingerprint, "expectedAfterFingerprint");
            this.expectedContextFingerprint = Objects.requireNonNull(
                    expectedContextFingerprint, "expectedContextFingerprint");
            this.expectedReplacementFingerprint = Objects.requireNonNull(
                    expectedReplacementFingerprint, "expectedReplacementFingerprint");
        }

        @Override
        public String toString() {
            return "ReplaceTransition{<redacted>}";
        }
    }

    private final OwnerGuard ownerGuard;
    private final ProcessInputConnectionRegistry connections;
    private final ElapsedRealtimeClock clock;
    private final EditorTransactionManager transactions;
    private final Object rawTransitionOwnerStamp = new Object();
    private final Object replaceTransitionOwnerStamp = new Object();

    private long epoch;
    private long authorityRevision;
    private ActiveEditor activeEditor;
    private TextRange selection = TextRange.UNKNOWN;
    private boolean closed;

    public EditorSessionManager() {
        this(
                () -> {
                    if (Looper.myLooper() != Looper.getMainLooper()) {
                        throw new IllegalStateException(
                                "EditorSessionManager requires the Android main thread");
                    }
                },
                SystemClock::elapsedRealtime);
    }

    EditorSessionManager(ElapsedRealtimeClock clock) {
        this(threadGuard(Thread.currentThread()), clock);
    }

    EditorSessionManager(OwnerGuard ownerGuard, ElapsedRealtimeClock clock) {
        this(ownerGuard, clock, null, null);
    }

    EditorSessionManager(
            OwnerGuard ownerGuard,
            ElapsedRealtimeClock clock,
            CommitLedger.CommitIdSource commitIdSource,
            EditorTransactionManager.CleanupSink cleanupSink) {
        this(ownerGuard, clock, commitIdSource, cleanupSink, audit -> {});
    }

    EditorSessionManager(
            OwnerGuard ownerGuard,
            ElapsedRealtimeClock clock,
            CommitLedger.CommitIdSource commitIdSource,
            EditorTransactionManager.CleanupSink cleanupSink,
            EditorTransactionManager.AuditSink auditSink) {
        this.ownerGuard = Objects.requireNonNull(ownerGuard, "ownerGuard");
        this.ownerGuard.requireOwner();
        connections = new ProcessInputConnectionRegistry();
        this.clock = Objects.requireNonNull(clock, "clock");
        EditorTransactionManager.CleanupSink safeCleanup = cleanupSink == null
                ? failure -> {}
                : cleanupSink;
        CommitLedger ledger = commitIdSource == null
                ? new CommitLedger()
                : new CommitLedger(commitIdSource);
        transactions = new EditorTransactionManager(
                this,
                safeCleanup,
                Objects.requireNonNull(auditSink, "auditSink"),
                ledger);
    }

    /**
     * Starts a new editor generation. Every call rotates both epoch and connection token.
     *
     * <p>A null EditorInfo or connection still advances the epoch but leaves no active session.
     */
    public long onStartInput(EditorInfo info, InputConnection connection) {
        requireOwnerThread();
        requireOpen();
        beginNewEpoch();
        if (info == null || connection == null) return epoch;
        try {
            return activate(EditorDescriptor.copyOf(info), connection);
        } catch (RuntimeException invalidMetadata) {
            // Android/OEM metadata is mutable external input. The old capability is already gone.
            clearActive();
            return epoch;
        }
    }

    long start(EditorDescriptor descriptor, InputConnection connection) {
        requireOwnerThread();
        requireOpen();
        beginNewEpoch();
        if (descriptor == null || connection == null) return epoch;

        return activate(descriptor, connection);
    }

    private long activate(EditorDescriptor descriptor, InputConnection connection) {
        try {
            EditorSessionLimits.requirePackageName(descriptor.packageName());
        } catch (IllegalArgumentException | NullPointerException invalidMetadata) {
            clearActive();
            return epoch;
        }

        long token = connections.register(connection);
        boolean sensitive = descriptor.fieldKind() == FieldKind.SENSITIVE;
        boolean learningAllowed = !sensitive
                && (descriptor.imeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        activeEditor = new ActiveEditor(
                token,
                descriptor.packageName(),
                descriptor.fieldId(),
                descriptor.fieldKind(),
                descriptor.inputType(),
                descriptor.imeOptions(),
                learningAllowed,
                sensitive);
        selection = rangeOrUnknown(
                descriptor.initialSelectionStart(), descriptor.initialSelectionEnd());
        return epoch;
    }

    /** Mirrors onFinishInput: rotate epoch, revoke the capability, and forget editor metadata. */
    public long onFinishInput() {
        requireOwnerThread();
        requireOpen();
        transactions.revokeSessionState();
        advanceEpoch();
        advanceAuthorityRevision();
        clearActive();
        return epoch;
    }

    /** Updates only coordinates reported by Android; no editor content is read here. */
    public void onSelectionChanged(int start, int end) {
        requireOwnerThread();
        requireOpen();
        if (activeEditor == null) return;
        TextRange updated = rangeOrUnknown(start, end);
        if (selection.equals(updated)) return;
        advanceAuthorityRevision();
        selection = updated;
    }

    /**
     * Captures already-observed evidence without performing a second InputConnection read.
     *
     * <p>The observed connection is used only for exact identity validation. It is never returned
     * or included in the immutable domain snapshot.
     */
    public CaptureResult captureFromEvidence(
            InputConnection observedConnection,
            CharSequence selectedText,
            CharSequence beforeText,
            CharSequence afterText) {
        requireOwnerThread();
        requireOpen();
        ActiveEditor editor = activeEditor;
        if (editor == null) return rejected(CaptureFailure.NO_ACTIVE_SESSION);
        if (observedConnection == null
                || connections.resolve(editor.connectionToken()) != observedConnection) {
            return rejected(CaptureFailure.CONNECTION_CHANGED);
        }

        if (beforeText == null || afterText == null) {
            return rejected(CaptureFailure.SURROUNDING_TEXT_UNAVAILABLE);
        }

        int selectedLength;
        int beforeLength;
        int afterLength;
        try {
            selectedLength = selectedText == null ? 0 : selectedText.length();
            beforeLength = beforeText.length();
            afterLength = afterText.length();
        } catch (RuntimeException invalidEvidence) {
            return rejected(CaptureFailure.INVALID_EVIDENCE);
        }
        if (selectedLength < 0 || beforeLength < 0 || afterLength < 0) {
            return rejected(CaptureFailure.INVALID_EVIDENCE);
        }
        if (selectedLength > EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2
                || beforeLength > EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS
                || afterLength > EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS) {
            return rejected(CaptureFailure.EVIDENCE_LIMIT_EXCEEDED);
        }

        if (editor.sensitive()) {
            // Never materialize caller-provided content for sensitive fields, even transiently.
            if (selectedLength != 0 || beforeLength != 0 || afterLength != 0) {
                return rejected(CaptureFailure.SENSITIVE_EVIDENCE_PRESENT);
            }
            return capture(editor, "", "", "", false, true);
        }

        String selected;
        String before;
        String after;
        try {
            selected = selectedText == null ? "" : selectedText.toString();
            before = beforeText.toString();
            after = afterText.toString();
        } catch (RuntimeException invalidEvidence) {
            return rejected(CaptureFailure.INVALID_EVIDENCE);
        }
        if (selected.length() > EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2
                || before.length() > EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS
                || after.length() > EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS) {
            return rejected(CaptureFailure.EVIDENCE_LIMIT_EXCEEDED);
        }

        CaptureFailure selectionFailure = validateSelection(selection, selectedText, selected);
        if (selectionFailure != null) return rejected(selectionFailure);
        try {
            if (selected.codePointCount(0, selected.length())
                    > EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS) {
                return rejected(CaptureFailure.EVIDENCE_LIMIT_EXCEEDED);
            }
            EditorSessionLimits.requireSelectedText(selected);
            EditorSessionLimits.requireSurroundingInput(before, "beforeText");
            EditorSessionLimits.requireSurroundingInput(after, "afterText");
        } catch (IllegalArgumentException invalidEvidence) {
            return rejected(CaptureFailure.INVALID_EVIDENCE);
        } catch (NullPointerException impossibleAfterNormalization) {
            return rejected(CaptureFailure.INVALID_EVIDENCE);
        }
        return capture(
                editor,
                selected,
                before,
                after,
                editor.learningAllowed(),
                false);
    }

    /**
     * Validates a captured session against fresh framework authority and bounded current evidence.
     *
     * <p>This host-only entry point invokes {@code authoritySupplier} exactly twice on a successful
     * path (before and after evidence) and invokes {@code evidenceReader} at most once. Every
     * preflight rejection occurs before evidence is read. Matching sensitive sessions are
     * validated with redacted empty evidence and never invoke the evidence reader; EDT-007 policy
     * remains responsible for restricting which local operation sources may write such fields.
     */
    HostValidationResult validateCurrentSession(
            EditorSessionSnapshot expected,
            LiveAuthoritySupplier authoritySupplier,
            CurrentEvidenceReader evidenceReader) {
        requireOwnerThread();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(authoritySupplier, "authoritySupplier");
        Objects.requireNonNull(evidenceReader, "evidenceReader");
        // A close may be delivered synchronously by an OEM during beginBatchEdit. Validation is a
        // transaction boundary, so that lifecycle race is a stable target change rather than a
        // programming-contract exception. Public lifecycle/capture entry points remain fail-fast.
        if (closed) return invalid(TargetChangeReason.NO_ACTIVE_SESSION);

        ValidationBasis basis = currentValidationBasis(expected);
        if (basis == null) return invalid(preflightReason(expected));

        FrozenAuthority pre = readAuthority(authoritySupplier);
        TargetChangeReason preReason = validateAuthority(expected, basis, pre);
        if (preReason != null) return invalid(preReason);

        MaterializedEvidence evidence;
        if (basis.editor().sensitive()) {
            evidence = new MaterializedEvidence(basis.selection(), "", "", "");
        } else {
            EvidenceAttempt attempt = readEvidence(
                    evidenceReader,
                    basis.connection(),
                    basis.selection(),
                    ordinaryEvidenceRequest());
            evidence = attempt.evidence();
            if (evidence == null) {
                return invalid(basisStillCurrent(basis)
                        ? attempt.failureReason()
                        : reasonForChangedBasis(expected, basis));
            }
        }

        FrozenAuthority post = readAuthority(authoritySupplier);
        TargetChangeReason postReason = validateAuthority(expected, basis, post);
        if (postReason != null) return invalid(postReason);

        if (!basisStillCurrent(basis)) {
            return invalid(reasonForChangedBasis(expected, basis));
        }

        EditorSessionSnapshot current = currentSnapshot(basis, evidence);
        if (current == null) return invalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        SessionValidationResult domainResult = SessionValidator.validate(expected, current);
        if (domainResult instanceof SessionValidationResult.Invalid invalid) {
            return invalid(invalid.reason());
        }
        return new Validated(
                new HostLease(
                        basis.epoch(),
                        basis.token(),
                        basis.authorityRevision(),
                        basis.editor(),
                        basis.selection()),
                new ValidatedEvidence(
                        evidence.selection(),
                        evidence.selected(),
                        evidence.before(),
                        evidence.after()));
    }

    /**
     * Validates one exact CommitRecord Undo state using a full committed suffix plus bounded
     * context, all within one live-authority bracket.
     *
     * <p>This is deliberately separate from normal snapshot evidence: a committed value may be
     * 40,000 code points, while ordinary surrounding evidence is capped at 800 UTF-16 units. The
     * returned lease is still identity-only and must be consumed synchronously by the transaction.
     */
    UndoValidationResult validateUndoState(
            EditorSessionSnapshot expected,
            CommitRecord record,
            UndoProofState proofState,
            LiveAuthoritySupplier authoritySupplier,
            UndoEvidenceReader evidenceReader) {
        requireOwnerThread();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(proofState, "proofState");
        Objects.requireNonNull(authoritySupplier, "authoritySupplier");
        Objects.requireNonNull(evidenceReader, "evidenceReader");
        if (closed) return undoInvalid(TargetChangeReason.NO_ACTIVE_SESSION);

        ValidationBasis basis = currentValidationBasis(expected);
        if (basis == null) return undoInvalid(preflightReason(expected));

        FrozenAuthority pre = readAuthority(authoritySupplier);
        TargetChangeReason preReason = validateAuthority(expected, basis, pre);
        if (preReason != null) return undoInvalid(preReason);
        if (basis.editor().sensitive() || expected.sensitive()) {
            return undoInvalid(TargetChangeReason.SECURITY_STATE_CHANGED);
        }

        UndoEvidenceRequest request = undoEvidenceRequest(record, proofState);
        if (request == null) return undoInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        UndoEvidenceAttempt attempt =
                readUndoEvidence(evidenceReader, basis.connection(), basis.selection(), request);
        MaterializedUndoEvidence evidence = attempt.evidence();
        if (evidence == null) {
            return undoInvalid(basisStillCurrent(basis)
                    ? attempt.failureReason()
                    : reasonForChangedBasis(expected, basis));
        }

        FrozenAuthority post = readAuthority(authoritySupplier);
        TargetChangeReason postReason = validateAuthority(expected, basis, post);
        if (postReason != null) return undoInvalid(postReason);
        if (!basisStillCurrent(basis)) {
            return undoInvalid(reasonForChangedBasis(expected, basis));
        }

        MaterializedEvidence snapshotEvidence = new MaterializedEvidence(
                evidence.selection(),
                evidence.selected(),
                snapshotBeforeTail(evidence.before()),
                snapshotAfterHead(evidence.after()));
        EditorSessionSnapshot current = currentSnapshot(basis, snapshotEvidence);
        if (current == null) return undoInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        SessionValidationResult domainResult = SessionValidator.validate(expected, current);
        if (domainResult instanceof SessionValidationResult.Invalid invalid) {
            return undoInvalid(invalid.reason());
        }

        if (proofState == UndoProofState.COMMITTED) {
            TargetChangeReason relationFailure = validateCommittedRelation(record, evidence);
            if (relationFailure != null) return undoInvalid(relationFailure);
        }
        return new UndoValidated(new HostLease(
                basis.epoch(),
                basis.token(),
                basis.authorityRevision(),
                basis.editor(),
                basis.selection()));
    }

    /**
     * Verifies the post-Undo original state while allowing the one expected selection transition.
     * Identity, metadata and security must still belong to the record's exact original session.
     */
    UndoValidationResult validateUndoOriginalState(
            CommitRecord record,
            LiveAuthoritySupplier authoritySupplier,
            UndoEvidenceReader evidenceReader) {
        requireOwnerThread();
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(authoritySupplier, "authoritySupplier");
        Objects.requireNonNull(evidenceReader, "evidenceReader");
        EditorSessionSnapshot origin = record.originalSession();
        if (closed) return undoInvalid(TargetChangeReason.NO_ACTIVE_SESSION);

        ValidationBasis basis = currentUndoOutcomeBasis(origin);
        if (basis == null) return undoInvalid(undoOutcomePreflightReason(record));
        FrozenAuthority pre = readAuthority(authoritySupplier);
        TargetChangeReason preReason = validateAuthority(origin, basis, pre);
        if (preReason != null) return undoInvalid(preReason);

        UndoEvidenceRequest request = undoEvidenceRequest(record, UndoProofState.ORIGINAL);
        if (request == null) return undoInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        UndoEvidenceAttempt attempt =
                readUndoEvidence(evidenceReader, basis.connection(), basis.selection(), request);
        MaterializedUndoEvidence evidence = attempt.evidence();
        if (evidence == null) {
            return undoInvalid(basisStillCurrent(basis)
                    ? attempt.failureReason()
                    : reasonForChangedBasis(origin, basis));
        }
        FrozenAuthority post = readAuthority(authoritySupplier);
        TargetChangeReason postReason = validateAuthority(origin, basis, post);
        if (postReason != null) return undoInvalid(postReason);
        if (!basisStillCurrent(basis)) {
            return undoInvalid(reasonForChangedBasis(origin, basis));
        }

        EditorSessionSnapshot current = currentSnapshot(
                basis,
                new MaterializedEvidence(
                        evidence.selection(),
                        evidence.selected(),
                        snapshotBeforeTail(evidence.before()),
                        snapshotAfterHead(evidence.after())));
        if (current == null) return undoInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        SessionValidationResult domainResult = SessionValidator.validate(origin, current);
        if (domainResult instanceof SessionValidationResult.Invalid invalid) {
            return undoInvalid(invalid.reason());
        }
        return new UndoValidated(new HostLease(
                basis.epoch(),
                basis.token(),
                basis.authorityRevision(),
                basis.editor(),
                basis.selection()));
    }

    /**
     * Captures process-local authority immediately before one exact-ID replacement or rollback
     * mutator.
     *
     * <p>The returned observation is not a write capability. It is consumed only by
     * {@link #validateRawTransitionState} after that mutator returns, and it never carries text or
     * an {@link InputConnection}.
     */
    RawTransition prepareRawTransition(
            CommitRecord record, RawProofState fromState, RawProofState targetState) {
        requireOwnerThread();
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(fromState, "fromState");
        Objects.requireNonNull(targetState, "targetState");
        if ((fromState != RawProofState.COMMITTED && fromState != RawProofState.ORIGINAL)
                || (fromState == RawProofState.COMMITTED
                        && targetState == RawProofState.COMMITTED)) return null;
        if (closed || activeEditor == null || activeEditor.sensitive()) return null;

        EditorSessionSnapshot origin = record.originalSession();
        if (epoch != origin.epoch()
                || connections.currentToken() != origin.connectionToken()
                || connections.resolve(origin.connectionToken()) == null
                || !snapshotMetadataMatches(origin, activeEditor)
                || !selection.isKnown()
                || !selection.isCollapsed()
                || !origin.selection().isKnown()) {
            return null;
        }

        int committedCursor = replacementCursor(origin, record.insertedText());
        String fromText = replacementText(record, fromState);
        String targetText = replacementText(record, targetState);
        int fromCursor = fromState == RawProofState.COMMITTED
                ? committedCursor
                : replacementCursor(origin, fromText);
        int targetCursor = replacementCursor(origin, targetText);
        if (committedCursor < 0 || fromCursor < 0 || targetCursor < 0
                || fromText == null || targetText == null) {
            return null;
        }

        if (selection.start() != fromCursor
                && !(fromState == RawProofState.ORIGINAL
                        && selection.start() == committedCursor)) {
            return null;
        }
        TextFingerprint replacementFingerprint;
        try {
            replacementFingerprint = Sha256EditorTextHasher.INSTANCE.committedText(targetText);
        } catch (RuntimeException unavailable) {
            return null;
        }
        return new RawTransition(
                rawTransitionOwnerStamp,
                epoch,
                origin.connectionToken(),
                authorityRevision,
                activeEditor,
                selection,
                new TextRange(fromCursor, fromCursor),
                new TextRange(targetCursor, targetCursor),
                targetState,
                origin,
                record.insertedTextFingerprint(),
                replacementFingerprint);
    }

    /**
     * Proves the exact state reached by one Raw Restore mutator before another write may occur.
     *
     * <p>The proof accepts a delayed framework selection callback only when it is the token's one
     * expected transition. Full text evidence is still bracketed by fresh authority observations.
     */
    UndoValidationResult validateRawTransitionState(
            RawTransition transition,
            CommitRecord record,
            LiveAuthoritySupplier authoritySupplier,
            UndoEvidenceReader evidenceReader) {
        requireOwnerThread();
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(authoritySupplier, "authoritySupplier");
        Objects.requireNonNull(evidenceReader, "evidenceReader");
        if (transition.ownerStamp != rawTransitionOwnerStamp) {
            return undoInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (!transitionMatchesRecord(transition, record)) {
            return undoInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (transition.claimed) {
            return undoInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        transition.claimed = true;

        ValidationBasis basis = rawTransitionBasis(transition);
        if (basis == null) return undoInvalid(rawTransitionInvalidReason(transition));

        EditorSessionSnapshot origin = record.originalSession();
        FrozenAuthority pre = readAuthority(authoritySupplier);
        TargetChangeReason preReason = validateRawTransitionAuthority(origin, basis, pre);
        if (preReason != null) return undoInvalid(preReason);

        UndoEvidenceRequest request = rawEvidenceRequest(record, transition.targetState);
        if (request == null) return undoInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        UndoEvidenceAttempt attempt =
                readUndoEvidence(
                        evidenceReader,
                        basis.connection(),
                        transition.targetSelection,
                        request);
        MaterializedUndoEvidence evidence = attempt.evidence();
        if (evidence == null) {
            TargetChangeReason evidenceReason = attempt.failureReason();
            if (evidenceReason == TargetChangeReason.SELECTION_CHANGED
                    && attempt.observedSelection().equals(transition.provenFromSelection)) {
                // A mutator may honestly return false/throw without changing the exact pre-state.
                // That is not target invalidation, but it still cannot satisfy the intended proof.
                evidenceReason = TargetChangeReason.EVIDENCE_UNAVAILABLE;
            }
            return undoInvalid(basisStillCurrent(basis)
                    ? evidenceReason
                    : rawTransitionInvalidReason(transition));
        }

        FrozenAuthority post = readAuthority(authoritySupplier);
        TargetChangeReason postReason = validateRawTransitionAuthority(origin, basis, post);
        if (postReason != null) return undoInvalid(postReason);
        if (!basisStillCurrent(basis)) {
            return undoInvalid(rawTransitionInvalidReason(transition));
        }

        TargetChangeReason relationFailure = validateRawRelation(
                record, transition.targetState, evidence);
        if (relationFailure != null) return undoInvalid(relationFailure);
        return new UndoValidated(new HostLease(
                basis.epoch(),
                basis.token(),
                basis.authorityRevision(),
                basis.editor(),
                basis.selection()));
    }

    /** Captures a one-shot, plaintext-free outcome proof immediately before ReplaceSelection. */
    ReplaceTransition prepareReplaceTransition(
            EditorSessionSnapshot expected,
            EditorOperation.ReplaceSelection operation,
            ReplaceProofState targetState) {
        requireOwnerThread();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(targetState, "targetState");
        if (closed || activeEditor == null || activeEditor.sensitive()) return null;
        TextRange original = expected.selection();
        if (!original.hasSelection()
                || !original.equals(operation.expectedSelection())
                || !original.equals(selection)
                || !operation.expectedTextHash().securelyMatches(
                        expected.selectedTextFingerprint())
                || epoch != expected.epoch()
                || connections.currentToken() != expected.connectionToken()
                || connections.resolve(expected.connectionToken()) == null
                || !snapshotMetadataMatches(expected, activeEditor)) {
            return null;
        }

        TextRange intended;
        TextFingerprint replacementFingerprint;
        try {
            int cursor = Math.addExact(
                    Math.min(original.start(), original.end()), operation.text().length());
            intended = new TextRange(cursor, cursor);
            replacementFingerprint = Sha256EditorTextHasher.INSTANCE.committedText(
                    operation.text());
        } catch (RuntimeException unavailable) {
            return null;
        }
        TextRange target = targetState == ReplaceProofState.ORIGINAL ? original : intended;
        return new ReplaceTransition(
                replaceTransitionOwnerStamp,
                epoch,
                expected.connectionToken(),
                authorityRevision,
                activeEditor,
                selection,
                original,
                target,
                targetState,
                operation.text().length(),
                expected.selectedTextFingerprint(),
                expected.beforeFingerprint(),
                expected.afterFingerprint(),
                expected.contextFingerprint(),
                replacementFingerprint);
    }

    /**
     * Proves one ReplaceSelection outcome with live absolute selection and the complete replacement.
     *
     * <p>The intended-state request expands to replacement UTF-16 length plus bounded original
     * context, so a matching 64-code-point tail can never hide a corrupted long prefix.
     */
    ReplaceValidationResult validateReplaceTransitionState(
            ReplaceTransition transition,
            LiveAuthoritySupplier authoritySupplier,
            CurrentEvidenceReader evidenceReader) {
        requireOwnerThread();
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(authoritySupplier, "authoritySupplier");
        Objects.requireNonNull(evidenceReader, "evidenceReader");
        if (transition.ownerStamp != replaceTransitionOwnerStamp || transition.claimed) {
            return replaceInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        transition.claimed = true;

        ValidationBasis basis = replaceTransitionBasis(transition);
        if (basis == null) return replaceInvalid(replaceTransitionInvalidReason(transition));
        FrozenAuthority pre = readAuthority(authoritySupplier);
        TargetChangeReason preReason = validateReplaceTransitionAuthority(transition, basis, pre);
        if (preReason != null) return replaceInvalid(preReason);

        CurrentEvidenceRequest request = replaceEvidenceRequest(transition);
        if (request == null) return replaceInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        EvidenceAttempt attempt = readEvidence(
                evidenceReader, basis.connection(), transition.targetSelection, request);
        MaterializedEvidence evidence = attempt.evidence();
        if (evidence == null) {
            TargetChangeReason reason = attempt.failureReason();
            if (reason == TargetChangeReason.SELECTION_CHANGED
                    && replaceAlternateSelection(transition)
                            .equals(attempt.observedSelection())) {
                reason = TargetChangeReason.EVIDENCE_UNAVAILABLE;
            }
            return replaceInvalid(basisStillCurrent(basis)
                    ? reason
                    : replaceTransitionInvalidReason(transition));
        }

        FrozenAuthority post = readAuthority(authoritySupplier);
        ValidationBasis postBasis = replaceTransitionPostEvidenceBasis(transition, basis);
        if (postBasis == null) {
            return replaceInvalid(replaceTransitionInvalidReason(transition));
        }
        TargetChangeReason postReason = validateReplaceTransitionAuthority(
                transition, postBasis, post);
        if (postReason != null) return replaceInvalid(postReason);
        if (!basisStillCurrent(postBasis)) {
            return replaceInvalid(replaceTransitionInvalidReason(transition));
        }

        TargetChangeReason relation = validateReplaceRelation(transition, evidence);
        return relation == null ? new ReplaceValidated() : replaceInvalid(relation);
    }

    /** Drops the process-local capability without advancing the legacy lifecycle epoch. */
    public void close() {
        requireOwnerThread();
        if (closed) return;
        transactions.revokeSessionState();
        advanceAuthorityRevision();
        clearActive();
        closed = true;
    }

    @Override
    public String toString() {
        return "EditorSessionManager{<redacted>}";
    }

    long currentEpoch() {
        requireOwnerThread();
        return epoch;
    }

    /** Fails fast before an editor transaction evaluates inputs or invokes any callback. */
    void requireOwnerThreadForHost() {
        requireOwnerThread();
        requireOpen();
    }

    /** Lifecycle-owned state cleanup may run while close is transitioning this manager. */
    void requireOwnerThreadForLifecycle() {
        requireOwnerThread();
    }

    /**
     * Applies one bounded key-text insertion through the sole transaction writer.
     *
     * <p>The Android host is consulted afresh around every evidence read. It is used only during
     * this synchronous call and is never retained. A selected range becomes an exact
     * {@link EditorOperation.ReplaceSelection}; a collapsed range becomes an
     * {@link EditorOperation.InsertText}.
     */
    public EditorTransactionResult insertKeyboardText(
            KeyboardHost host, EditorSessionSnapshot expected, String text) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        return insertKeyboardText(
                expected,
                text,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /** Applies one backward-delete key without exposing the generic operation surface. */
    public EditorTransactionResult deleteKeyboardBackward(
            KeyboardHost host, EditorSessionSnapshot expected) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        return deleteKeyboardBackward(
                expected,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /**
     * Applies the semantic editor action advertised by the field, or inserts a newline when the
     * field exposes no allowlisted action. No KeyEvent or indirect InputMethodService writer is
     * used.
     */
    public EditorTransactionResult performKeyboardEnter(
            KeyboardHost host, EditorSessionSnapshot expected) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        return performKeyboardEnter(
                expected,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /** Applies one monotonic Voice composition revision through the sole transaction writer. */
    public EditorTransactionResult setVoiceComposition(
            KeyboardHost host,
            EditorSessionSnapshot expected,
            String text,
            long revision) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        EditorOperation operation;
        try {
            operation = new EditorOperation.SetComposition(
                    text, CompositionOwner.VOICE, revision, OperationSource.VOICE);
        } catch (RuntimeException invalidOperation) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        return transactions.apply(
                expected,
                operation,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /** Applies one monotonic Rime preedit revision through the sole transaction writer. */
    public EditorTransactionResult setRimeComposition(
            KeyboardHost host,
            EditorSessionSnapshot expected,
            String text,
            long revision) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        EditorOperation operation;
        try {
            operation = new EditorOperation.SetComposition(
                    text, CompositionOwner.RIME, revision, OperationSource.RIME);
        } catch (RuntimeException invalidOperation) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        return transactions.apply(
                expected,
                operation,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /** Finishes only the exact Rime preedit revision; no current-cursor fallback exists. */
    public EditorTransactionResult finishRimeComposition(
            KeyboardHost host,
            EditorSessionSnapshot expected,
            long expectedRevision) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        EditorOperation operation;
        try {
            operation = new EditorOperation.CommitComposition(
                    CompositionOwner.RIME, expectedRevision, OperationSource.RIME);
        } catch (RuntimeException invalidOperation) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        return transactions.apply(
                expected,
                operation,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /**
     * Finalizes the exact Voice composition revision and atomically returns its commit record.
     * The raw transcript is data only; it never grants editor authority.
     */
    public TransactionReceipt commitVoiceComposition(
            KeyboardHost host,
            EditorSessionSnapshot expected,
            long expectedRevision,
            CommitRecord.RawTranscript rawTranscript) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(rawTranscript, "rawTranscript");
        EditorOperation operation;
        try {
            operation = new EditorOperation.CommitComposition(
                    CompositionOwner.VOICE, expectedRevision, OperationSource.VOICE);
        } catch (RuntimeException invalidOperation) {
            return new TransactionReceipt.WithoutCommit(
                    new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED));
        }
        return transactions.applyWithReceipt(
                expected,
                operation,
                new CommitRecordRequest.Requested(rawTranscript),
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /** Finishes a Voice composition without creating a commit record. */
    public EditorTransactionResult finishVoiceComposition(
            KeyboardHost host,
            EditorSessionSnapshot expected,
            long expectedRevision) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        EditorOperation operation;
        try {
            operation = new EditorOperation.CommitComposition(
                    CompositionOwner.VOICE, expectedRevision, OperationSource.VOICE);
        } catch (RuntimeException invalidOperation) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        return transactions.apply(
                expected,
                operation,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /**
     * Applies a final Voice result at the exact captured selection and returns its same-stack
     * receipt. A selected range is represented as ReplaceSelection; a caret as InsertText.
     */
    public TransactionReceipt commitVoiceText(
            KeyboardHost host,
            EditorSessionSnapshot expected,
            String text,
            CommitRecord.RawTranscript rawTranscript) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(rawTranscript, "rawTranscript");
        EditorOperation operation;
        try {
            if (expected.selection().hasSelection()) {
                operation = new EditorOperation.ReplaceSelection(
                        expected.selection(),
                        expected.selectedTextFingerprint(),
                        text,
                        OperationSource.VOICE);
            } else if (expected.selection().isCollapsed()) {
                operation = new EditorOperation.InsertText(text, OperationSource.VOICE);
            } else {
                return new TransactionReceipt.WithoutCommit(
                        new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED));
            }
        } catch (RuntimeException invalidOperation) {
            return new TransactionReceipt.WithoutCommit(
                    new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED));
        }
        return transactions.applyWithReceipt(
                expected,
                operation,
                new CommitRecordRequest.Requested(rawTranscript),
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardEvidence);
    }

    /** Exact-ID Voice Undo; a public receipt or caller-created record is never accepted. */
    public EditorTransactionResult undoVoiceCommit(
            KeyboardHost host,
            EditorSessionSnapshot expectedCurrent,
            String commitId) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        return transactions.undoCommit(
                commitId,
                expectedCurrent,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardUndoEvidence);
    }

    /** Exact-ID Voice Raw restore; replacement plaintext comes only from the resolved record. */
    public EditorTransactionResult restoreRawVoiceCommit(
            KeyboardHost host,
            EditorSessionSnapshot expectedCurrent,
            String commitId) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(host, "host");
        return transactions.restoreRawCommit(
                commitId,
                expectedCurrent,
                () -> keyboardAuthority(host),
                EditorSessionManager::readKeyboardUndoEvidence);
    }

    EditorTransactionResult insertKeyboardText(
            EditorSessionSnapshot expected,
            String text,
            LiveAuthoritySupplier authoritySupplier,
            CurrentEvidenceReader evidenceReader) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(expected, "expected");
        EditorOperation operation;
        try {
            operation = keyboardTextOperation(expected, text);
        } catch (RuntimeException invalidOperation) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        if (operation == null) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        return transactions.apply(expected, operation, authoritySupplier, evidenceReader);
    }

    EditorTransactionResult deleteKeyboardBackward(
            EditorSessionSnapshot expected,
            LiveAuthoritySupplier authoritySupplier,
            CurrentEvidenceReader evidenceReader) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(expected, "expected");
        EditorOperation operation;
        try {
            if (expected.selection().hasSelection()) {
                operation = new EditorOperation.ReplaceSelection(
                        expected.selection(), expected.selectedTextFingerprint(), "",
                        OperationSource.LATIN);
            } else if (expected.selection().isCollapsed()) {
                operation = new EditorOperation.DeleteBeforeCursor(1, OperationSource.LATIN);
            } else {
                return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
            }
        } catch (RuntimeException invalidOperation) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        return transactions.apply(expected, operation, authoritySupplier, evidenceReader);
    }

    EditorTransactionResult performKeyboardEnter(
            EditorSessionSnapshot expected,
            LiveAuthoritySupplier authoritySupplier,
            CurrentEvidenceReader evidenceReader) {
        requireOwnerThreadForHost();
        Objects.requireNonNull(expected, "expected");
        EditorOperation operation;
        try {
            EditorAction action = keyboardAction(expected.imeOptions());
            operation = action == null
                    ? keyboardTextOperation(expected, "\n")
                    : new EditorOperation.PerformEditorAction(action, OperationSource.LATIN);
        } catch (RuntimeException invalidOperation) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        if (operation == null) {
            return new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED);
        }
        return transactions.apply(expected, operation, authoritySupplier, evidenceReader);
    }

    EditorTransactionResult apply(
            EditorSessionSnapshot expected,
            EditorOperation operation,
            LiveAuthoritySupplier authoritySupplier,
            CurrentEvidenceReader evidenceReader) {
        return transactions.apply(expected, operation, authoritySupplier, evidenceReader);
    }

    TransactionReceipt applyWithReceipt(
            EditorSessionSnapshot expected,
            EditorOperation operation,
            CommitRecordRequest commitRequest,
            LiveAuthoritySupplier authoritySupplier,
            CurrentEvidenceReader evidenceReader) {
        return transactions.applyWithReceipt(
                expected, operation, commitRequest, authoritySupplier, evidenceReader);
    }

    EditorTransactionResult undoCommit(
            String commitId,
            EditorSessionSnapshot expectedCurrent,
            LiveAuthoritySupplier authoritySupplier,
            UndoEvidenceReader evidenceReader) {
        return transactions.undoCommit(
                commitId, expectedCurrent, authoritySupplier, evidenceReader);
    }

    EditorTransactionResult restoreRawCommit(
            String commitId,
            EditorSessionSnapshot expectedCurrent,
            LiveAuthoritySupplier authoritySupplier,
            UndoEvidenceReader evidenceReader) {
        return transactions.restoreRawCommit(
                commitId, expectedCurrent, authoritySupplier, evidenceReader);
    }

    Optional<CommitRecord> resolveCommitRecord(
            String commitId, EditorSessionSnapshot currentSession) {
        return transactions.resolveCommitRecord(commitId, currentSession);
    }

    Optional<CommitRecord> consumeCommitRecord(
            String commitId, EditorSessionSnapshot currentSession) {
        return transactions.consumeCommitRecord(commitId, currentSession);
    }

    int commitRecordCountForTest() {
        return transactions.commitRecordCountForTest();
    }

    private ValidationBasis currentValidationBasis(EditorSessionSnapshot expected) {
        if (activeEditor == null) return null;
        if (expected.epoch() != epoch) return null;
        long currentToken = connections.currentToken();
        if (currentToken <= InputConnectionRegistry.NO_CONNECTION_TOKEN) return null;
        if (currentToken != expected.connectionToken()) return null;
        InputConnection connection = connections.resolve(expected.connectionToken());
        if (connection == null) return null;
        return new ValidationBasis(
                epoch,
                expected.connectionToken(),
                authorityRevision,
                connection,
                activeEditor,
                selection);
    }

    private ValidationBasis currentUndoOutcomeBasis(EditorSessionSnapshot origin) {
        if (activeEditor == null) return null;
        if (origin.epoch() != epoch) return null;
        long currentToken = connections.currentToken();
        if (currentToken <= InputConnectionRegistry.NO_CONNECTION_TOKEN
                || currentToken != origin.connectionToken()) return null;
        InputConnection connection = connections.resolve(origin.connectionToken());
        if (connection == null) return null;
        TextRange originalSelection = origin.selection();
        if (!originalSelection.isKnown()
                || !originalSelection.isCollapsed()
                || !originalSelection.equals(selection)) return null;
        return new ValidationBasis(
                epoch,
                origin.connectionToken(),
                authorityRevision,
                connection,
                activeEditor,
                selection);
    }

    private ValidationBasis rawTransitionBasis(RawTransition transition) {
        if (closed
                || activeEditor == null
                || !transition.expectedEditor.equals(activeEditor)) return null;
        if (epoch != transition.expectedEpoch
                || connections.currentToken() != transition.expectedToken) return null;
        InputConnection connection = connections.resolve(transition.expectedToken);
        if (connection == null) return null;

        boolean unchanged = authorityRevision == transition.expectedAuthorityRevision
                && selection.equals(transition.managerSelection);
        boolean expectedTransition = transition.expectedAuthorityRevision != Long.MAX_VALUE
                && authorityRevision == transition.expectedAuthorityRevision + 1L
                && selection.equals(transition.targetSelection);
        if (!unchanged && !expectedTransition) return null;
        return new ValidationBasis(
                epoch,
                transition.expectedToken,
                authorityRevision,
                connection,
                activeEditor,
                selection);
    }

    /**
     * Accepts the single delayed framework callback which can arrive inside the evidence bracket.
     * Any second callback, intermediate selection or authority change remains fail closed.
     */
    private ValidationBasis replaceTransitionPostEvidenceBasis(
            ReplaceTransition transition, ValidationBasis initialBasis) {
        if (basisStillCurrent(initialBasis)) return initialBasis;
        if (transition.targetState != ReplaceProofState.INTENDED
                || initialBasis.authorityRevision() != transition.expectedAuthorityRevision
                || !initialBasis.selection().equals(transition.managerSelection)
                || transition.expectedAuthorityRevision == Long.MAX_VALUE
                || closed
                || epoch != transition.expectedEpoch
                || authorityRevision != transition.expectedAuthorityRevision + 1L
                || !transition.expectedEditor.equals(activeEditor)
                || !selection.equals(transition.targetSelection)
                || connections.currentToken() != transition.expectedToken) {
            return null;
        }
        InputConnection connection = connections.resolve(transition.expectedToken);
        if (connection == null || connection != initialBasis.connection()) return null;
        return new ValidationBasis(
                epoch,
                transition.expectedToken,
                authorityRevision,
                connection,
                activeEditor,
                selection);
    }

    private ValidationBasis replaceTransitionBasis(ReplaceTransition transition) {
        if (closed
                || activeEditor == null
                || !transition.expectedEditor.equals(activeEditor)) return null;
        if (epoch != transition.expectedEpoch
                || connections.currentToken() != transition.expectedToken) return null;
        InputConnection connection = connections.resolve(transition.expectedToken);
        if (connection == null) return null;

        boolean unchanged = authorityRevision == transition.expectedAuthorityRevision
                && selection.equals(transition.managerSelection);
        boolean expectedTransition = transition.targetState == ReplaceProofState.INTENDED
                && transition.expectedAuthorityRevision != Long.MAX_VALUE
                && authorityRevision == transition.expectedAuthorityRevision + 1L
                && selection.equals(transition.targetSelection);
        if (!unchanged && !expectedTransition) return null;
        return new ValidationBasis(
                epoch,
                transition.expectedToken,
                authorityRevision,
                connection,
                activeEditor,
                selection);
    }

    private TargetChangeReason preflightReason(EditorSessionSnapshot expected) {
        if (activeEditor == null) return TargetChangeReason.NO_ACTIVE_SESSION;
        long token = connections.currentToken();
        if (token <= InputConnectionRegistry.NO_CONNECTION_TOKEN) {
            return TargetChangeReason.SESSION_REVOKED;
        }
        if (expected.epoch() != epoch) return TargetChangeReason.EPOCH_CHANGED;
        if (token != expected.connectionToken()) return TargetChangeReason.CONNECTION_CHANGED;
        if (connections.resolve(expected.connectionToken()) == null) {
            return TargetChangeReason.SESSION_REVOKED;
        }
        return TargetChangeReason.EVIDENCE_UNAVAILABLE;
    }

    private TargetChangeReason undoOutcomePreflightReason(CommitRecord record) {
        EditorSessionSnapshot origin = record.originalSession();
        TargetChangeReason identityFailure = preflightReason(origin);
        if (identityFailure != TargetChangeReason.EVIDENCE_UNAVAILABLE) {
            return identityFailure;
        }
        if (!origin.selection().isKnown() || !selection.isKnown()) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }
        if (origin.selection().equals(selection)) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }
        // A false/throwing delete may legitimately leave the exact committed cursor unchanged;
        // that proves neither target invalidation nor the full editor outcome. Any third
        // selection is a real target change and receives the stronger stable classification.
        try {
            int committedCursor = Math.addExact(
                    origin.selection().start(), record.insertedText().length());
            if (selection.isCollapsed() && selection.start() == committedCursor) {
                return TargetChangeReason.EVIDENCE_UNAVAILABLE;
            }
        } catch (ArithmeticException unavailable) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }
        return TargetChangeReason.SELECTION_CHANGED;
    }

    private TargetChangeReason rawTransitionInvalidReason(RawTransition transition) {
        if (closed || activeEditor == null) return TargetChangeReason.NO_ACTIVE_SESSION;
        if (epoch != transition.expectedEpoch) return TargetChangeReason.EPOCH_CHANGED;
        long token = connections.currentToken();
        if (token <= InputConnectionRegistry.NO_CONNECTION_TOKEN) {
            return TargetChangeReason.SESSION_REVOKED;
        }
        if (token != transition.expectedToken) return TargetChangeReason.CONNECTION_CHANGED;
        if (connections.resolve(transition.expectedToken) == null) {
            return TargetChangeReason.SESSION_REVOKED;
        }
        if (!transition.expectedEditor.equals(activeEditor)) {
            if (transition.expectedEditor.sensitive() != activeEditor.sensitive()
                    || transition.expectedEditor.learningAllowed()
                            != activeEditor.learningAllowed()) {
                return TargetChangeReason.SECURITY_STATE_CHANGED;
            }
            return TargetChangeReason.EDITOR_METADATA_CHANGED;
        }
        return TargetChangeReason.SELECTION_CHANGED;
    }

    private TargetChangeReason replaceTransitionInvalidReason(ReplaceTransition transition) {
        if (closed || activeEditor == null) return TargetChangeReason.NO_ACTIVE_SESSION;
        if (epoch != transition.expectedEpoch) return TargetChangeReason.EPOCH_CHANGED;
        long token = connections.currentToken();
        if (token <= InputConnectionRegistry.NO_CONNECTION_TOKEN) {
            return TargetChangeReason.SESSION_REVOKED;
        }
        if (token != transition.expectedToken) return TargetChangeReason.CONNECTION_CHANGED;
        if (connections.resolve(transition.expectedToken) == null) {
            return TargetChangeReason.SESSION_REVOKED;
        }
        if (!transition.expectedEditor.equals(activeEditor)) {
            if (transition.expectedEditor.sensitive() != activeEditor.sensitive()
                    || transition.expectedEditor.learningAllowed()
                            != activeEditor.learningAllowed()) {
                return TargetChangeReason.SECURITY_STATE_CHANGED;
            }
            return TargetChangeReason.EDITOR_METADATA_CHANGED;
        }
        return TargetChangeReason.SELECTION_CHANGED;
    }

    private static boolean transitionMatchesRecord(
            RawTransition transition, CommitRecord record) {
        if (!transition.expectedOrigin.equals(record.originalSession())
                || !transition.expectedInsertedFingerprint.securelyMatches(
                        record.insertedTextFingerprint())) {
            return false;
        }
        String replacement = replacementText(record, transition.targetState);
        if (replacement == null) return false;
        try {
            return transition.expectedReplacementFingerprint.securelyMatches(
                    Sha256EditorTextHasher.INSTANCE.committedText(replacement));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private TargetChangeReason validateAuthority(
            EditorSessionSnapshot expected, ValidationBasis basis, FrozenAuthority authority) {
        if (!basisStillCurrent(basis)) return reasonForChangedBasis(expected, basis);
        if (authority == null) return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        if (authority.connection() != basis.connection()) return TargetChangeReason.CONNECTION_CHANGED;
        ActiveEditor editor = basis.editor();
        EditorDescriptor descriptor = authority.descriptor();
        if (!securityMatches(editor, descriptor)
                || expected.sensitive() != editor.sensitive()
                || expected.learningAllowed() != editor.learningAllowed()) {
            return TargetChangeReason.SECURITY_STATE_CHANGED;
        }
        if (!metadataMatches(editor, descriptor)
                || !snapshotMetadataMatches(expected, editor)) {
            return TargetChangeReason.EDITOR_METADATA_CHANGED;
        }
        if (!expected.selection().isKnown() || !basis.selection().isKnown()) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }
        if (!expected.selection().equals(basis.selection())) {
            return TargetChangeReason.SELECTION_CHANGED;
        }
        return null;
    }

    private TargetChangeReason validateRawTransitionAuthority(
            EditorSessionSnapshot origin,
            ValidationBasis basis,
            FrozenAuthority authority) {
        if (!basisStillCurrent(basis)) {
            return rawTransitionBasisReason(origin, basis);
        }
        if (authority == null) return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        if (authority.connection() != basis.connection()) {
            return TargetChangeReason.CONNECTION_CHANGED;
        }
        ActiveEditor editor = basis.editor();
        EditorDescriptor descriptor = authority.descriptor();
        if (!securityMatches(editor, descriptor)
                || origin.sensitive() != editor.sensitive()
                || origin.learningAllowed() != editor.learningAllowed()) {
            return TargetChangeReason.SECURITY_STATE_CHANGED;
        }
        if (!metadataMatches(editor, descriptor)
                || !snapshotMetadataMatches(origin, editor)) {
            return TargetChangeReason.EDITOR_METADATA_CHANGED;
        }
        if (!basis.selection().isKnown() || !basis.selection().isCollapsed()) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }
        return null;
    }

    private TargetChangeReason validateReplaceTransitionAuthority(
            ReplaceTransition transition,
            ValidationBasis basis,
            FrozenAuthority authority) {
        if (!basisStillCurrent(basis)) return replaceTransitionInvalidReason(transition);
        if (authority == null) return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        if (authority.connection() != basis.connection()) {
            return TargetChangeReason.CONNECTION_CHANGED;
        }
        ActiveEditor editor = basis.editor();
        EditorDescriptor descriptor = authority.descriptor();
        if (!securityMatches(editor, descriptor) || editor.sensitive()) {
            return TargetChangeReason.SECURITY_STATE_CHANGED;
        }
        if (!metadataMatches(editor, descriptor)) {
            return TargetChangeReason.EDITOR_METADATA_CHANGED;
        }
        return null;
    }

    private TargetChangeReason rawTransitionBasisReason(
            EditorSessionSnapshot origin, ValidationBasis basis) {
        if (closed || activeEditor == null) return TargetChangeReason.NO_ACTIVE_SESSION;
        if (epoch != basis.epoch() || epoch != origin.epoch()) {
            return TargetChangeReason.EPOCH_CHANGED;
        }
        long token = connections.currentToken();
        if (token <= InputConnectionRegistry.NO_CONNECTION_TOKEN) {
            return TargetChangeReason.SESSION_REVOKED;
        }
        if (token != basis.token() || connections.resolve(basis.token()) != basis.connection()) {
            return TargetChangeReason.CONNECTION_CHANGED;
        }
        if (!basis.editor().equals(activeEditor)) {
            if (basis.editor().sensitive() != activeEditor.sensitive()
                    || basis.editor().learningAllowed() != activeEditor.learningAllowed()) {
                return TargetChangeReason.SECURITY_STATE_CHANGED;
            }
            return TargetChangeReason.EDITOR_METADATA_CHANGED;
        }
        return TargetChangeReason.SELECTION_CHANGED;
    }

    private boolean basisStillCurrent(ValidationBasis basis) {
        return !closed
                && epoch == basis.epoch()
                && authorityRevision == basis.authorityRevision()
                && basis.editor().equals(activeEditor)
                && basis.selection().equals(selection)
                && connections.currentToken() == basis.token()
                && connections.resolve(basis.token()) == basis.connection();
    }

    private TargetChangeReason reasonForChangedBasis(
            EditorSessionSnapshot expected, ValidationBasis basis) {
        if (closed || activeEditor == null) return TargetChangeReason.NO_ACTIVE_SESSION;
        if (epoch != basis.epoch() || epoch != expected.epoch()) {
            return TargetChangeReason.EPOCH_CHANGED;
        }
        long token = connections.currentToken();
        if (token <= InputConnectionRegistry.NO_CONNECTION_TOKEN) {
            return TargetChangeReason.SESSION_REVOKED;
        }
        if (token != basis.token() || connections.resolve(basis.token()) != basis.connection()) {
            return TargetChangeReason.CONNECTION_CHANGED;
        }
        if (!basis.editor().equals(activeEditor)) {
            if (basis.editor().sensitive() != activeEditor.sensitive()
                    || basis.editor().learningAllowed() != activeEditor.learningAllowed()) {
                return TargetChangeReason.SECURITY_STATE_CHANGED;
            }
            return TargetChangeReason.EDITOR_METADATA_CHANGED;
        }
        if (authorityRevision != basis.authorityRevision()
                || !basis.selection().equals(selection)) {
            return TargetChangeReason.SELECTION_CHANGED;
        }
        return TargetChangeReason.EVIDENCE_UNAVAILABLE;
    }

    private static FrozenAuthority readAuthority(LiveAuthoritySupplier supplier) {
        try {
            return freezeAuthority(supplier.get());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static FrozenAuthority freezeAuthority(LiveAuthority live) {
        if (live == null || live.editorInfo() == null) return null;
        try {
            EditorDescriptor descriptor = EditorDescriptor.copyOf(live.editorInfo());
            EditorSessionLimits.requirePackageName(descriptor.packageName());
            return new FrozenAuthority(descriptor, live.connection());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static CurrentEvidenceRequest ordinaryEvidenceRequest() {
        return new CurrentEvidenceRequest(
                EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS,
                EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
    }

    private static LiveAuthority keyboardAuthority(KeyboardHost host) {
        return new LiveAuthority(
                host.currentEditorInfo(), host.currentInputConnection());
    }

    private static EvidenceReadResult readKeyboardEvidence(
            InputConnection connection, CurrentEvidenceRequest request) {
        try {
            ExtractedTextRequest extractedRequest = new ExtractedTextRequest();
            extractedRequest.hintMaxChars = Math.addExact(
                    Math.addExact(request.beforeUtf16Units(), request.afterUtf16Units()),
                    EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2);
            ExtractedText beforeSelection = connection.getExtractedText(extractedRequest, 0);
            CharSequence selected = connection.getSelectedText(0);
            CharSequence before =
                    connection.getTextBeforeCursor(request.beforeUtf16Units(), 0);
            CharSequence after =
                    connection.getTextAfterCursor(request.afterUtf16Units(), 0);
            ExtractedText afterSelection = connection.getExtractedText(extractedRequest, 0);
            boolean selectionAvailable = beforeSelection != null
                    && afterSelection != null
                    && beforeSelection.selectionStart >= 0
                    && beforeSelection.selectionEnd >= 0
                    && beforeSelection.selectionStart == afterSelection.selectionStart
                    && beforeSelection.selectionEnd == afterSelection.selectionEnd;
            return new CurrentEvidence(
                    selectionAvailable,
                    selectionAvailable ? beforeSelection.selectionStart : -1,
                    selectionAvailable ? beforeSelection.selectionEnd : -1,
                    selected != null,
                    selected,
                    before != null,
                    before,
                    after != null,
                    after);
        } catch (RuntimeException unavailable) {
            return new EvidenceUnavailable();
        }
    }

    private static UndoEvidenceReadResult readKeyboardUndoEvidence(
            InputConnection connection, UndoEvidenceRequest request) {
        try {
            ExtractedTextRequest extractedRequest = new ExtractedTextRequest();
            extractedRequest.hintMaxChars = Math.addExact(
                    Math.addExact(request.beforeUtf16Units(), request.afterUtf16Units()),
                    EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2);
            ExtractedText beforeSelection = connection.getExtractedText(extractedRequest, 0);
            CharSequence selected = connection.getSelectedText(0);
            CharSequence before =
                    connection.getTextBeforeCursor(request.beforeUtf16Units(), 0);
            CharSequence after =
                    connection.getTextAfterCursor(request.afterUtf16Units(), 0);
            ExtractedText afterSelection = connection.getExtractedText(extractedRequest, 0);
            boolean selectionAvailable = beforeSelection != null
                    && afterSelection != null
                    && beforeSelection.selectionStart >= 0
                    && beforeSelection.selectionEnd >= 0
                    && beforeSelection.selectionStart == afterSelection.selectionStart
                    && beforeSelection.selectionEnd == afterSelection.selectionEnd;
            return new UndoEvidence(
                    selectionAvailable,
                    selectionAvailable ? beforeSelection.selectionStart : -1,
                    selectionAvailable ? beforeSelection.selectionEnd : -1,
                    selected != null,
                    selected,
                    before != null,
                    before,
                    after != null,
                    after);
        } catch (RuntimeException unavailable) {
            return new UndoEvidenceUnavailable();
        }
    }

    private static EditorOperation keyboardTextOperation(
            EditorSessionSnapshot expected, String text) {
        if (expected.selection().hasSelection()) {
            return new EditorOperation.ReplaceSelection(
                    expected.selection(), expected.selectedTextFingerprint(), text,
                    OperationSource.LATIN);
        }
        if (expected.selection().isCollapsed()) {
            return new EditorOperation.InsertText(text, OperationSource.LATIN);
        }
        return null;
    }

    private static EditorAction keyboardAction(int imeOptions) {
        if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null;
        return switch (imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO -> EditorAction.GO;
            case EditorInfo.IME_ACTION_SEARCH -> EditorAction.SEARCH;
            case EditorInfo.IME_ACTION_SEND -> EditorAction.SEND;
            case EditorInfo.IME_ACTION_NEXT -> EditorAction.NEXT;
            case EditorInfo.IME_ACTION_DONE -> EditorAction.DONE;
            case EditorInfo.IME_ACTION_PREVIOUS -> EditorAction.PREVIOUS;
            default -> null;
        };
    }

    private static EvidenceAttempt readEvidence(
            CurrentEvidenceReader reader,
            InputConnection connection,
            TextRange selection,
            CurrentEvidenceRequest request) {
        EvidenceReadResult raw;
        try {
            raw = reader.read(connection, request);
        } catch (RuntimeException unavailable) {
            return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (!(raw instanceof CurrentEvidence available) || !available.selectionAvailable()) {
            return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (!available.beforeTextAvailable() || !available.afterTextAvailable()) {
            return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (available.beforeText() == null || available.afterText() == null) {
            return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (selection.hasSelection()
                && (!available.selectedTextAvailable() || available.selectedText() == null)) {
            return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }

        try {
            TextRange liveSelection = new TextRange(
                    available.selectionStart(), available.selectionEnd());
            if (!liveSelection.isKnown()) {
                return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            if (!liveSelection.equals(selection)) {
                return evidenceFailure(TargetChangeReason.SELECTION_CHANGED, liveSelection);
            }
            CharSequence selectedValue = available.selectedTextAvailable()
                    && available.selectedText() != null ? available.selectedText() : "";
            int selectedLength = selectedValue.length();
            int beforeLength = available.beforeText().length();
            int afterLength = available.afterText().length();
            if (selectedLength < 0 || beforeLength < 0 || afterLength < 0
                    || selectedLength > EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2
                    || beforeLength > request.beforeUtf16Units()
                    || afterLength > request.afterUtf16Units()) {
                return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            String selected = selectedValue.toString();
            String before = available.beforeText().toString();
            String after = available.afterText().toString();
            if (selected.length() > EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2
                    || before.length() > request.beforeUtf16Units()
                    || after.length() > request.afterUtf16Units()) {
                return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            EditorSessionLimits.requireSelectedText(selected);
            EditorSessionLimits.requireWellFormedUtf16(before, "beforeText");
            EditorSessionLimits.requireWellFormedUtf16(after, "afterText");
            if (selection.hasSelection()
                    && Math.abs((long) selection.end() - selection.start()) != selected.length()) {
                return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            if (selection.isCollapsed() && !selected.isEmpty()) {
                return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            return new EvidenceAttempt(
                    new MaterializedEvidence(liveSelection, selected, before, after),
                    null,
                    liveSelection);
        } catch (RuntimeException unavailable) {
            return evidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
    }

    private static EvidenceAttempt evidenceFailure(TargetChangeReason reason) {
        return evidenceFailure(reason, TextRange.UNKNOWN);
    }

    private static EvidenceAttempt evidenceFailure(
            TargetChangeReason reason, TextRange observedSelection) {
        return new EvidenceAttempt(
                null,
                Objects.requireNonNull(reason, "reason"),
                Objects.requireNonNull(observedSelection, "observedSelection"));
    }

    private static UndoEvidenceRequest undoEvidenceRequest(
            CommitRecord record, UndoProofState proofState) {
        try {
            int before = EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS;
            if (proofState == UndoProofState.COMMITTED) {
                before = Math.addExact(record.insertedText().length(), before);
            }
            return new UndoEvidenceRequest(
                    before, EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static UndoEvidenceRequest rawEvidenceRequest(
            CommitRecord record, RawProofState proofState) {
        String expected = replacementText(record, proofState);
        if (expected == null) return null;
        try {
            int before = Math.addExact(
                    expected.length(), EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
            return new UndoEvidenceRequest(
                    before, EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static CurrentEvidenceRequest replaceEvidenceRequest(
            ReplaceTransition transition) {
        try {
            int before = EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS;
            if (transition.targetState == ReplaceProofState.INTENDED) {
                before = Math.addExact(transition.replacementUtf16Units, before);
            }
            return new CurrentEvidenceRequest(
                    before, EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static TextRange replaceAlternateSelection(ReplaceTransition transition) {
        if (transition.targetState == ReplaceProofState.INTENDED) {
            return transition.originalSelection;
        }
        try {
            int cursor = Math.addExact(
                    Math.min(
                            transition.originalSelection.start(),
                            transition.originalSelection.end()),
                    transition.replacementUtf16Units);
            return new TextRange(cursor, cursor);
        } catch (RuntimeException unavailable) {
            return TextRange.UNKNOWN;
        }
    }

    private static UndoEvidenceAttempt readUndoEvidence(
            UndoEvidenceReader reader,
            InputConnection connection,
            TextRange selection,
            UndoEvidenceRequest request) {
        UndoEvidenceReadResult raw;
        try {
            raw = reader.read(connection, request);
        } catch (RuntimeException unavailable) {
            return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (!(raw instanceof UndoEvidence available) || !available.selectionAvailable()) {
            return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (!available.beforeTextAvailable() || !available.afterTextAvailable()) {
            return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (available.beforeText() == null || available.afterText() == null) {
            return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (selection.hasSelection()
                && (!available.selectedTextAvailable() || available.selectedText() == null)) {
            return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }

        try {
            TextRange liveSelection = new TextRange(
                    available.selectionStart(), available.selectionEnd());
            if (!liveSelection.isKnown()) {
                return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            if (!liveSelection.equals(selection)) {
                return undoEvidenceFailure(
                        TargetChangeReason.SELECTION_CHANGED, liveSelection);
            }
            CharSequence selectedValue = available.selectedTextAvailable()
                    && available.selectedText() != null ? available.selectedText() : "";
            int selectedLength = selectedValue.length();
            int beforeLength = available.beforeText().length();
            int afterLength = available.afterText().length();
            if (selectedLength < 0 || beforeLength < 0 || afterLength < 0
                    || selectedLength > EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2
                    || beforeLength > request.beforeUtf16Units()
                    || afterLength > request.afterUtf16Units()) {
                return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            String selected = selectedValue.toString();
            String before = available.beforeText().toString();
            String after = available.afterText().toString();
            // A hostile or mutable CharSequence may report a small length and materialize a much
            // larger String. Re-apply every bound to the frozen values before hashing or storing.
            if (selected.length() > EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2
                    || before.length() > request.beforeUtf16Units()
                    || after.length() > request.afterUtf16Units()) {
                return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            EditorSessionLimits.requireSelectedText(selected);
            EditorSessionLimits.requireWellFormedUtf16(before, "undoBeforeText");
            EditorSessionLimits.requireWellFormedUtf16(after, "undoAfterText");
            if (selection.hasSelection()
                    && Math.abs((long) selection.end() - selection.start()) != selected.length()) {
                return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            if (selection.isCollapsed() && !selected.isEmpty()) {
                return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
            }
            return new UndoEvidenceAttempt(
                    new MaterializedUndoEvidence(liveSelection, selected, before, after),
                    null,
                    liveSelection);
        } catch (RuntimeException unavailable) {
            return undoEvidenceFailure(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
    }

    private static UndoEvidenceAttempt undoEvidenceFailure(TargetChangeReason reason) {
        return undoEvidenceFailure(reason, TextRange.UNKNOWN);
    }

    private static UndoEvidenceAttempt undoEvidenceFailure(
            TargetChangeReason reason, TextRange observedSelection) {
        return new UndoEvidenceAttempt(
                null,
                Objects.requireNonNull(reason, "reason"),
                Objects.requireNonNull(observedSelection, "observedSelection"));
    }

    private static TargetChangeReason validateCommittedRelation(
            CommitRecord record, MaterializedUndoEvidence evidence) {
        EditorSessionSnapshot origin = record.originalSession();
        String inserted = record.insertedText();
        if (!origin.selection().isKnown()
                || inserted.isEmpty()
                || evidence.before().length() < inserted.length()) {
            return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
        }

        int split = evidence.before().length() - inserted.length();
        String prefix;
        String committed;
        try {
            prefix = evidence.before().substring(0, split);
            committed = evidence.before().substring(split);
            EditorSessionLimits.requireWellFormedUtf16(prefix, "undoPrefix");
            EditorSessionLimits.requireWellFormedUtf16(committed, "undoCommittedText");
        } catch (RuntimeException unavailable) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }

        Sha256EditorTextHasher hasher = Sha256EditorTextHasher.INSTANCE;
        try {
            if (!record.insertedTextFingerprint()
                    .securelyMatches(hasher.committedText(committed))) {
                return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
            }
            if (!origin.beforeFingerprint().securelyMatches(hasher.beforeContext(prefix))
                    || !origin.afterFingerprint().securelyMatches(
                            hasher.afterContext(evidence.after()))
                    || !origin.selectedTextFingerprint().securelyMatches(
                            hasher.selectedText(origin.selectedText()))
                    || !origin.contextFingerprint().securelyMatches(
                            hasher.context(prefix, origin.selectedText(), evidence.after()))) {
                return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
            }
        } catch (RuntimeException unavailable) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }
        return null;
    }

    private static TargetChangeReason validateRawRelation(
            CommitRecord record,
            RawProofState proofState,
            MaterializedUndoEvidence evidence) {
        EditorSessionSnapshot origin = record.originalSession();
        if (!origin.selection().isKnown()) {
            return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
        }

        String expected = replacementText(record, proofState);
        if (expected == null || evidence.before().length() < expected.length()) {
            return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
        }
        int split = evidence.before().length() - expected.length();
        String prefix;
        String replacement;
        try {
            prefix = evidence.before().substring(0, split);
            replacement = evidence.before().substring(split);
            EditorSessionLimits.requireWellFormedUtf16(prefix, "rawRestorePrefix");
            EditorSessionLimits.requireWellFormedUtf16(replacement, "rawRestoreText");
        } catch (RuntimeException unavailable) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }

        Sha256EditorTextHasher hasher = Sha256EditorTextHasher.INSTANCE;
        try {
            if (!hasher.committedText(expected)
                    .securelyMatches(hasher.committedText(replacement))) {
                return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
            }
            if (!origin.beforeFingerprint().securelyMatches(hasher.beforeContext(prefix))
                    || !origin.afterFingerprint().securelyMatches(
                            hasher.afterContext(evidence.after()))
                    || !origin.selectedTextFingerprint().securelyMatches(
                            hasher.selectedText(origin.selectedText()))
                    || !origin.contextFingerprint().securelyMatches(
                            hasher.context(prefix, origin.selectedText(), evidence.after()))) {
                return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
            }
        } catch (RuntimeException unavailable) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }
        return null;
    }

    private static TargetChangeReason validateReplaceRelation(
            ReplaceTransition transition, MaterializedEvidence evidence) {
        Sha256EditorTextHasher hasher = Sha256EditorTextHasher.INSTANCE;
        try {
            if (transition.targetState == ReplaceProofState.ORIGINAL) {
                if (!transition.expectedSelectedFingerprint.securelyMatches(
                                hasher.selectedText(evidence.selected()))) {
                    return TargetChangeReason.SELECTED_TEXT_CHANGED;
                }
                if (!transition.expectedBeforeFingerprint.securelyMatches(
                                hasher.beforeContext(evidence.before()))
                        || !transition.expectedAfterFingerprint.securelyMatches(
                                hasher.afterContext(evidence.after()))
                        || !transition.expectedContextFingerprint.securelyMatches(
                                hasher.context(
                                        evidence.before(),
                                        evidence.selected(),
                                        evidence.after()))) {
                    return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
                }
                return null;
            }

            if (!evidence.selected().isEmpty()
                    || evidence.before().length() < transition.replacementUtf16Units) {
                return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
            }
            int split = evidence.before().length() - transition.replacementUtf16Units;
            String prefix = evidence.before().substring(0, split);
            String replacement = evidence.before().substring(split);
            EditorSessionLimits.requireWellFormedUtf16(prefix, "replacePrefix");
            EditorSessionLimits.requireWellFormedUtf16(replacement, "replacementText");
            if (!transition.expectedReplacementFingerprint.securelyMatches(
                            hasher.committedText(replacement))
                    || !transition.expectedBeforeFingerprint.securelyMatches(
                            hasher.beforeContext(prefix))
                    || !transition.expectedAfterFingerprint.securelyMatches(
                            hasher.afterContext(evidence.after()))) {
                return TargetChangeReason.SURROUNDING_TEXT_CHANGED;
            }
            return null;
        } catch (RuntimeException unavailable) {
            return TargetChangeReason.EVIDENCE_UNAVAILABLE;
        }
    }

    private static String rawText(CommitRecord record) {
        if (!(record.rawTranscript() instanceof CommitRecord.RawTranscript.Present present)) {
            return null;
        }
        return present.text();
    }

    private static String replacementText(CommitRecord record, RawProofState proofState) {
        return switch (proofState) {
            case COMMITTED -> record.insertedText();
            case ORIGINAL -> "";
            case UNDO -> record.originalSession().selectedText();
            case RAW -> rawText(record);
        };
    }

    private static int replacementCursor(EditorSessionSnapshot origin, String text) {
        if (text == null || !origin.selection().isKnown()) {
            return -1;
        }
        try {
            return Math.addExact(
                    Math.min(origin.selection().start(), origin.selection().end()), text.length());
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    private static String snapshotBeforeTail(String value) {
        int codePoints = value.codePointCount(0, value.length());
        int limit = EditorSessionLimits.SURROUNDING_CONTEXT_CODE_POINTS;
        if (codePoints <= limit) return value;
        return value.substring(value.offsetByCodePoints(0, codePoints - limit));
    }

    private static String snapshotAfterHead(String value) {
        int codePoints = value.codePointCount(0, value.length());
        int limit = EditorSessionLimits.SURROUNDING_CONTEXT_CODE_POINTS;
        if (codePoints <= limit) return value;
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }

    private EditorSessionSnapshot currentSnapshot(
            ValidationBasis basis, MaterializedEvidence evidence) {
        try {
            long capturedAt = clock.nowMillis();
            if (capturedAt < 0) return null;
            ActiveEditor editor = basis.editor();
            return EditorSessionSnapshot.capture(
                    basis.epoch(),
                    basis.token(),
                    editor.packageName(),
                    editor.fieldId(),
                    editor.fieldKind(),
                    editor.inputType(),
                    editor.imeOptions(),
                    evidence.selection(),
                    evidence.selected(),
                    evidence.before(),
                    evidence.after(),
                    editor.learningAllowed(),
                    editor.sensitive(),
                    capturedAt);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean metadataMatches(ActiveEditor editor, EditorDescriptor descriptor) {
        return editor.packageName().equals(descriptor.packageName())
                && editor.fieldId() == descriptor.fieldId()
                && editor.fieldKind() == descriptor.fieldKind()
                && editor.inputType() == descriptor.inputType()
                && editor.imeOptions() == descriptor.imeOptions();
    }

    private static boolean securityMatches(ActiveEditor editor, EditorDescriptor descriptor) {
        boolean sensitive = descriptor.fieldKind() == FieldKind.SENSITIVE;
        boolean learningAllowed = !sensitive
                && (descriptor.imeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        return editor.sensitive() == sensitive && editor.learningAllowed() == learningAllowed;
    }

    private static boolean snapshotMetadataMatches(
            EditorSessionSnapshot snapshot, ActiveEditor editor) {
        return snapshot.packageName().equals(editor.packageName())
                && snapshot.fieldId() == editor.fieldId()
                && snapshot.fieldKind() == editor.fieldKind()
                && snapshot.inputType() == editor.inputType()
                && snapshot.imeOptions() == editor.imeOptions();
    }

    private static ValidationInvalid invalid(TargetChangeReason reason) {
        return new ValidationInvalid(reason);
    }

    private static UndoValidationInvalid undoInvalid(TargetChangeReason reason) {
        return new UndoValidationInvalid(reason);
    }

    private static ReplaceValidationInvalid replaceInvalid(TargetChangeReason reason) {
        return new ReplaceValidationInvalid(reason);
    }

    private CaptureResult capture(
            ActiveEditor editor,
            String selected,
            String before,
            String after,
            boolean learningAllowed,
            boolean sensitive) {
        try {
            long capturedAt = clock.nowMillis();
            if (capturedAt < 0) return rejected(CaptureFailure.INVALID_EVIDENCE);
            return new Captured(EditorSessionSnapshot.capture(
                    epoch,
                    editor.connectionToken(),
                    editor.packageName(),
                    editor.fieldId(),
                    editor.fieldKind(),
                    editor.inputType(),
                    editor.imeOptions(),
                    selection,
                    selected,
                    before,
                    after,
                    learningAllowed,
                    sensitive,
                    capturedAt));
        } catch (IllegalArgumentException | NullPointerException rejected) {
            return rejected(CaptureFailure.INVALID_EVIDENCE);
        }
    }

    private static CaptureFailure validateSelection(
            TextRange range, CharSequence observedSelectedText, String selected) {
        if (!range.isKnown()) {
            return selected.isEmpty() ? null : CaptureFailure.UNREPRESENTABLE_SELECTION;
        }
        if (range.isCollapsed()) {
            return selected.isEmpty() ? null : CaptureFailure.SELECTION_MISMATCH;
        }
        if (observedSelectedText == null || selected.isEmpty()) {
            return CaptureFailure.SELECTED_TEXT_UNAVAILABLE;
        }
        long span = Math.abs((long) range.end() - range.start());
        return span == selected.length() ? null : CaptureFailure.SELECTION_MISMATCH;
    }

    private static TextRange rangeOrUnknown(int start, int end) {
        return start >= 0 && end >= 0 ? new TextRange(start, end) : TextRange.UNKNOWN;
    }

    private static Rejected rejected(CaptureFailure reason) {
        return new Rejected(reason);
    }

    private void advanceEpoch() {
        if (epoch == Long.MAX_VALUE) {
            clearActive();
            throw new IllegalStateException("Editor session epoch space is exhausted");
        }
        epoch++;
    }

    private void beginNewEpoch() {
        transactions.revokeSessionState();
        advanceEpoch();
        advanceAuthorityRevision();
        clearActive();
    }

    private void advanceAuthorityRevision() {
        if (authorityRevision == Long.MAX_VALUE) {
            clearActive();
            throw new IllegalStateException("Editor authority revision space is exhausted");
        }
        authorityRevision++;
    }

    private void clearActive() {
        connections.invalidateAll();
        activeEditor = null;
        selection = TextRange.UNKNOWN;
    }

    private void requireOwnerThread() {
        ownerGuard.requireOwner();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("EditorSessionManager is closed");
    }

    private static OwnerGuard threadGuard(Thread ownerThread) {
        Objects.requireNonNull(ownerThread, "ownerThread");
        return () -> {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(
                        "EditorSessionManager may only be accessed from its owner thread");
            }
        };
    }
}
