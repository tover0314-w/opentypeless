#!/usr/bin/env python3
"""KBD-008 bounded input-method and internal-engine switching boundary."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


SWITCH_ROOT = Path("app/src/main/java/com/opentypeless/android/keyboard/switching")
ENGINE = SWITCH_ROOT / "KeyboardEngineSelection.java"
SYSTEM = SWITCH_ROOT / "KeyboardSystemImeSwitcher.java"
LAYOUT = Path("app/src/main/java/com/opentypeless/android/keyboard/latin/LatinKeyboardLayout.java")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
ENGINE_TEST = Path(
    "app/src/test/java/com/opentypeless/android/keyboard/switching/"
    "KeyboardEngineSelectionTest.java"
)
SYSTEM_TEST = Path(
    "app/src/test/java/com/opentypeless/android/keyboard/switching/"
    "KeyboardSystemImeSwitcherTest.java"
)
VIEW_TEST = Path(
    "app/src/androidTest/java/com/opentypeless/android/keyboard/latin/"
    "LatinKeyboardLayoutInstrumentedTest.java"
)
METHOD_XML = Path("app/src/main/res/xml/method.xml")
EN_STRINGS = Path("app/src/main/res/values/ime_strings.xml")
ZH_STRINGS = Path("app/src/main/res/values-zh-rCN/ime_strings.xml")
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
    source_dir = root / SWITCH_ROOT
    actual = (
        {path.name for path in source_dir.glob("*.java")}
        if source_dir.is_dir() and not source_dir.is_symlink()
        else set()
    )
    expected = {"KeyboardEngineSelection.java", "KeyboardSystemImeSwitcher.java"}
    if actual != expected:
        violations.append(Violation(
            "KBD008_SOURCE_SET", f"switching source set drifted: {sorted(actual)}"
        ))

    engine = _read(root, ENGINE, "KBD008_ENGINE_SOURCE", violations)
    system = _read(root, SYSTEM, "KBD008_SYSTEM_SOURCE", violations)
    layout = _read(root, LAYOUT, "KBD008_LAYOUT_SOURCE", violations)
    service = _read(root, SERVICE, "KBD008_SERVICE_SOURCE", violations)
    engine_test = _read(root, ENGINE_TEST, "KBD008_ENGINE_TEST", violations)
    system_test = _read(root, SYSTEM_TEST, "KBD008_SYSTEM_TEST", violations)
    view_test = _read(root, VIEW_TEST, "KBD008_VIEW_TEST", violations)
    method_xml = _read(root, METHOD_XML, "KBD008_METHOD_XML", violations)
    english = _read(root, EN_STRINGS, "KBD008_EN_STRINGS", violations)
    chinese = _read(root, ZH_STRINGS, "KBD008_ZH_STRINGS", violations)

    forbidden = (
        "InputConnection", "EditorTransaction", "EditorSessionManager",
        "CompositionCoordinator", "java.lang.reflect", "java.lang.invoke",
        "dalvik.system", "sun.misc.Unsafe", "System.loadLibrary", " native ",
        "java.net.", "okhttp", "SharedPreferences", "android.database", "Intent(",
    )
    for name, source in (("engine", engine), ("system", system)):
        if any(token in source for token in forbidden) or WRITER.search(source):
            violations.append(Violation(
                "KBD008_CAPABILITY_BOUNDARY",
                f"{name} switching contract acquired editor/native/network/storage authority",
            ))

    engine_compact = _compact(engine)
    engine_tokens = (
        "enumEngine{LATIN,RIME}",
        "sealedinterfaceCycleResultpermitsChanged,Unavailable",
        "Set.copyOf(EnumSet.copyOf(available))",
        "available.size()>1",
        "if(!bounded.contains(Engine.LATIN))",
        "keyboardenginerevisionexhausted",
        "newUnavailable(this)",
        "newChanged(newKeyboardEngineSelection(next,available,nextRevision()))",
    )
    if any(token not in engine_compact for token in engine_tokens):
        violations.append(Violation(
            "KBD008_ENGINE_CONTRACT",
            "engine state must remain closed, immutable, monotonic and Latin-safe",
        ))
    if "catch(" in engine_compact or "Runnable" in engine:
        violations.append(Violation(
            "KBD008_ENGINE_CONTRACT", "engine state must not own callbacks or swallow failures"
        ))

    system_compact = _compact(system)
    system_tokens = (
        "NEXT_INPUT_METHOD_REQUESTED",
        "PICKER_SHOWN_NO_NEXT",
        "PICKER_SHOWN_AFTER_PLATFORM_FAILURE",
        "FAILED",
        "if(platform.switchToNextInputMethod())",
        "if(platform.showInputMethodPicker())",
        "publicstaticOutcomerequestPicker(Platformplatform)",
    )
    if any(token not in system_compact for token in system_tokens):
        violations.append(Violation(
            "KBD008_SYSTEM_CONTRACT",
            "next-IME request must use a stable picker fallback and explicit outcomes",
        ))
    if system.count("catch (RuntimeException unavailable)") != 3:
        violations.append(Violation(
            "KBD008_SYSTEM_CONTRACT", "platform failures need exactly three content-free boundaries"
        ))

    layout_compact = _compact(layout)
    layout_tokens = (
        "voidshowKeyboardPicker();",
        "voidswitchInputEngine();",
        "switchKeyboardButton.setOnLongClickListener(ignored->consumeKeyboardPickerLongPress())",
        "privatebooleanconsumeKeyboardPickerLongPress(){feedback.onLongPress(switchKeyboardButton);listener.showKeyboardPicker();returntrue;}",
        "engineSwitchButton.setVisibility(View.GONE)",
        "publicvoidsetEngineSelection(KeyboardEngineSelectionselection)",
        "engineSwitchButton.setVisibility(safe.hasAlternative()?View.VISIBLE:View.GONE)",
        "state.resetToLetters()",
        "engineSwitchButton.setEnabled(enabled)",
    )
    if any(token not in layout_compact for token in layout_tokens):
        violations.append(Violation(
            "KBD008_VIEW_CONTRACT",
            "globe/picker and bounded engine controls must remain accessible and fail closed",
        ))

    service_tokens = (
        "KeyboardEngineSelection.latinOnly()",
        "KeyboardSystemImeSwitcher.requestNext(systemImePlatform())",
        "KeyboardSystemImeSwitcher.requestPicker(systemImePlatform())",
        "if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false",
        "switchToNextInputMethod(false)",
        "manager.showInputMethodPicker()",
        "latinKeyboardLayout.setEngineSelection(keyboardEngineSelection)",
        "if (activeTarget != null || voiceController.state() != VoiceController.State.IDLE)",
        "keyboardCandidateBar.clear()",
        "refreshRimeAvailability(editorEpoch, privacy)",
        "RimeResourceStore.RuntimePackage runtime = null",
        "rimeResourceStore.runtimePackage()",
        "keyboardEngineSelection = keyboardEngineSelection.withAvailability(available)",
        "KeyboardEngineSelection.Engine.RIME",
    )
    if (
        any(token not in service for token in service_tokens)
        or service.count("switchToNextInputMethod(false)") != 1
        or service.count("manager.showInputMethodPicker()") != 1
        or "ACTION_INPUT_METHOD_SETTINGS" in service
        or "KeyboardEngineSelection.latinOnly().withAvailability(" in service
    ):
        violations.append(Violation(
            "KBD008_SERVICE_WIRING",
            "service must use one Android next/picker path and expose no unregistered Rime mode",
        ))

    if "android:supportsSwitchingToNextInputMethod=\"true\"" not in method_xml:
        violations.append(Violation(
            "KBD008_METHOD_XML", "IME metadata must advertise next-input-method support"
        ))
    required_strings = (
        "ime_cd_switch_keyboard", "ime_cd_engine_latin", "ime_cd_engine_rime",
        "ime_status_keyboard_picker_opened", "ime_status_keyboard_switch_failed",
        "ime_status_second_engine_unavailable",
    )
    if any(token not in english or token not in chinese for token in required_strings):
        violations.append(Violation(
            "KBD008_LOCALIZATION", "English and Chinese switching semantics must stay synchronized"
        ))

    engine_test_tokens = (
        "latinOnlyIsSafeAndCannotPretendRimeExists",
        "exactlyTwoRegisteredEnginesCycleWithMonotonicRevision",
        "availabilityRemovalFallsBackOnlyToLatin",
        "invalidEmptyMissingActiveAndExhaustedStatesFailClosed",
    )
    system_test_tokens = (
        "successfulNextImeDoesNotOpenPicker",
        "noNextImeFallsBackToPicker",
        "platformFailureUsesPickerWithoutLeakingExceptionText",
        "pickerFailureIsStableAndDoesNotRetry",
        "explicitPickerNeverAttemptsNextIme",
    )
    view_test_tokens = (
        "engineControlIsHiddenUntilTwoEnginesAreRegistered",
        "switchKeyboardButton().performLongClick()",
    )
    if any(token not in engine_test for token in engine_test_tokens):
        violations.append(Violation("KBD008_ENGINE_TEST", "engine-state matrix drifted"))
    if any(token not in system_test for token in system_test_tokens):
        violations.append(Violation("KBD008_SYSTEM_TEST", "system-switch fallback matrix drifted"))
    if any(token not in view_test for token in view_test_tokens):
        violations.append(Violation("KBD008_VIEW_TEST", "View switching coverage drifted"))
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
    print("KBD-008 keyboard switching source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
