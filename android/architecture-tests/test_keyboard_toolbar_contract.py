from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from keyboard_toolbar_contract import HOST_TEST, SERVICE, TOOLBAR, inspect_android


class KeyboardToolbarContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (TOOLBAR, SERVICE, HOST_TEST):
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

    def test_rejects_sub_forty_eight_touch_target(self) -> None:
        self.mutate(TOOLBAR, "MINIMUM_TOUCH_TARGET_DP = 48", "MINIMUM_TOUCH_TARGET_DP = 40")
        self.assertIn("KBD006_SLOT_CONTRACT", self.rules())

    def test_rejects_unbounded_primary_actions(self) -> None:
        self.mutate(TOOLBAR, "MAXIMUM_PRIMARY_ACTIONS = 2", "MAXIMUM_PRIMARY_ACTIONS = 20")
        self.assertIn("KBD006_SLOT_CONTRACT", self.rules())

    def test_rejects_editor_capability_in_toolbar(self) -> None:
        path = self.root / TOOLBAR
        path.write_text(path.read_text(encoding="utf-8")
                        + "\nandroid.view.inputmethod.InputConnection connection;\n",
                        encoding="utf-8")
        self.assertIn("KBD006_TOOLBAR_CAPABILITY", self.rules())

    def test_rejects_duplicate_registration_path(self) -> None:
        self.mutate(
            TOOLBAR,
            "placements.put(placementId, placement);",
            "placements.put(placementId, placement); placements.put(placementId, placement);",
        )
        self.assertIn("KBD006_SLOT_CONTRACT", self.rules())

    def test_rejects_undo_promoted_back_to_primary_toolbar(self) -> None:
        self.mutate(
            SERVICE,
            "private Button moreButton;",
            "private Button moreButton; private Button undoButton;",
        )
        self.assertIn("KBD006_SERVICE_WIRING", self.rules())

    def test_rejects_third_primary_service_action(self) -> None:
        self.mutate(
            SERVICE,
            'attachOverflowAnchor("more", moreButton);',
            'attachPrimaryAction("more", moreButton, 48);',
        )
        self.assertIn("KBD006_SERVICE_WIRING", self.rules())

    def test_rejects_missing_system_touch_target_assertion(self) -> None:
        self.mutate(HOST_TEST, "bounds.height() >= minimumPx", "bounds.height() >= 1")
        self.assertIn("KBD006_SYSTEM_TEST", self.rules())


if __name__ == "__main__":
    unittest.main()
