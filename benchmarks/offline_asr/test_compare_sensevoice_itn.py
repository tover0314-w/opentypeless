from __future__ import annotations

import unittest

from benchmarks.offline_asr.compare_sensevoice_itn import paired_counts


class SenseVoiceItnComparisonTest(unittest.TestCase):
    def test_paired_counts_separates_hypothesis_and_error_changes(self) -> None:
        baseline = [
            {"id": "a", "reference": "one", "hypothesis": "won", "edit_distance": 1},
            {"id": "b", "reference": "two", "hypothesis": "two", "edit_distance": 0},
            {"id": "c", "reference": "three", "hypothesis": "3", "edit_distance": 1},
        ]
        candidate = [
            {"id": "a", "reference": "one", "hypothesis": "one", "edit_distance": 0},
            {"id": "b", "reference": "two", "hypothesis": "Two.", "edit_distance": 0},
            {"id": "c", "reference": "three", "hypothesis": "free", "edit_distance": 2},
        ]

        self.assertEqual(
            {"changed": 3, "improved": 1, "worsened": 1, "tied": 1},
            paired_counts(baseline, candidate),
        )

    def test_paired_counts_rejects_reordered_records(self) -> None:
        with self.assertRaisesRegex(ValueError, "order"):
            paired_counts(
                [{"id": "a", "reference": "x", "hypothesis": "x", "edit_distance": 0}],
                [{"id": "b", "reference": "x", "hypothesis": "x", "edit_distance": 0}],
            )


if __name__ == "__main__":
    unittest.main()
