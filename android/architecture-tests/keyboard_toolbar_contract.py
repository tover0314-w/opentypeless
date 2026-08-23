#!/usr/bin/env python3
"""KBD-006 capability-free toolbar, overflow and touch-target source boundary."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


TOOLBAR = Path(
    "app/src/main/java/com/opentypeless/android/keyboard/toolbar/KeyboardToolbarLayout.java"
)
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
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


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    toolbar = _read(root, TOOLBAR, "KBD006_TOOLBAR_SOURCE", violations)
    service = _read(root, SERVICE, "KBD006_SERVICE_SOURCE", violations)
    host_test = _read(root, HOST_TEST, "KBD006_SYSTEM_TEST", violations)

    forbidden = (
        "InputConnection", "com.opentypeless.android.editor", "EditorTransaction",
        "java.lang.reflect", "java.lang.invoke", "dalvik.system", "sun.misc.Unsafe",
        "System.loadLibrary", "java.net.", "okhttp", "SharedPreferences", "Intent(",
    )
    if any(token in toolbar for token in forbidden) or WRITER.search(toolbar):
        violations.append(Violation(
            "KBD006_TOOLBAR_CAPABILITY",
            "toolbar must remain bounded View placement without editor/network/storage authority",
        ))

    compact = _compact(toolbar)
    toolbar_tokens = (
        "MINIMUM_TOUCH_TARGET_DP=48",
        "MAXIMUM_PRIMARY_ACTIONS=2;",
        "enumPlacement{PRIMARY,OVERFLOW}",
        "primarytoolbarisfull;useoverflow",
        "toolbaractionneedsa contentdescription".replace(" ", ""),
        "action.setMinimumWidth(dp(MINIMUM_TOUCH_TARGET_DP))",
        "action.setMinimumHeight(dp(MINIMUM_TOUCH_TARGET_DP))",
        "attachPrimaryAction(StringplacementId,Viewaction,intwidthDp)",
        "attachOverflowAnchor(StringplacementId,Viewaction)",
    )
    if any(token not in compact for token in toolbar_tokens):
        violations.append(Violation(
            "KBD006_SLOT_CONTRACT",
            "toolbar must keep exact two-primary/one-overflow/48dp fail-closed slots",
        ))
    if (
        toolbar.count("placements.put(placementId, placement);") != 1
        or toolbar.count("root.addView(action, actionParams(MINIMUM_TOUCH_TARGET_DP));") != 1
        or "catch (" in toolbar
    ):
        violations.append(Violation(
            "KBD006_SLOT_CONTRACT",
            "placement registration must be single-path and must not swallow failures",
        ))

    service_tokens = (
        "compactToolbar = compactLayout || landscape;",
        "new KeyboardToolbarLayout(this, toolbar)",
        'attachPrimaryAction("voice.mode", modeButton, 64)',
        'attachPrimaryAction(\n                    "input.mode", keyboardInputModeLayout.toggleButton(), 48)',
        'attachOverflowAnchor("more", moreButton)',
        "new KeyboardInputModeLayout(",
        "createVoiceInputPage()",
        "microphone.setMinimumWidth(dp(148))",
        "microphone.setMinimumHeight(dp(56))",
        "MENU_UNDO",
        "KBD-006 keeps Undo in the existing overflow menu",
    )
    if (
        any(token not in service for token in service_tokens)
        or service.count("new KeyboardToolbarLayout(this, toolbar)") != 1
        or service.count("attachPrimaryAction(") != 2
        or service.count("attachOverflowAnchor(") != 1
        or "undoButton" in service
        or "addWeighted(toolbar" in service
        or "addFixed(toolbar" in service
        or "toolbar.addView(" in service
    ):
        violations.append(Violation(
            "KBD006_SERVICE_WIRING",
            "service must keep two bounded toolbar actions, one overflow anchor, one bounded Voice page and no transient Undo button",
        ))

    test_tokens = (
        'getString("imeToolbarPackage")',
        "selectedImeToolbarKeepsFixedFortyEightDpActionsWhenRequested",
        "bounds.width() >= minimumPx",
        "bounds.height() >= minimumPx",
        "mode action missing from selected IME toolbar",
        "overflow action missing from selected IME toolbar",
    )
    if any(token not in host_test for token in test_tokens):
        violations.append(Violation(
            "KBD006_SYSTEM_TEST",
            "test host must verify the selected IME's three fixed 48dp toolbar actions",
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
    print("KBD-006 keyboard toolbar source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
