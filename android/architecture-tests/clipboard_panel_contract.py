#!/usr/bin/env python3
"""KBD-011 fail-closed current-clipboard panel source boundary."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


CLIPBOARD_ROOT = Path("app/src/main/java/com/opentypeless/android/keyboard/clipboard")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
SNAPSHOT_TEST = Path("app/src/test/java/com/opentypeless/android/keyboard/clipboard/ClipboardPanelSnapshotTest.java")
READER_TEST = Path("app/src/androidTest/java/com/opentypeless/android/keyboard/clipboard/SystemClipboardReaderInstrumentedTest.java")
PANEL_TEST = Path("app/src/androidTest/java/com/opentypeless/android/keyboard/clipboard/KeyboardClipboardPanelInstrumentedTest.java")
HOST_TEST = Path("test-host/src/androidTest/java/com/opentypeless/testhost/TestHostInstrumentedTest.java")
EXPECTED_FILES = {
    "ClipboardPanelSnapshot.java",
    "KeyboardClipboardPanel.java",
    "SystemClipboardReader.java",
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


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    clipboard_root = root / CLIPBOARD_ROOT
    observed = {
        path.name for path in clipboard_root.iterdir()
        if path.is_file() and not path.is_symlink()
    } if clipboard_root.is_dir() and not clipboard_root.is_symlink() else set()
    if observed != EXPECTED_FILES:
        violations.append(Violation(
            "KBD011_SOURCE_SET",
            f"expected {sorted(EXPECTED_FILES)}, got {sorted(observed)}",
        ))

    sources = {
        name: _read(clipboard_root / name, "KBD011_SOURCE", violations)
        for name in EXPECTED_FILES
    }
    snapshot = sources.get("ClipboardPanelSnapshot.java", "")
    panel = sources.get("KeyboardClipboardPanel.java", "")
    reader = sources.get("SystemClipboardReader.java", "")
    service = _read(root / SERVICE, "KBD011_SERVICE", violations)
    snapshot_test = _read(root / SNAPSHOT_TEST, "KBD011_SNAPSHOT_TEST", violations)
    reader_test = _read(root / READER_TEST, "KBD011_READER_TEST", violations)
    panel_test = _read(root / PANEL_TEST, "KBD011_PANEL_TEST", violations)
    host_test = _read(root / HOST_TEST, "KBD011_SYSTEM_TEST", violations)

    pure_forbidden = (
        "import android.", "InputConnection", "ClipboardManager", "ClipData",
        "java.net.", "java.io.", "SharedPreferences", "Bundle", "Intent", "Log.",
    )
    if any(token in snapshot for token in pure_forbidden) or "catch (Exception" in snapshot:
        violations.append(Violation(
            "KBD011_SNAPSHOT_CAPABILITY",
            "clipboard snapshot must remain pure, bounded and content-redacted",
        ))
    snapshot_compact = _compact(snapshot)
    snapshot_tokens = (
        "MAX_TEXT_CODE_POINTS=EditorOperation.MAX_TEXT_CODE_POINTS",
        "EditorSessionLimits.requireWellFormedUtf16(text,\"clipboardText\")",
        "Character.isISOControl(codePoint)",
        "returntext.substring(0,end)+'\\u2026'",
        'return"ClipboardPanelSnapshot{state="+state',
    )
    if any(token not in snapshot_compact for token in snapshot_tokens):
        violations.append(Violation(
            "KBD011_SNAPSHOT_BOUNDARY",
            "snapshot must reject malformed/oversized text and redact diagnostics",
        ))

    panel_forbidden = (
        "ClipboardManager", "ClipData", "InputConnection",
        "com.opentypeless.android.editor", "java.net.", "java.io.",
        "SharedPreferences", "System.loadLibrary", "Log.",
    )
    if any(token in panel for token in panel_forbidden) or WRITER.search(panel):
        violations.append(Violation(
            "KBD011_PANEL_CAPABILITY",
            "panel may own only View state and bounded callbacks",
        ))
    panel_compact = _compact(panel)
    panel_tokens = (
        "publicstaticfinalintMINIMUM_TOUCH_TARGET_DP=48",
        "voidonPaste(Stringtext)",
        "voidonRefresh()",
        "voidonClose()",
        "content.setText(hasText?snapshot.preview():\"\")",
        "snapshot=ClipboardPanelSnapshot.unavailable()",
        "content.setText(\"\")",
    )
    if any(token not in panel_compact for token in panel_tokens):
        violations.append(Violation(
            "KBD011_PANEL_RENDERER",
            "panel must expose 48dp actions and destructively clear retained text",
        ))

    reader_compact = _compact(reader)
    reader_tokens = (
        "manager.getPrimaryClip()",
        "clip.getItemAt(0).getText()",
        "ClipboardPanelSnapshot.fromPrimaryText",
    )
    reader_forbidden = (
        "addPrimaryClipChangedListener", "removePrimaryClipChangedListener",
        "coerceToText", "coerceToStyledText", "getUri()", "getIntent()",
        "SharedPreferences", "java.io.", "java.net.", "Log.",
    )
    if (
        any(token not in reader_compact for token in reader_tokens)
        or any(token in reader for token in reader_forbidden)
        or reader.count("getPrimaryClip()") != 1
    ):
        violations.append(Violation(
            "KBD011_EXPLICIT_READER",
            "reader must perform one explicit plain-text read with no listener/coercion/history",
        ))

    service_compact = _compact(service)
    service_tokens = (
        "keyboardToolbarPrivacy.clipboardVisible()&&currentEditor!=null",
        "caseMENU_CLIPBOARD->showClipboardPanel()",
        "panel.render(SystemClipboardReader.readCurrentText(this))",
        "sensitiveField||!keyboardToolbarPrivacy.clipboardVisible()",
        "if(lease!=null&&!lease.isIdle())",
        "hideClipboardPanel();closeIdleRimeSession();insertKeyboardText(snapshot.text())",
        "if(!keyboardToolbarPrivacy.clipboardVisible())hideClipboardPanel()",
    )
    if any(token not in service_compact for token in service_tokens):
        violations.append(Violation(
            "KBD011_SERVICE_WIRING",
            "service must consume SEC-005, reject busy Rime and reuse the ETM typing facade",
        ))
    if service.count("hideClipboardPanel();") < 8:
        violations.append(Violation(
            "KBD011_LIFECYCLE_CLEAR",
            "clipboard body must clear on field/view/window/service lifecycle transitions",
        ))

    required_tests = (
        (snapshot_test, "unsupportedAndOversizedInputsNeverRetainPartialText"),
        (snapshot_test, "diagnosticsExposeOnlyStateAndLength"),
        (reader_test, "uriAndIntentItemsAreNotCoercedOrResolved"),
        (panel_test, "textCardPreviewsButPastesTheExactBoundedSnapshot"),
        (panel_test, "clearRemovesClipboardBodyAndHeaderActionsRemainReachable"),
        (host_test, "selectedImeClipboardPastesCurrentTextAndHidesInSensitiveFieldWhenRequested"),
        (host_test, 'getString("imeClipboardPackage")'),
        (host_test, 'ClipData.newPlainText("KBD-011 fixture", "clipboard fixture")'),
    )
    if any(token not in source for source, token in required_tests):
        violations.append(Violation(
            "KBD011_TEST_COVERAGE",
            "tests must cover bounds, redaction, no coercion, exact paste, clear and system privacy",
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
    print("KBD-011 clipboard-panel source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
