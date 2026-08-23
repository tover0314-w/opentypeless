from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from symbol_keyboard_contract import LAYOUT, SERVICE, STATE, inspect_android


class SymbolKeyboardContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (STATE, LAYOUT, SERVICE):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_symbol_inventory_drift(self) -> None:
        path = self.root / LAYOUT
        path.write_text(
            path.read_text(encoding="utf-8").replace('"1", "2", "3"', '"1", "2", "x"'),
            encoding="utf-8",
        )
        self.assertIn("KBD003_SYMBOL_LAYOUT", self.rules())

    def test_rejects_long_press_that_does_not_consume_the_gesture(self) -> None:
        path = self.root / LAYOUT
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "if (!flickGesture.commitLongPress()) return true;",
                "if (!flickGesture.commitLongPress()) return false;",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("KBD003_SYMBOL_LAYOUT", self.rules())

    def test_rejects_symbol_page_fallback_from_letters(self) -> None:
        path = self.root / STATE
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                'throw new IllegalStateException("symbol page is unavailable on the letter layer");',
                "layer = Layer.SYMBOLS_SECONDARY;",
            ),
            encoding="utf-8",
        )
        self.assertIn("KBD003_LAYER_STATE", self.rules())

    def test_rejects_double_symbol_dispatch(self) -> None:
        path = self.root / LAYOUT
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "listener.insertText(symbol)",
                "listener.insertText(symbol); listener.insertText(symbol)",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("KBD003_SYMBOL_LAYOUT", self.rules())

    def test_rejects_editor_writer_capability(self) -> None:
        path = self.root / LAYOUT
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection c;"
            + 'void evil(){c.commitText("x",1);}\n',
            encoding="utf-8",
        )
        self.assertIn("KBD003_SYMBOL_CAPABILITY", self.rules())

    def test_rejects_second_service_binding(self) -> None:
        path = self.root / SERVICE
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "insertKeyboardText(text);",
                "insertKeyboardText(text); insertKeyboardText(text);",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("KBD003_IME_WIRING", self.rules())


if __name__ == "__main__":
    unittest.main()
