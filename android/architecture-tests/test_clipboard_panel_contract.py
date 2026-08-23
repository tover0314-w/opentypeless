from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from clipboard_panel_contract import (
    CLIPBOARD_ROOT,
    EXPECTED_FILES,
    HOST_TEST,
    PANEL_TEST,
    READER_TEST,
    SERVICE,
    SNAPSHOT_TEST,
    inspect_android,
)


class ClipboardPanelContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for name in EXPECTED_FILES:
            relative = CLIPBOARD_ROOT / name
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)
        for relative in (SERVICE, SNAPSHOT_TEST, READER_TEST, PANEL_TEST, HOST_TEST):
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

    def test_rejects_background_clipboard_listener(self) -> None:
        reader = CLIPBOARD_ROOT / "SystemClipboardReader.java"
        path = self.root / reader
        path.write_text(
            path.read_text(encoding="utf-8") + "\nmanager.addPrimaryClipChangedListener(null);\n",
            encoding="utf-8",
        )
        self.assertIn("KBD011_EXPLICIT_READER", self.rules())

    def test_rejects_uri_coercion(self) -> None:
        reader = CLIPBOARD_ROOT / "SystemClipboardReader.java"
        self.mutate(reader, "clip.getItemAt(0).getText()", "clip.getItemAt(0).coerceToText(null)")
        self.assertIn("KBD011_EXPLICIT_READER", self.rules())

    def test_rejects_editor_writer_in_panel(self) -> None:
        panel = CLIPBOARD_ROOT / "KeyboardClipboardPanel.java"
        path = self.root / panel
        path.write_text(
            path.read_text(encoding="utf-8") + "\neditor.commitText(text, 1);\n",
            encoding="utf-8",
        )
        self.assertIn("KBD011_PANEL_CAPABILITY", self.rules())

    def test_rejects_missing_sensitive_projection(self) -> None:
        self.mutate(
            SERVICE,
            "if (!keyboardToolbarPrivacy.clipboardVisible()) hideClipboardPanel();",
            "if (false) hideClipboardPanel();",
        )
        self.assertIn("KBD011_SERVICE_WIRING", self.rules())

    def test_rejects_direct_paste_outside_typing_facade(self) -> None:
        self.mutate(
            SERVICE,
            "insertKeyboardText(snapshot.text());",
            "routeTypingText(snapshot.text());",
        )
        self.assertIn("KBD011_SERVICE_WIRING", self.rules())


if __name__ == "__main__":
    unittest.main()
