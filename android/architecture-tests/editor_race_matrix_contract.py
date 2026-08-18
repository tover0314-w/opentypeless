#!/usr/bin/env python3
"""Fail-closed TST-002 mapping for the twenty editor race scenarios."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import sys


@dataclass(frozen=True)
class Evidence:
    path: str
    method: str
    tokens: tuple[str, ...]


RACES: dict[str, tuple[Evidence, ...]] = {
    "R01": (
        Evidence("app/src/test/java/com/opentypeless/android/ime/EditorTargetGuardTest.java",
                 "rejectsEveryCrossFieldAndCursorMutationVector", ('"com.other"',)),
        Evidence("app/src/test/java/com/opentypeless/android/ime/VoicePipelineStateTest.java",
                 "lifecycleBoundaryCancelsInsteadOfStoppingForAFinalResult",
                 ("cancelControllerForLifecycle", "assertEquals(1, controller.cancelCalls)", "assertEquals(0, controller.stopCalls)")),
    ),
    "R02": (
        Evidence("app/src/test/java/com/opentypeless/android/ime/EditorTargetGuardTest.java",
                 "rejectsEveryCrossFieldAndCursorMutationVector", ('"com.example", 43', '"changed"')),
        Evidence("test-host/src/androidTest/java/com/opentypeless/testhost/TestHostInstrumentedTest.java",
                 "instrumentationSwitchesFieldsAndPreservesIndependentText", ("first", "second", "assertEquals")),
    ),
    "R03": (
        Evidence("app/src/test/java/com/opentypeless/android/ime/VoicePipelineStateTest.java",
                 "staleQueuedResultCannotMutateAReplacementVoiceSession",
                 ("oldTarget", "newTarget", "assertFalse(mutated.get())")),
    ),
    "R04": (
        Evidence("app/src/test/java/com/opentypeless/android/editor/SessionValidatorTest.java",
                 "selectionAndEachFingerprintHaveStableReasons", ("TargetChangeReason.SELECTION_CHANGED",)),
    ),
    "R05": (
        Evidence("app/src/test/java/com/opentypeless/android/editor/SessionValidatorTest.java",
                 "selectionAndEachFingerprintHaveStableReasons",
                 ("TargetChangeReason.SELECTED_TEXT_CHANGED", "TargetChangeReason.SURROUNDING_TEXT_CHANGED")),
    ),
    "R06": (
        Evidence("app/src/androidTest/java/com/opentypeless/android/editor/host/EditorTransactionManagerInstrumentedTest.java",
                 "rimeToVoiceCommitAndCancelPathsNeverOverlapOrLoseVisibleText", ('"prenivoice"', '"prevoice"')),
    ),
    "R07": (
        Evidence("app/src/test/java/com/opentypeless/android/ime/VoiceEditorTransactionSessionTest.java",
                 "visiblePartialUsesFrozenCommitOrCancelPolicyAndNeverAcceptsAnotherPartial",
                 ("ReleaseDirective.COMMIT_CURRENT", "ReleaseDirective.CANCEL_CURRENT", "assertFalse(committing.acceptsPartial")),
    ),
    "R08": (
        Evidence("app/src/test/java/com/opentypeless/android/ime/VoiceEditorTransactionSessionTest.java",
                 "terminalGateDropsEvenLargerLatePartialAndKeepsFinalCallbackOwned",
                 ("beginFinalizing", "Long.MAX_VALUE", "assertFalse(session.acceptsPartial")),
    ),
    "R09": (
        Evidence("app/src/test/java/com/opentypeless/android/recognition/RecognitionSessionControllerTest.java",
                 "cancelDropsLateCallbacksAndPermitsNextSession", ('stale.onFinal("must be ignored")', 'List.of("cancelled")')),
    ),
    "R10": (
        Evidence("app/src/test/java/com/opentypeless/android/recognition/RecognitionRouterTest.java",
                 "terminalFailuresNeverRetryOrFallbackEvenForHostileFeedback",
                 ("FailureClass.CANCELLED", "FailureReason.TERMINAL_FAILURE", "IgnoreReason.STALE_ATTEMPT")),
    ),
    "R11": (
        Evidence("app/src/test/java/com/opentypeless/android/ime/VoicePipelineStateTest.java",
                 "lifecycleBoundaryCancelsInsteadOfStoppingForAFinalResult",
                 ("cancelControllerForLifecycle", "VoiceController.State.IDLE")),
    ),
    "R12": (
        Evidence("app/src/androidTest/java/com/opentypeless/android/ime/VoiceEditorTransactionSessionInstrumentedTest.java",
                 "screenOffReceiverCancelsExactlyOnceAndIgnoresUnrelatedBroadcasts",
                 ("Intent.ACTION_SCREEN_OFF", "Intent.ACTION_USER_PRESENT", "assertEquals(1, cancellations.get())")),
    ),
    "R13": (
        Evidence("app/src/test/java/com/opentypeless/android/editor/host/EditorSessionManagerTest.java",
                 "everyStartRotatesTokenEvenForSameConnectionAndField",
                 ("assertEquals(2, second.epoch())", "assertNotEquals(first.connectionToken(), second.connectionToken())")),
    ),
    "R14": (
        Evidence("app/src/test/java/com/opentypeless/android/editor/host/EditorTransactionManagerTest.java",
                 "selectedOriginUndoProvesTheFullOriginalAndFailsClosedOnWrongInsertion",
                 ("undo", "assertRollbackFailed", "OUTCOME_UNCONFIRMED")),
    ),
    "R15": (
        Evidence("app/src/test/java/com/opentypeless/android/editor/host/EditorTransactionManagerTest.java",
                 "rawRestoreStructuralRejectionsAndOrdinaryRawOperationsRetainExactRecord",
                 ("COMMIT_RECORD_UNAVAILABLE", "OPERATION_NOT_SUPPORTED", "foreign-id")),
    ),
    "R16": (
        Evidence("app/src/test/java/com/opentypeless/android/security/PrivacyPolicyEngineTest.java",
                 "noLearningBlocksOnlyPersistentPersonalizationCapabilities",
                 ("Capability.LEARNING", "Capability.TEACH", "NO_PERSONALIZED_LEARNING")),
    ),
    "R17": (
        Evidence("app/src/test/java/com/opentypeless/android/editor/CompositionConflictPolicyTest.java",
                 "fixedPairsCommitComposingTextAndActionChoiceCoversBothActionPhases",
                 ("ActionRunning", "ActionPreview", "CANCEL_CURRENT_AND_ROUTE_RESULT")),
    ),
    "R18": (
        Evidence("app/src/test/java/com/opentypeless/android/editor/CompositionCoordinatorTest.java",
                 "concurrentExactAcquireHasOneWinnerAndNoGenerationGap",
                 ("CountDownLatch", "Disposition.APPLIED", "Disposition.IGNORED_STALE")),
    ),
    "R19": (
        Evidence("app/src/test/java/com/opentypeless/android/recognition/AndroidSystemRecognitionProviderTest.java",
                 "busyStartFailsOnlyTheNewSessionAndLeavesTheActiveSessionAuthoritative",
                 ("RECOGNIZER_BUSY", "assertEquals(1, backend.startCount)", 'backend.callback.onFinal("active result")')),
    ),
    "R20": (
        Evidence("app/src/test/java/com/opentypeless/android/recognition/RecognitionRouterTest.java",
                 "staleForeignAndRegistryAbaCannotAdvanceOrCompleteTheRoute",
                 ("IgnoreReason.STALE_ATTEMPT", "FailureReason.PROVIDER_CHANGED", "assertFalse(firstRouter.isCurrent(retry))")),
    ),
}


def _method(source: str, name: str) -> str:
    marker = f"void {name}("
    start = source.find(marker)
    if start < 0:
        return ""
    brace = source.find("{", start)
    if brace < 0:
        return ""
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    return ""


def inspect_android(android_root: Path) -> list[str]:
    violations: list[str] = []
    expected = tuple(f"R{index:02d}" for index in range(1, 21))
    if tuple(RACES) != expected:
        violations.append("TST002_SCENARIO_SET")
        return violations

    cache: dict[str, str] = {}
    for race, evidence_items in RACES.items():
        if not evidence_items:
            violations.append(f"TST002_{race}_NO_EVIDENCE")
            continue
        for evidence in evidence_items:
            path = android_root / evidence.path
            if not path.is_file() or path.is_symlink():
                violations.append(f"TST002_{race}_FILE:{evidence.path}")
                continue
            source = cache.setdefault(evidence.path, path.read_text(encoding="utf-8"))
            body = _method(source, evidence.method)
            if not body:
                violations.append(f"TST002_{race}_METHOD:{evidence.method}")
                continue
            for token in evidence.tokens:
                if token not in body:
                    violations.append(f"TST002_{race}_ASSERT:{evidence.method}:{token}")
    return violations


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: editor_race_matrix_contract.py <android-root>", file=sys.stderr)
        return 2
    violations = inspect_android(Path(sys.argv[1]))
    if violations:
        for violation in violations:
            print(violation)
        return 1
    print("TST-002 editor race matrix passed: R01-R20")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
