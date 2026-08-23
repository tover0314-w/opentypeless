#!/usr/bin/env python3
"""Fail-closed source contract for RIM-009 Rime-to-Voice arbitration."""

from __future__ import annotations

import sys
from pathlib import Path


SERVICE = Path("main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
POLICY = Path("main/java/com/opentypeless/android/editor/CompositionConflictPolicy.java")
UNIT = Path("test/java/com/opentypeless/android/ime/RimeVoicePreemptionTest.java")
HOST_ANDROID = Path("androidTest/java/com/opentypeless/android/editor/host/EditorTransactionManagerInstrumentedTest.java")
VOICE_ANDROID = Path("androidTest/java/com/opentypeless/android/ime/VoiceEditorTransactionSessionInstrumentedTest.java")


def _method(source: str, signature: str) -> str:
    start = source.find(signature)
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
                return source[start : index + 1]
    return ""


def inspect_sources(app_src: Path) -> list[str]:
    violations: list[str] = []
    paths = [SERVICE, POLICY, UNIT, HOST_ANDROID, VOICE_ANDROID]
    for relative in paths:
        path = app_src / relative
        if not path.is_file() or path.is_symlink():
            violations.append(f"RIME_VOICE_REQUIRED_FILE:{relative}")
    if violations:
        return violations

    service = (app_src / SERVICE).read_text(encoding="utf-8")
    policy = (app_src / POLICY).read_text(encoding="utf-8")
    unit = (app_src / UNIT).read_text(encoding="utf-8")
    host_android = (app_src / HOST_ANDROID).read_text(encoding="utf-8")
    voice_android = (app_src / VOICE_ANDROID).read_text(encoding="utf-8")

    toggle = _method(service, "private void toggleRecording(")
    preempt = _method(service, "private RimeVoicePreemption preemptRimeForVoice(")
    release = _method(service, "private RimeReleaseProof releaseRimeForVoice(")
    nested_start = service.find("static final class RimeVoicePreemption")
    nested_end = service.find("static final class PendingDetachedSession", nested_start)
    nested = service[nested_start:nested_end] if nested_start >= 0 and nested_end > nested_start else ""

    for name, body in (("toggle", toggle), ("preempt", preempt), ("release", release), ("nested", nested)):
        if not body:
            violations.append(f"RIME_VOICE_MISSING_SECTION:{name}")

    for token in ("RimeToVoice", "COMMIT_RIME", "CANCEL_RIME", "rimeToVoiceDecision()", "ReleaseDirective.COMMIT_CURRENT", "ReleaseDirective.CANCEL_CURRENT"):
        if token not in policy:
            violations.append(f"RIME_VOICE_POLICY:{token}")

    for token in (
        "coordinator.beginPreempt(",
        "new CompositionCoordinator.Acquisition.Voice()",
        "coordinator.finishPreempt(",
        "ReleaseResolution.PROVEN_RELEASED",
        "ReleaseResolution.PROVEN_UNCHANGED",
        "CompositionState.VoicePreparing",
        "CompositionState.RimeComposing",
        "claimVoiceSession(",
        "cancelUnclaimedVoice()",
    ):
        if token not in nested:
            violations.append(f"RIME_VOICE_HANDOFF:{token}")

    for token in (
        "lease.pendingKeyCommands != 0",
        "lease.pendingSelection != null",
        "lease.pendingPageRequest != null",
        "RimeVoicePreemption.begin(",
        "compositionConflictPolicy",
        "releaseRimeForVoice(",
        "ReleaseResolution.PROVEN_RELEASED",
        "ReleaseResolution.PROVEN_UNCHANGED",
        "ReleaseResolution.UNCERTAIN",
        "closeRimeControllerOnly(lease)",
        "activeRimeLease = null",
        "keyboardCandidateBar.clear()",
    ):
        if token not in preempt:
            violations.append(f"RIME_VOICE_SERVICE_PREEMPT:{token}")

    for token in (
        "!lease.hasComposition()",
        "finishRimeComposition(",
        'setRimeComposition(\n                    this, lease.editorSnapshot, "", cancellationRevision)',
        "captureCurrentTransactionSnapshot()",
        "classifyUnchangedRelease(",
    ):
        if token not in release:
            violations.append(f"RIME_VOICE_EDITOR_RELEASE:{token}")

    for token in ("InputConnection", "getCurrentInputConnection", ".commitText(", ".setComposingText(", ".finishComposingText(", ".deleteSurroundingText(", ".sendKeyEvent("):
        if token in preempt or token in release or token in nested:
            violations.append(f"RIME_VOICE_EDITOR_BYPASS:{token}")

    try:
        if not (toggle.count("captureTarget()") == 1 and toggle.index("activeRimeLease != null") < toggle.index("preemptRimeForVoice()") < toggle.index("captureTarget()") < toggle.index("claimVoiceSession")):
            violations.append("RIME_VOICE_START_ORDER")
    except ValueError:
        violations.append("RIME_VOICE_START_ORDER")

    tests = unit + host_android + voice_android
    for token in (
        "provenCommitPublishesOneVoiceOwnerThatCanBeClaimedOnce",
        "provenUnchangedRestoresExactRimeOwnerAndDoesNotPublishVoice",
        "uncertainReleaseStaysPendingAndCannotBeClaimedOrRetried",
        "unclaimedVoiceCanBeCancelledWithoutStartingRecognition",
        "rimeToVoiceCommitAndCancelPathsNeverOverlapOrLoseVisibleText",
        "exactRimeOwnerCanHandOffToOneVoiceOwnerAfterReleaseProof",
        'assertEquals("prenivoice"',
        'assertEquals("prevoice"',
    ):
        if token not in tests:
            violations.append(f"RIME_VOICE_TEST_MATRIX:{token}")
    return violations


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: rime_voice_conflict_contract.py <app-src>", file=sys.stderr)
        return 2
    violations = inspect_sources(Path(sys.argv[1]))
    if violations:
        for violation in violations:
            print(violation)
        return 1
    print("RIM-009 Rime-to-Voice conflict boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
