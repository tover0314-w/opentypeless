#!/usr/bin/env python3
"""SEC-001 pure privacy-policy intersection and hard-precedence source boundary."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


ENGINE = Path(
    "app/src/main/java/com/opentypeless/android/security/PrivacyPolicyEngine.java"
)
TEST = Path(
    "app/src/test/java/com/opentypeless/android/security/PrivacyPolicyEngineTest.java"
)


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
    engine = _read(root, ENGINE, "SEC001_ENGINE_SOURCE", violations)
    tests = _read(root, TEST, "SEC001_JVM_TEST", violations)
    compact = _compact(engine)

    forbidden = (
        "import android.",
        "InputConnection",
        "android.content.Context",
        "SharedPreferences",
        "java.io.",
        "java.net.",
        "java.nio.",
        "java.lang.reflect",
        "java.lang.invoke",
        "Executor",
        "Thread",
        "System.loadLibrary",
        "Log.",
        "Bundle",
        "Intent",
    )
    if any(token in engine for token in forbidden) or "catch (" in engine:
        violations.append(Violation(
            "SEC001_PURE_AUTHORITY",
            "privacy policy must remain pure and capability-free",
        ))

    vocabulary = (
        "enumCapability{VOICE,SEND_CONTEXT,HISTORY,ACTION,CLIPBOARD,LEARNING,TEACH}",
        "enumDecisionReason{ALLOWED,SENSITIVE_FIELD,NO_PERSONALIZED_LEARNING,"
        "GLOBAL_INCOGNITO,APP_RULE,PROFILE,USER_CHOICE}",
        "recordRequest(EffectiveProfileprofile,booleansensitiveField,"
        "booleanlearningAllowed,booleanglobalIncognito,"
        "CapabilitySetappMaximum,CapabilitySetuserChoices)",
        "recordPolicy(Decisionvoice,DecisionsendContext,Decisionhistory,"
        "Decisionaction,Decisionclipboard,Decisionlearning,Decisionteach)",
    )
    if any(token not in compact for token in vocabulary):
        violations.append(Violation(
            "SEC001_CLOSED_VOCABULARY",
            "capabilities, reasons, request and policy must keep their exact closed shape",
        ))

    hard_tokens = (
        "booleansensitive=safe.sensitiveField()||profileRequiresFullRestriction(safe.profile())",
        "if(sensitive)returnDecision.denied(DecisionReason.SENSITIVE_FIELD)",
        "if(!request.learningAllowed()&&isLearningBound(capability))",
        "DecisionReason.NO_PERSONALIZED_LEARNING",
        "if(request.globalIncognito()&&isIncognitoBound(capability))",
        "DecisionReason.GLOBAL_INCOGNITO",
        "if(!request.appMaximum().allows(capability))",
        "DecisionReason.APP_RULE",
        "if(!profileAllows)returnDecision.denied(DecisionReason.PROFILE)",
        "if(!request.userChoices().allows(capability))",
        "DecisionReason.USER_CHOICE",
        "profile.voiceRouteId().isDisabled()"
        "&&OverrideValue.value(ProcessingMode.EXACT).equals(profile.processingMode().value())"
        "&&profile.sendContext().isDisabled()&&profile.historyEnabled().isDisabled()"
        "&&profile.actionSetId().isDisabled()",
    )
    if any(token not in compact for token in hard_tokens):
        violations.append(Violation(
            "SEC001_HARD_PRECEDENCE",
            "sensitive/no-learning/incognito/App/profile must dominate UI choices",
        ))
    precedence = (
        compact.find("if(sensitive)returnDecision.denied"),
        compact.find("if(!request.learningAllowed()"),
        compact.find("if(request.globalIncognito()"),
        compact.find("if(!request.appMaximum().allows"),
        compact.find("if(!profileAllows)"),
        compact.find("if(!request.userChoices().allows"),
    )
    if any(index < 0 for index in precedence) or tuple(sorted(precedence)) != precedence:
        violations.append(Violation(
            "SEC001_HARD_PRECEDENCE",
            "privacy restriction order changed",
        ))

    retention_tokens = (
        "capability==Capability.HISTORY||capability==Capability.LEARNING"
        "||capability==Capability.TEACH",
        "capability==Capability.SEND_CONTEXT||capability==Capability.HISTORY"
        "||capability==Capability.LEARNING||capability==Capability.TEACH",
        "if(teach.allowed()&&!learning.allowed())",
        "Decisionteach=learning.allowed()?decide(Capability.TEACH",
    )
    if any(token not in compact for token in retention_tokens):
        violations.append(Violation(
            "SEC001_RETENTION_CLOSURE",
            "no-learning/incognito/Teach closure must remain fail closed",
        ))

    diagnostic_tokens = (
        '"Request{profile=<redacted>,sensitive="',
        '"Policy{allowed="',
        '"CapabilitySet{count="',
    )
    if any(token not in compact for token in diagnostic_tokens):
        violations.append(Violation(
            "SEC001_REDACTED_DIAGNOSTICS",
            "policy diagnostics must remain content-free",
        ))

    test_tokens = (
        "sensitiveSignalOverridesProfileAppAndUiEnables",
        "hardSafetyProfileCannotBeRelabeledAsOrdinaryByCaller",
        "noLearningBlocksOnlyPersistentPersonalizationCapabilities",
        "incognitoBlocksContextHistoryLearningAndTeachWithoutDisablingInput",
        "appMaximumCannotBeBypassedByFullyEnabledUiChoices",
        "userChoicesCanOnlyTightenTheAlreadyAuthorizedPolicy",
        "restrictiveReasonPrecedenceIsStableAndTeachRequiresLearning",
        "diagnosticsExposeNoProfileIdentifiersAndInputsRejectNulls",
    )
    if any(token not in tests for token in test_tokens):
        violations.append(Violation(
            "SEC001_JVM_TEST",
            "required hard-policy regression coverage is missing",
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
    print("SEC-001 privacy policy source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
