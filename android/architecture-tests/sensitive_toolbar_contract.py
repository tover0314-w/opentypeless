#!/usr/bin/env python3
"""SEC-005 fail-closed sensitive/no-learning toolbar visibility boundary."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


POLICY = Path("app/src/main/java/com/opentypeless/android/keyboard/toolbar/KeyboardToolbarPrivacyPolicy.java")
ENGINE = Path("app/src/main/java/com/opentypeless/android/security/PrivacyPolicyEngine.java")
LAYOUT = Path("app/src/main/java/com/opentypeless/android/keyboard/toolbar/KeyboardToolbarLayout.java")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
JVM_TEST = Path("app/src/test/java/com/opentypeless/android/keyboard/toolbar/KeyboardToolbarPrivacyPolicyTest.java")
VIEW_TEST = Path("app/src/androidTest/java/com/opentypeless/android/keyboard/toolbar/KeyboardToolbarLayoutInstrumentedTest.java")
HOST_TEST = Path("test-host/src/androidTest/java/com/opentypeless/testhost/TestHostInstrumentedTest.java")


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _read(root: Path, relative: Path, rule: str, violations: list[Violation]) -> str:
    path = root / relative
    if not path.is_file() or path.is_symlink():
        violations.append(Violation(rule, str(relative)))
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError:
        violations.append(Violation(rule, f"invalid UTF-8: {relative}"))
        return ""


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    policy = _read(root, POLICY, "SEC005_POLICY_SOURCE", violations)
    engine = _read(root, ENGINE, "SEC005_HARD_SAFETY_SOURCE", violations)
    layout = _read(root, LAYOUT, "SEC005_LAYOUT_SOURCE", violations)
    service = _read(root, SERVICE, "SEC005_SERVICE_SOURCE", violations)
    jvm = _read(root, JVM_TEST, "SEC005_JVM_TEST", violations)
    view = _read(root, VIEW_TEST, "SEC005_VIEW_TEST", violations)
    host = _read(root, HOST_TEST, "SEC005_SYSTEM_TEST", violations)

    forbidden = (
        "import android.", "InputConnection", "com.opentypeless.android.editor", "java.net.",
        "java.io.", "java.lang.reflect", "java.lang.invoke", "SharedPreferences",
        "System.loadLibrary", "Intent", "Bundle", "Executor", "Thread", "Log.",
    )
    if any(token in policy for token in forbidden) or "catch (" in policy:
        violations.append(Violation(
            "SEC005_POLICY_CAPABILITY",
            "toolbar policy must remain pure, closed and capability-free",
        ))

    policy_compact = _compact(policy)
    required_policy = (
        "recordState(booleanvoiceVisible,booleanactionVisible,"
        "booleanclipboardVisible,booleanteachVisible)",
        "resolve(PrivacyPolicyEngine.HardSafetyhardSafety)",
        "!safe.denies(PrivacyPolicyEngine.Capability.VOICE)",
        "!safe.denies(PrivacyPolicyEngine.Capability.ACTION)",
        "!safe.denies(PrivacyPolicyEngine.Capability.CLIPBOARD)",
        "!safe.denies(PrivacyPolicyEngine.Capability.TEACH)",
        '"ToolbarPrivacyState{visible="',
    )
    if any(token not in policy_compact for token in required_policy):
        violations.append(Violation(
            "SEC005_CLOSED_POLICY",
            "Voice/Action/clipboard/Teach must project from exact hard-safety decisions",
        ))

    engine_compact = _compact(engine)
    hard_safety = (
        "recordHardSafety(booleansensitiveField,booleanlearningAllowed)",
        "if(sensitiveField)learningAllowed=false",
        "returnsensitiveField||(!learningAllowed&&isLearningBound(capability))",
        "hardSafety(booleansensitiveField,booleanlearningAllowed)",
    )
    if any(token not in engine_compact for token in hard_safety):
        violations.append(Violation(
            "SEC005_HARD_SAFETY",
            "field safety must be deny-only and share SEC-001 capability semantics",
        ))

    layout_compact = _compact(layout)
    layout_tokens = (
        "setActionVisible(StringplacementId,booleanvisible)",
        "action.setVisibility(visible?View.VISIBLE:View.GONE)",
        "isActionVisible(StringplacementId)",
        "if(action==null)thrownewIllegalArgumentException",
    )
    if any(token not in layout_compact for token in layout_tokens):
        violations.append(Violation(
            "SEC005_LAYOUT_VISIBILITY",
            "registered toolbar actions must hide/restore by exact placement ID",
        ))

    service_compact = _compact(service)
    service_tokens = (
        "InputContextClassifier.classifyPrivacy(attribute)",
        "PrivacyPolicyEngine.hardSafety(privacy.sensitive(),privacy.learningAllowed())",
        "keyboardToolbarPrivacy=restrictedToolbarPrivacy()",
        'toolbar.setActionVisible("voice.mode",voiceVisible)',
        "voicePulse.setVisibility(voiceVisible?View.VISIBLE:View.GONE)",
        "keyboardInputModeLayout.setVoiceAvailable(voiceVisible)",
        "keyboardToolbarPrivacy.teachVisible()&&TeachCorrectionResolver.isEligible",
    )
    if any(token not in service_compact for token in service_tokens):
        violations.append(Violation(
            "SEC005_SERVICE_WIRING",
            "service must apply one metadata policy and gate Voice/Teach with reversible visibility",
        ))
    if service.count("applyKeyboardToolbarPrivacy();") < 3:
        violations.append(Violation(
            "SEC005_LIFECYCLE_RESTORE",
            "toolbar privacy must apply after construction, input start and input finish",
        ))

    jvm_tokens = (
        "sensitiveFieldHidesEveryPlaintextToolbarCapability",
        "noLearningHidesTeachWithoutRemovingOrdinaryInputActions",
        "ordinaryFieldRestoresAllToolbarPlacements",
        "invalidInputFailsClosedAndDiagnosticsContainNoFieldIdentity",
    )
    if any(token not in jvm for token in jvm_tokens):
        violations.append(Violation(
            "SEC005_JVM_TEST",
            "policy tests must cover sensitive, no-learning, restore and diagnostics",
        ))

    if "privacyVisibilityHidesAndRestoresOnlyTheRequestedActions" not in view:
        violations.append(Violation(
            "SEC005_VIEW_TEST",
            "real toolbar Views must prove GONE-to-VISIBLE restoration",
        ))

    host_tokens = (
        'getString("imeSensitiveToolbarPackage")',
        "selectedImeHidesSensitiveToolbarAndRestoresOrdinaryWhenRequested",
        "focusField(R.id.host_plain_text)",
        "focusField(R.id.host_otp)",
        "focusField(R.id.host_no_learning)",
        "assertToolbarPrivacyState(automation, expectedPackage, R.id.host_otp, false, true)",
    )
    if any(token not in host for token in host_tokens) or host.count(
            "focusField(R.id.host_plain_text)") < 2:
        violations.append(Violation(
            "SEC005_SYSTEM_TEST",
            "selected IME must prove ordinary-to-sensitive-to-no-learning-to-ordinary restore",
        ))
    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--android-root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    args = parser.parse_args()
    violations = inspect_android(args.android_root)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}", file=sys.stderr)
        return 1
    print("SEC-005 sensitive-toolbar source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
