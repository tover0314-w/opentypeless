#!/usr/bin/env python3
"""KBD-004 field-profile layout and single-writer source boundary."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


PROFILE = Path("app/src/main/java/com/opentypeless/android/keyboard/field/KeyboardFieldProfile.java")
LAYOUT = Path("app/src/main/java/com/opentypeless/android/keyboard/latin/LatinKeyboardLayout.java")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
HOST = Path("test-host/src/main/java/com/opentypeless/testhost/TestHostActivity.java")
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


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    profile = _read(root, PROFILE, "KBD004_PROFILE_SOURCE", violations)
    layout = _read(root, LAYOUT, "KBD004_LAYOUT_SOURCE", violations)
    service = _read(root, SERVICE, "KBD004_SERVICE_SOURCE", violations)
    host = _read(root, HOST, "KBD004_TEST_HOST_SOURCE", violations)

    forbidden = (
        "InputConnection", "com.opentypeless.android.editor", "java.lang.reflect",
        "java.lang.invoke", "dalvik.system", "sun.misc.Unsafe", "System.loadLibrary",
        "java.net.", "okhttp",
    )
    for name, source in ((PROFILE.name, profile), (LAYOUT.name, layout)):
        if any(token in source for token in forbidden) or WRITER.search(source):
            violations.append(Violation(
                "KBD004_FIELD_CAPABILITY",
                f"{name} must remain metadata/View/callback-only",
            ))

    profile_tokens = (
        "GENERAL,", "EMAIL,", "URI,", "PHONE,", "NUMBER,", "DATE,", "PASSWORD;",
        "if (fieldKind == FieldKind.SENSITIVE) return PASSWORD",
        "if (fieldKind == FieldKind.EMAIL_ADDRESS) return EMAIL",
        "if (fieldKind == FieldKind.URI) return URI",
        "case InputType.TYPE_CLASS_PHONE -> PHONE",
        "case InputType.TYPE_CLASS_NUMBER -> NUMBER",
        "case InputType.TYPE_CLASS_DATETIME -> DATE",
        "default -> GENERAL",
    )
    if any(token not in profile for token in profile_tokens) or "catch (" in profile:
        violations.append(Violation(
            "KBD004_PROFILE_SELECTION",
            "field profile selection must be closed, sensitive-first and fail to GENERAL",
        ))

    compact = _compact(layout)
    layout_tokens = (
        'PHONE_ROWS={{"1","2","3"},{"4","5","6"},{"7","8","9","+","0","*","#"}}',
        'NUMBER_ROWS={{"1","2","3"},{"4","5","6"},{"7","8","9","-","0","."}}',
        'DATE_ROWS={{"1","2","3"},{"4","5","6"},{"7","8","9","/","0","-","."}}',
        'caseEMAIL->newString[]{"@","."}',
        'caseURI->newString[]{"/",".",":"}',
        "fieldProfile.usesNumericPanel()",
        "state.resetToLetters()",
        "listener.insertText(shortcuts[index])",
        "listener.insertText(symbol)",
        "root.setContentDescription(context.getString(profileDescription(fieldProfile)))",
    )
    if (
        any(token not in compact for token in layout_tokens)
        or layout.count("listener.insertText(shortcuts[index])") != 1
        or "catch (" in layout
    ):
        violations.append(Violation(
            "KBD004_FIELD_LAYOUT",
            "field rows and shortcuts must be exact and single-dispatch",
        ))

    service_tokens = (
        "KeyboardFieldProfile.from(attribute, currentFieldKind)",
        "latinKeyboardLayout.setFieldProfile(currentKeyboardFieldProfile)",
    )
    if (
        any(token not in service for token in service_tokens)
        or service.count("KeyboardFieldProfile.from(attribute, currentFieldKind)") != 1
        or service.count("new LatinKeyboardLayout(") != 1
        or service.count("insertKeyboardText(text);") != 1
    ):
        violations.append(Violation(
            "KBD004_SERVICE_WIRING",
            "onStartInput must select one profile without creating another writer route",
        ))

    host_tokens = (
        "TYPE_TEXT_VARIATION_EMAIL_ADDRESS", "TYPE_TEXT_VARIATION_URI",
        "TYPE_CLASS_PHONE", "TYPE_NUMBER_FLAG_DECIMAL",
        "TYPE_DATETIME_VARIATION_DATE", "TYPE_TEXT_VARIATION_PASSWORD",
    )
    if any(token not in host for token in host_tokens):
        violations.append(Violation(
            "KBD004_TEST_HOST_MATRIX",
            "test-host must keep all six specialized inputType fields",
        ))
    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-root", type=Path,
                        default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    violations = inspect_android(args.android_root)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}", file=sys.stderr)
        return 1
    print("KBD-004 field keyboard source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
