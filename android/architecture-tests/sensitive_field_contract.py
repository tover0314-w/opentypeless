#!/usr/bin/env python3
"""SEC-002 metadata-only sensitive-field classification boundary."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


CLASSIFIER = Path("app/src/main/java/com/opentypeless/android/context/InputContextClassifier.java")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
MANAGER = Path("app/src/main/java/com/opentypeless/android/editor/host/EditorSessionManager.java")
HOST = Path("test-host/src/main/java/com/opentypeless/testhost/TestHostActivity.java")
HOST_TEST = Path(
    "test-host/src/androidTest/java/com/opentypeless/testhost/TestHostInstrumentedTest.java"
)
WRITER = re.compile(
    r"\.\s*(?:commitText|setComposingText|finishComposingText|"
    r"deleteSurroundingText(?:InCodePoints)?|sendKeyEvent|setSelection)\s*\("
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


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    classifier = _read(root, CLASSIFIER, "SEC002_CLASSIFIER_SOURCE", violations)
    service = _read(root, SERVICE, "SEC002_SERVICE_SOURCE", violations)
    manager = _read(root, MANAGER, "SEC002_MANAGER_SOURCE", violations)
    host = _read(root, HOST, "SEC002_HOST_SOURCE", violations)
    host_test = _read(root, HOST_TEST, "SEC002_HOST_TEST_SOURCE", violations)

    forbidden = (
        "InputConnection", "com.opentypeless.android.editor", "packageName", ".extras",
        "getTextBeforeCursor", "getTextAfterCursor", "getSelectedText", "android.content.",
        "java.io.", "java.net.", "okhttp", "java.lang.reflect", "java.lang.invoke",
        "dalvik.system", "System.loadLibrary", "SharedPreferences", "SQLite",
    )
    if any(token in classifier for token in forbidden) or WRITER.search(classifier):
        violations.append(Violation(
            "SEC002_METADATA_ONLY",
            "classifier must use bounded EditorInfo metadata without editor or execution authority",
        ))

    closed_tokens = (
        "NONE,", "PASSWORD,", "ONE_TIME_CODE,", "PAYMENT,", "IDENTITY,",
        "UNTRUSTED_METADATA", "MAX_METADATA_CODE_POINTS = 256",
        "MAX_METADATA_FIELD_CODE_POINTS = 128", "hasUnpairedSurrogate(raw)",
        "unsafeMetadataCodePoint(codePoint)", "Normalizer.Form.NFKC",
        "IME_FLAG_NO_PERSONALIZED_LEARNING", "TYPE_TEXT_VARIATION_PASSWORD",
        "TYPE_TEXT_VARIATION_VISIBLE_PASSWORD", "TYPE_TEXT_VARIATION_WEB_PASSWORD",
        "TYPE_NUMBER_VARIATION_PASSWORD", "info.fieldName", "info.label", "info.hintText",
        "info.privateImeOptions", "matchesPayment(words, compact)",
        "matchesOneTimeCode(words, compact)", "matchesIdentity(words, compact)",
        "if (privacy.sensitive()) return FieldKind.SENSITIVE",
        "if (sensitivity != Sensitivity.NONE && learningAllowed)",
    )
    if any(token not in classifier for token in closed_tokens) or "catch (" in classifier:
        violations.append(Violation(
            "SEC002_CLASSIFICATION_SHAPE",
            "password/OTP/payment/identity classification must remain closed and fail closed",
        ))

    sensitive_index = classifier.find("if (privacy.sensitive()) return FieldKind.SENSITIVE")
    ordinary_index = classifier.find("if (inputClass == InputType.TYPE_CLASS_NUMBER")
    metadata_index = classifier.find("Metadata metadata = normalizeMetadata")
    learning_index = classifier.find("IME_FLAG_NO_PERSONALIZED_LEARNING")
    if not (0 <= sensitive_index < ordinary_index and 0 <= metadata_index < learning_index):
        violations.append(Violation(
            "SEC002_ONLY_TIGHTENS",
            "sensitive metadata must win before ordinary kinds and no-learning cannot bypass it",
        ))

    if (
        service.count("InputContextClassifier.classify(attribute)") != 1
        or manager.count("InputContextClassifier.classify(info)") != 1
        or "currentFieldKind = InputContextClassifier.classify(attribute);" not in service
        or "InputContextClassifier.classify(info)," not in manager
    ):
        violations.append(Violation(
            "SEC002_SINGLE_CLASSIFIER",
            "service and editor host must share exactly one classifier call per activation path",
        ))

    host_tokens = (
        "R.id.host_otp", "R.string.host_otp", "R.id.host_payment_card",
        "R.string.host_payment_card", "R.id.host_identity_number",
        "R.string.host_identity_number", "R.id.host_no_learning",
        "IME_FLAG_NO_PERSONALIZED_LEARNING",
    )
    if any(token not in host for token in host_tokens):
        violations.append(Violation(
            "SEC002_TEST_HOST_MATRIX",
            "test-host must keep OTP/payment/identity/no-learning fields",
        ))

    test_tokens = (
        "selectedImeTreatsOtpPaymentAndIdentityAsSensitiveWhenRequested",
        'getString("imeSensitiveFieldPackage")',
    )
    compact_test = re.sub(r"\s+", "", host_test)
    exact_calls = (
        'assertFieldProfile(automation,expectedPackage,R.id.host_otp,"Passwordkeyboard","密码键盘",false);',
        'assertFieldProfile(automation,expectedPackage,R.id.host_payment_card,"Passwordkeyboard","密码键盘",false);',
        'assertFieldProfile(automation,expectedPackage,R.id.host_identity_number,"Passwordkeyboard","密码键盘",false);',
    )
    if (
        any(token not in host_test for token in test_tokens)
        or any(call not in compact_test for call in exact_calls)
    ):
        violations.append(Violation(
            "SEC002_SYSTEM_IME_MATRIX",
            "selected IME test must verify all three heuristic fields as sensitive",
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
    print("SEC-002 sensitive-field source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
