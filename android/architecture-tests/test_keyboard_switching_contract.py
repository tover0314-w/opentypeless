from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from keyboard_switching_contract import (
    ENGINE,
    ENGINE_TEST,
    EN_STRINGS,
    LAYOUT,
    METHOD_XML,
    SERVICE,
    SWITCH_ROOT,
    SYSTEM,
    SYSTEM_TEST,
    VIEW_TEST,
    ZH_STRINGS,
    inspect_android,
)


class KeyboardSwitchingContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (
            ENGINE, SYSTEM, LAYOUT, SERVICE, ENGINE_TEST, SYSTEM_TEST, VIEW_TEST,
            METHOD_XML, EN_STRINGS, ZH_STRINGS,
        ):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def mutate(self, relative: Path, old: str, new: str) -> None:
        path = self.root / relative
        source = path.read_text(encoding="utf-8")
        self.assertIn(old, source)
        path.write_text(source.replace(old, new, 1), encoding="utf-8")

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_extra_switching_source(self) -> None:
        extra = self.root / SWITCH_ROOT / "HiddenEngine.java"
        extra.write_text("final class HiddenEngine {}\n", encoding="utf-8")
        self.assertIn("KBD008_SOURCE_SET", self.rules())

    def test_rejects_editor_capability_in_engine_state(self) -> None:
        path = self.root / ENGINE
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection connection;\n",
            encoding="utf-8",
        )
        self.assertIn("KBD008_CAPABILITY_BOUNDARY", self.rules())

    def test_rejects_open_engine_vocabulary(self) -> None:
        self.mutate(ENGINE, "LATIN,\n        RIME", "LATIN,\n        RIME,\n        REMOTE")
        self.assertIn("KBD008_ENGINE_CONTRACT", self.rules())

    def test_rejects_settings_activity_fallback(self) -> None:
        path = self.root / SERVICE
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\n// ACTION_INPUT_METHOD_SETTINGS must not replace the user picker.\n",
            encoding="utf-8",
        )
        self.assertIn("KBD008_SERVICE_WIRING", self.rules())

    def test_rejects_unregistered_rime_availability(self) -> None:
        self.mutate(
            SERVICE,
            "KeyboardEngineSelection.latinOnly()",
            "KeyboardEngineSelection.latinOnly().withAvailability(java.util.EnumSet.allOf(KeyboardEngineSelection.Engine.class))",
        )
        self.assertIn("KBD008_SERVICE_WIRING", self.rules())

    def test_rejects_non_consuming_picker_long_press(self) -> None:
        self.mutate(
            LAYOUT,
            "private boolean consumeKeyboardPickerLongPress() {\n"
            "        View source = engineSwitchButton.getVisibility() == View.VISIBLE\n"
            "                ? engineSwitchButton\n"
            "                : switchKeyboardButton;\n"
            "        feedback.onLongPress(source);\n"
            "        listener.showKeyboardPicker();\n"
            "        return true;\n"
            "    }",
            "private boolean consumeKeyboardPickerLongPress() {\n"
            "        View source = engineSwitchButton.getVisibility() == View.VISIBLE\n"
            "                ? engineSwitchButton\n"
            "                : switchKeyboardButton;\n"
            "        feedback.onLongPress(source);\n"
            "        listener.showKeyboardPicker();\n"
            "        return false;\n"
            "    }",
        )
        self.assertIn("KBD008_VIEW_CONTRACT", self.rules())

    def test_rejects_missing_chinese_accessibility_text(self) -> None:
        self.mutate(ZH_STRINGS, "ime_cd_engine_rime", "ime_cd_engine_removed")
        self.assertIn("KBD008_LOCALIZATION", self.rules())

    def test_rejects_picker_fallback_test_removal(self) -> None:
        self.mutate(SYSTEM_TEST, "noNextImeFallsBackToPicker", "noNextImeWasIgnored")
        self.assertIn("KBD008_SYSTEM_TEST", self.rules())


if __name__ == "__main__":
    unittest.main()
