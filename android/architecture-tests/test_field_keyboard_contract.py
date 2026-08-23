from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from field_keyboard_contract import HOST, LAYOUT, PROFILE, SERVICE, inspect_android


class FieldKeyboardContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (PROFILE, LAYOUT, SERVICE, HOST):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_sensitive_profile_that_is_not_first(self) -> None:
        path = self.root / PROFILE
        source = path.read_text(encoding="utf-8")
        source = source.replace(
            "if (fieldKind == FieldKind.SENSITIVE) return PASSWORD;",
            "if (fieldKind == FieldKind.SENSITIVE) return GENERAL;",
        )
        path.write_text(source, encoding="utf-8")
        self.assertIn("KBD004_PROFILE_SELECTION", self.rules())

    def test_rejects_missing_phone_symbol(self) -> None:
        path = self.root / LAYOUT
        path.write_text(path.read_text(encoding="utf-8").replace('"+", "0"', '"0", "0"'),
                        encoding="utf-8")
        self.assertIn("KBD004_FIELD_LAYOUT", self.rules())

    def test_rejects_double_shortcut_dispatch(self) -> None:
        path = self.root / LAYOUT
        path.write_text(path.read_text(encoding="utf-8").replace(
            "listener.insertText(shortcuts[index]);",
            "listener.insertText(shortcuts[index]); listener.insertText(shortcuts[index]);",
        ), encoding="utf-8")
        self.assertIn("KBD004_FIELD_LAYOUT", self.rules())

    def test_rejects_editor_writer_capability(self) -> None:
        path = self.root / PROFILE
        path.write_text(path.read_text(encoding="utf-8")
                        + "\nandroid.view.inputmethod.InputConnection c;\n",
                        encoding="utf-8")
        self.assertIn("KBD004_FIELD_CAPABILITY", self.rules())

    def test_rejects_second_profile_selection(self) -> None:
        path = self.root / SERVICE
        path.write_text(path.read_text(encoding="utf-8").replace(
            "KeyboardFieldProfile.from(attribute, currentFieldKind);",
            "KeyboardFieldProfile.from(attribute, currentFieldKind); "
            "KeyboardFieldProfile.from(attribute, currentFieldKind);",
        ), encoding="utf-8")
        self.assertIn("KBD004_SERVICE_WIRING", self.rules())

    def test_rejects_missing_test_host_field(self) -> None:
        path = self.root / HOST
        path.write_text(path.read_text(encoding="utf-8").replace(
            "InputType.TYPE_CLASS_PHONE", "InputType.TYPE_CLASS_TEXT"), encoding="utf-8")
        self.assertIn("KBD004_TEST_HOST_MATRIX", self.rules())


if __name__ == "__main__":
    unittest.main()
