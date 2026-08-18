from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from keyboard_shell_contract import SHELL_ROOT, SERVICE, inspect_android


class KeyboardShellContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        target_shell = self.root / SHELL_ROOT
        target_shell.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(android / SHELL_ROOT, target_shell)
        target_service = self.root / SERVICE
        target_service.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(android / SERVICE, target_service)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_editor_capability_or_direct_writer_in_shell(self) -> None:
        path = self.root / SHELL_ROOT / "KeyboardShellFrame.java"
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\n// malicious\nandroid.view.inputmethod.InputConnection connection;\n"
            + "void write(){ connection.commitText(\"x\", 1); }\n",
            encoding="utf-8",
        )
        self.assertIn(
            "KBD001_SHELL_CAPABILITY",
            {item.rule for item in inspect_android(self.root)},
        )

    def test_rejects_selected_route_catch_and_fallback(self) -> None:
        path = self.root / SHELL_ROOT / "KeyboardShellSelector.java"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "T selected = switch (route)",
                "try { throw new RuntimeException(); } catch (RuntimeException ignored) {}\n"
                "        T selected = switch (route)",
            ),
            encoding="utf-8",
        )
        self.assertIn(
            "KBD001_EXCLUSIVE_SELECTOR",
            {item.rule for item in inspect_android(self.root)},
        )

    def test_rejects_default_off_or_async_flag_write(self) -> None:
        path = self.root / SHELL_ROOT / "KeyboardShellConfig.java"
        path.write_text(
            path.read_text(encoding="utf-8")
            .replace("preferences.getBoolean(ROUTE_A_ENABLED, true)",
                     "preferences.getBoolean(ROUTE_A_ENABLED, false)", 1)
            .replace("migration.commit()", "migration.apply()", 1),
            encoding="utf-8",
        )
        self.assertIn(
            "KBD001_FEATURE_FLAG_SHAPE",
            {item.rule for item in inspect_android(self.root)},
        )

    def test_rejects_unreviewed_shell_source(self) -> None:
        (self.root / SHELL_ROOT / "HiddenWriter.java").write_text(
            "package com.opentypeless.android.keyboard.shell;\n",
            encoding="utf-8",
        )
        self.assertIn(
            "KBD001_SHELL_SOURCE_SET",
            {item.rule for item in inspect_android(self.root)},
        )


if __name__ == "__main__":
    unittest.main()
