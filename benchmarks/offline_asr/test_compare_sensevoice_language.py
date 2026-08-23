import unittest

from benchmarks.offline_asr.compare_sensevoice_language import (
    render_markdown,
    unique_languages,
)


def summary(overall: float, zh: float, en: float, mixed: float) -> dict:
    return {
        "micro_error_rate": overall,
        "language_error_rate": {"zh": zh, "en": en, "mixed": mixed},
    }


class SenseVoiceLanguageComparisonTest(unittest.TestCase):
    def test_rejects_duplicate_languages(self) -> None:
        with self.assertRaisesRegex(ValueError, "unique"):
            unique_languages(["zh", "zh"])

    def test_report_keeps_paired_direction_explicit(self) -> None:
        payload = {
            "baseline_summary": summary(0.2, 0.1, 0.2, 0.3),
            "language_results": {
                "zh": {
                    "summary": summary(0.15, 0.08, 0.3, 0.2),
                    "paired_counts": {"improved": 4, "worsened": 2, "tied": 10},
                }
            },
        }
        report = render_markdown(payload)
        self.assertIn("| auto | 20.00%", report)
        self.assertIn("| zh | 15.00%", report)
        self.assertIn("4 / 2 / 10", report)


if __name__ == "__main__":
    unittest.main()
