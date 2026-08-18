#!/usr/bin/env python3
"""KBD-003 source boundary for paged symbols and fixed long-press alternates."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


LATIN_ROOT = Path("app/src/main/java/com/opentypeless/android/keyboard/latin")
STATE = LATIN_ROOT / "LatinKeyboardState.java"
LAYOUT = LATIN_ROOT / "LatinKeyboardLayout.java"
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
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


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    state = _read(root / STATE, "KBD003_STATE_SOURCE", violations)
    layout = _read(root / LAYOUT, "KBD003_LAYOUT_SOURCE", violations)
    service = _read(root / SERVICE, "KBD003_IME_SOURCE", violations)

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
    for name, source in ((STATE.name, state), (LAYOUT.name, layout)):
        if any(token in source for token in forbidden_tokens) or WRITER.search(source):
            violations.append(Violation(
                "KBD003_SYMBOL_CAPABILITY",
                f"{name} may own only bounded state/View/callback capability",
            ))
        if re.search(r"(?m)^\s*(?:public\s+|private\s+|protected\s+)?native\s+", source):
            violations.append(Violation("KBD003_SYMBOL_NATIVE", name))

    state_tokens = (
        "LETTERS,",
        "SYMBOLS_PRIMARY,",
        "SYMBOLS_SECONDARY",
        "layer == Layer.LETTERS ? Layer.SYMBOLS_PRIMARY : Layer.LETTERS",
        "throw new IllegalStateException(\"symbol page is unavailable on the letter layer\")",
        "layer == Layer.SYMBOLS_PRIMARY",
        "? Layer.SYMBOLS_SECONDARY",
        ": Layer.SYMBOLS_PRIMARY",
        "resetShift();",
    )
    if any(token not in state for token in state_tokens) or "catch (" in state:
        violations.append(Violation(
            "KBD003_LAYER_STATE",
            "letter/primary/secondary transitions must be closed and reset Shift",
        ))

    compact = _compact(layout)
    layout_tokens = (
        'LONG_PRESS_ROWS={"1234567890","@#$%&-+()","*\\"\':;!?"}',
        'SYMBOL_ROWS_PRIMARY={{"1","2","3","4","5","6","7","8","9","0"},'
        '{"@","#","$","%","&","-","+","(",")","/"},'
        '{"*","\\"","\'",":",";","!","?",",","."}}',
        'SYMBOL_ROWS_SECONDARY={{"~","`","|","•","√","π","÷","×","§","∆"},'
        '{"€","£","¥","₩","¢","^","°","=","{","}"},'
        '{"\\\\","_","[","]","<",">","…","¿","¡"}}',
        "button.setOnLongClickListener(ignored->{feedback.onLongPress(button);listener.insertText(longPressSymbol);returntrue;})",
        "listener.insertText(symbol)",
        "state.pressSymbolsToggle()",
        "state.pressSymbolPage()",
        "symbolPageButton.setVisibility(View.GONE)",
        "symbolPageButton.setVisibility(View.VISIBLE)",
        "symbolsToggleButton.setEnabled(enabled)",
        "symbolPageButton.setEnabled(enabled)",
    )
    if (
        any(token not in compact for token in layout_tokens)
        or layout.count("listener.insertText(symbol)") != 1
        or layout.count("listener.insertText(longPressSymbol)") != 1
        or "catch (" in layout
    ):
        violations.append(Violation(
            "KBD003_SYMBOL_LAYOUT",
            "symbol pages/long-press must be exact, bounded and single-dispatch",
        ))

    route_start = service.find("if (shellFrame.route() == KeyboardShellRoute.ROUTE_A)")
    legacy_start = service.find("} else {", route_start)
    route_body = service[route_start:legacy_start] if route_start >= 0 and legacy_start >= 0 else ""
    if (
        route_start < 0
        or route_body.count("routeTypingText(text);") != 1
        or service.count("insertKeyboardText(text);") != 1
        or service.count("new LatinKeyboardLayout(") != 1
    ):
        violations.append(Violation(
            "KBD003_IME_WIRING",
            "symbol and long-press text must share the one existing keyboard façade binding",
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
    print("KBD-003 symbol keyboard source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
