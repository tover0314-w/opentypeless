#!/usr/bin/env python3
"""KBD-002 source boundary for the product ASCII QWERTY layer."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


LATIN_ROOT = Path("app/src/main/java/com/opentypeless/android/keyboard/latin")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
EXPECTED_LATIN_FILES = {
    "BoundedDeleteRepeater.java",
    "LatinKeyboardState.java",
    "LatinKeyboardLayout.java",
}
WRITER = re.compile(
    r"\.\s*(?:commitText|setComposingText|finishComposingText|"
    r"deleteSurroundingText(?:InCodePoints)?|sendKeyEvent|setSelection)\s*\("
)


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _read(path: Path, rule: str, violations: list[Violation]) -> str:
    if not path.is_file() or path.is_symlink():
        violations.append(Violation(rule, str(path)))
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError:
        violations.append(Violation(rule, f"invalid UTF-8: {path}"))
        return ""


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    latin = root / LATIN_ROOT
    observed = {
        path.name for path in latin.iterdir()
        if path.is_file() and not path.is_symlink()
    } if latin.is_dir() and not latin.is_symlink() else set()
    if observed != EXPECTED_LATIN_FILES:
        violations.append(Violation(
            "KBD002_LATIN_SOURCE_SET",
            f"expected {sorted(EXPECTED_LATIN_FILES)}, got {sorted(observed)}",
        ))

    sources = {
        name: _read(latin / name, "KBD002_LATIN_SOURCE", violations)
        for name in EXPECTED_LATIN_FILES
    }
    service = _read(root / SERVICE, "KBD002_IME_SOURCE", violations)

    forbidden_tokens = (
        "android.view.inputmethod",
        "com.opentypeless.android.editor",
        "java.lang.reflect",
        "java.lang.invoke",
        "dalvik.system",
        "sun.misc.Unsafe",
        "System.loadLibrary",
        "java.net.",
        "okhttp",
    )
    for name, source in sources.items():
        if any(token in source for token in forbidden_tokens) or WRITER.search(source):
            violations.append(Violation(
                "KBD002_LATIN_CAPABILITY",
                f"{name} may own only bounded state/View/callback capability",
            ))
        if re.search(r"(?m)^\s*(?:public\s+|private\s+|protected\s+)?native\s+", source):
            violations.append(Violation("KBD002_LATIN_NATIVE", name))

    state = sources.get("LatinKeyboardState.java", "")
    state_tokens = (
        "public static final long CAPS_DOUBLE_TAP_MILLIS = 400L",
        "LOWER,",
        "SHIFTED,",
        "CAPS_LOCKED",
        "uptimeMillis - shiftedAtMillis <= CAPS_DOUBLE_TAP_MILLIS",
        "shiftMode = doubleTap ? ShiftMode.CAPS_LOCKED : ShiftMode.LOWER",
        "if (shiftMode == ShiftMode.SHIFTED)",
        "letter must be lowercase ASCII",
    )
    if any(token not in state for token in state_tokens) or "catch (" in state:
        violations.append(Violation(
            "KBD002_SHIFT_STATE",
            "Shift/Caps must be closed, bounded and one-shot without exception fallback",
        ))

    layout = sources.get("LatinKeyboardLayout.java", "")
    layout_tokens = (
        'private static final String[] LETTER_ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"}',
        "listener.insertText(state.consumeLetter(letter))",
        "listener::deleteBackward",
        "listener::performEnter",
        '() -> listener.insertText(" ")',
        "state.pressShift(SystemClock.uptimeMillis())",
        "public void setInputEnabled(boolean enabled)",
        "row.addView(spacer, new LinearLayout.LayoutParams(0, 0, weight))",
    )
    if (
        any(token not in layout for token in layout_tokens)
        or layout.count("letters.put(letter, button)") != 1
        or "catch (" in layout
    ):
        violations.append(Violation(
            "KBD002_QWERTY_LAYOUT",
            "layout must expose exact alphabet/state and bounded callbacks",
        ))

    repeater = sources.get("BoundedDeleteRepeater.java", "")
    repeat_tokens = (
        "DEFAULT_INITIAL_DELAY_MILLIS = 320L",
        "DEFAULT_REPEAT_INTERVAL_MILLIS = 58L",
        "DEFAULT_MAXIMUM_DELETES = 120",
        "deleteAction.run();",
        "if (!active || generation != expectedGeneration || deleteAction == null) return;",
        "if (deletes >= maximumDeletes)",
        "pending.cancel();",
    )
    if any(token not in repeater for token in repeat_tokens) or "catch (" in repeater:
        violations.append(Violation(
            "KBD002_DELETE_REPEAT",
            "delete repeat must remain immediate, bounded and generation-cancellable",
        ))

    route_start = service.find("if (shellFrame.route() == KeyboardShellRoute.ROUTE_A)")
    legacy_start = service.find("} else {", route_start)
    route_body = service[route_start:legacy_start] if route_start >= 0 and legacy_start >= 0 else ""
    service_tokens = (
        "new LatinKeyboardLayout(",
        "routeTypingText(text);",
        "routeDeleteBackward();",
        "routeKeyboardEnter();",
        "OpenTypelessImeService.this.openRimeResourceImport();",
        "typing = latinKeyboardLayout.root();",
        "latinKeyboardLayout.setInputEnabled(editorEnabled)",
        "shellFrame.attachKeys(keyStage, matchWrap());",
    )
    if (
        route_start < 0
        or any(token not in service for token in service_tokens)
        or route_body.count("routeTypingText(text);") != 1
        or route_body.count("routeDeleteBackward();") != 1
        or route_body.count("routeKeyboardEnter();") != 1
        or route_body.count("OpenTypelessImeService.this.openRimeResourceImport();") != 1
        or service.count("insertKeyboardText(text);") != 1
        or service.count("deleteKeyboardBackward();") != 1
        or service.count("performKeyboardEnter();") != 1
        or service.count("new LatinKeyboardLayout(") != 1
    ):
        violations.append(Violation(
            "KBD002_IME_WIRING",
            "Route A must bind each QWERTY intent once to the existing narrow façade",
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
    print("KBD-002 Latin keyboard source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
