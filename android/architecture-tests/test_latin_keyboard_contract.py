from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from latin_keyboard_contract import LATIN_ROOT, SERVICE, inspect_android


class LatinKeyboardContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        target = self.root / LATIN_ROOT
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(android / LATIN_ROOT, target)
        service = self.root / SERVICE
        service.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(android / SERVICE, service)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_editor_capability_or_writer_in_layout(self) -> None:
        path = self.root / LATIN_ROOT / "LatinKeyboardLayout.java"
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection c;"
            + "void evil(){c.commitText(\"x\",1);}\n",
            encoding="utf-8",
        )
        self.assertIn("KBD002_LATIN_CAPABILITY", self.rules())

    def test_rejects_incomplete_or_reordered_alphabet(self) -> None:
        path = self.root / LATIN_ROOT / "LatinKeyboardLayout.java"
        path.write_text(
            path.read_text(encoding="utf-8").replace("qwertyuiop", "qwertyui"),
            encoding="utf-8",
        )
        self.assertIn("KBD002_QWERTY_LAYOUT", self.rules())

    def test_rejects_wrap_content_indent_spacer_that_expands_the_ime(self) -> None:
        path = self.root / LATIN_ROOT / "LatinKeyboardLayout.java"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "row.addView(spacer, new LinearLayout.LayoutParams(0, 0, weight));",
                "addWeighted(row, spacer, weight);",
            ),
            encoding="utf-8",
        )
        self.assertIn("KBD002_QWERTY_LAYOUT", self.rules())

    def test_rejects_double_dispatch_from_service(self) -> None:
        path = self.root / SERVICE
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "insertKeyboardText(text);",
                "insertKeyboardText(text); insertKeyboardText(text);",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("KBD002_IME_WIRING", self.rules())

    def test_rejects_shift_window_or_fallback_drift(self) -> None:
        path = self.root / LATIN_ROOT / "LatinKeyboardState.java"
        path.write_text(
            path.read_text(encoding="utf-8")
            .replace("CAPS_DOUBLE_TAP_MILLIS = 400L", "CAPS_DOUBLE_TAP_MILLIS = 5_000L")
            .replace("public synchronized ShiftMode pressShift", "void fallback() { try {} catch (Exception ignored) {} }\npublic synchronized ShiftMode pressShift"),
            encoding="utf-8",
        )
        self.assertIn("KBD002_SHIFT_STATE", self.rules())

    def test_rejects_unreviewed_latin_source(self) -> None:
        (self.root / LATIN_ROOT / "HiddenKeyWriter.java").write_text(
            "package com.opentypeless.android.keyboard.latin;\n",
            encoding="utf-8",
        )
        self.assertIn("KBD002_LATIN_SOURCE_SET", self.rules())

    def test_rejects_full_screen_weighted_key_stage(self) -> None:
        path = self.root / SERVICE
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "shellFrame.attachKeys(keyStage, matchWrap());",
                "shellFrame.attachKeys(keyStage, new LinearLayout.LayoutParams("
                "LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));",
            ),
            encoding="utf-8",
        )
        self.assertIn("KBD002_IME_WIRING", self.rules())


if __name__ == "__main__":
    unittest.main()
