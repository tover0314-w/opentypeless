#!/usr/bin/env python3
"""KBD-001 source boundary for the mutually exclusive product keyboard Shell."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


SHELL_ROOT = Path("app/src/main/java/com/opentypeless/android/keyboard/shell")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
EXPECTED_SHELL_FILES = {
    "KeyboardShellRoute.java",
    "KeyboardShellConfig.java",
    "KeyboardShellSelector.java",
    "KeyboardShellFrame.java",
    "KeyboardInputModeLayout.java",
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
    shell = root / SHELL_ROOT
    observed = {
        path.name for path in shell.iterdir()
        if path.is_file() and not path.is_symlink()
    } if shell.is_dir() and not shell.is_symlink() else set()
    if observed != EXPECTED_SHELL_FILES:
        violations.append(Violation(
            "KBD001_SHELL_SOURCE_SET",
            f"expected {sorted(EXPECTED_SHELL_FILES)}, got {sorted(observed)}",
        ))

    sources: dict[str, str] = {}
    for name in EXPECTED_SHELL_FILES:
        sources[name] = _read(shell / name, "KBD001_SHELL_SOURCE", violations)
    service = _read(root / SERVICE, "KBD001_IME_SOURCE", violations)

    for name, source in sources.items():
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
        if any(token in source for token in forbidden_tokens) or WRITER.search(source):
            violations.append(Violation(
                "KBD001_SHELL_CAPABILITY",
                f"{name} may own only view/config/closed-route capability",
            ))
        if re.search(r"(?m)^\s*(?:public\s+|private\s+|protected\s+)?native\s+", source):
            violations.append(Violation("KBD001_SHELL_NATIVE", name))

    config = sources.get("KeyboardShellConfig.java", "")
    config_tokens = (
        'static final String STORE = "keyboard_shell_runtime"',
        'static final String ROUTE_A_ENABLED = "keyboard_shell_route_a"',
        'static final String LEGACY_ENABLED = "enabled"',
        "preferences.getBoolean(ROUTE_A_ENABLED, true)",
        "preferences.getBoolean(LEGACY_ENABLED, true)",
        "public static synchronized KeyboardShellRoute selectedRoute(Context context)",
        "public static synchronized void setRouteAEnabled(Context context, boolean enabled)",
        ".putBoolean(ROUTE_A_ENABLED, enabled)",
        ".remove(LEGACY_ENABLED)",
        "migration.commit()",
    )
    if any(token not in config for token in config_tokens) or ".apply()" in config:
        violations.append(Violation(
            "KBD001_FEATURE_FLAG_SHAPE",
            "Shell rollback flag must default on, migrate once and persist synchronously",
        ))

    selector = sources.get("KeyboardShellSelector.java", "")
    selector_tokens = (
        "switch (route)",
        "case ROUTE_A -> routeAFactory.create()",
        "case LEGACY_VOICE -> legacyFactory.create()",
        'Objects.requireNonNull(selected, "selected Shell factory returned null")',
    )
    if (
        any(token not in selector for token in selector_tokens)
        or "catch (" in selector
        or "catch(" in selector
        or selector.count("routeAFactory.create()") != 1
        or selector.count("legacyFactory.create()") != 1
    ):
        violations.append(Violation(
            "KBD001_EXCLUSIVE_SELECTOR",
            "selector must invoke exactly one factory and never catch/fallback",
        ))

    method = re.search(
        r"(?s)public\s+View\s+onCreateInputView\s*\(\s*\)\s*\{(?P<body>.*?)"
        r"\n\s*\}\n\n\s*@Override\n\s*public\s+boolean\s+onEvaluateInputViewShown",
        service,
    )
    body = method.group("body") if method else ""
    service_tokens = (
        "keyboardShellRoute = KeyboardShellConfig.selectedRoute(this)",
        "KeyboardShellSelector.select(",
        "keyboardShellRoute,",
        "() -> KeyboardShellFrame.routeA(this)",
        "() -> KeyboardShellFrame.legacyVoice(this)",
        "shellFrame.attachToolbar(",
        "shellFrame.attachComposition(",
        "shellFrame.attachKeys(",
        "shellFrame.attachExtensions(",
    )
    if (
        method is None
        or any(token not in service for token in service_tokens)
        or body.count("KeyboardShellSelector.select(") != 1
        or "catch (" in body
        or "catch(" in body
    ):
        violations.append(Violation(
            "KBD001_IME_WIRING",
            "IME must freeze one route and construct one Shell without fallback",
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
    print("KBD-001 keyboard Shell source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
