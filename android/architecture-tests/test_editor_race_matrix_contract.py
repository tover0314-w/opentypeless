from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from editor_race_matrix_contract import RACES, inspect_android


class EditorRaceMatrixContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.android_root = Path(__file__).resolve().parents[1]

    def test_current_matrix_has_all_twenty_scenarios(self) -> None:
        self.assertEqual([], inspect_android(self.android_root))
        self.assertEqual(tuple(f"R{index:02d}" for index in range(1, 21)), tuple(RACES))

    def test_each_scenario_fails_closed_when_its_primary_test_is_removed(self) -> None:
        for race, evidence_items in RACES.items():
            with self.subTest(race=race), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                paths = {item.path for items in RACES.values() for item in items}
                for relative in paths:
                    source = self.android_root / relative
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
                primary = evidence_items[0]
                target = root / primary.path
                text = target.read_text(encoding="utf-8")
                self.assertIn(primary.method, text)
                target.write_text(
                    text.replace(primary.method, f"removed_{race.lower()}", 1),
                    encoding="utf-8",
                )
                self.assertTrue(any(f"TST002_{race}_METHOD" in item for item in inspect_android(root)))

    def test_scenario_assertion_drift_is_rejected(self) -> None:
        evidence = RACES["R20"][0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for items in RACES.values():
                for item in items:
                    source = self.android_root / item.path
                    target = root / item.path
                    if not target.exists():
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
            target = root / evidence.path
            text = target.read_text(encoding="utf-8")
            token = evidence.tokens[-1]
            self.assertIn(token, text)
            target.write_text(text.replace(token, "assertTrue(true)", 1), encoding="utf-8")
            self.assertTrue(any("TST002_R20_ASSERT" in item for item in inspect_android(root)))


if __name__ == "__main__":
    unittest.main()
