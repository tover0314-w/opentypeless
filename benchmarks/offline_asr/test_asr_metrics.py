import unittest

from benchmarks.offline_asr.asr_metrics import (
    apply_alias_corrections,
    contains_entity,
    count_entity_occurrences,
    error_details,
    error_rate,
    model_hotword_phrase,
    normalize_characters,
    normalize_mixed_units,
    normalize_words,
    percentile,
)


class AsrMetricsTest(unittest.TestCase):
    def test_normalizes_english_case_and_punctuation(self) -> None:
        self.assertEqual("hello world", normalize_words(" Hello, WORLD! "))

    def test_normalizes_full_width_and_chinese_punctuation(self) -> None:
        self.assertEqual("今天api正常", normalize_characters("今天，ＡＰＩ 正常。"))

    def test_english_uses_word_error_rate(self) -> None:
        self.assertAlmostEqual(0.25, error_rate("one two three four", "one too three four", "en"))

    def test_chinese_uses_character_error_rate(self) -> None:
        self.assertAlmostEqual(0.25, error_rate("语音输入", "语音输出", "zh"))

    def test_mixed_uses_han_characters_and_contiguous_english_words(self) -> None:
        self.assertEqual(
            ["请", "用", "opentypeless", "开", "发", "android"],
            normalize_mixed_units("请用 OpenTypeless 开发 Android"),
        )
        details = error_details(
            "请用 OpenTypeless 开发 Android",
            "请用 Open Type Less 开发 Android",
            "mixed",
        )
        self.assertEqual("mer", details["metric"])
        self.assertEqual(3, details["edits"])
        self.assertEqual(6, details["reference_units"])
        self.assertEqual(0.5, details["error_rate"])

    def test_entity_match_ignores_spaces_case_and_punctuation(self) -> None:
        self.assertTrue(contains_entity("OPEN TYPELESS settings", "OpenTypeless"))
        self.assertFalse(contains_entity("open type settings", "OpenTypeless"))

    def test_entity_match_does_not_count_latin_substrings(self) -> None:
        self.assertFalse(contains_entity("capital expenditure", "API"))
        self.assertFalse(contains_entity("OpenTypelesser", "OpenTypeless"))
        self.assertTrue(contains_entity("请调用API接口", "API"))
        self.assertEqual(2, count_entity_occurrences("API，然后再调用 api。", "API"))

    def test_applies_only_explicit_pronunciation_aliases(self) -> None:
        self.assertEqual(
            "OpenTypeless uses PostgreSQL",
            apply_alias_corrections(
                "open type-less uses Postgres Q L",
                ["OpenTypeless", "PostgreSQL"],
                ["OPEN TYPE LESS", "POSTGRES Q L"],
            ),
        )
        self.assertEqual(
            "the open type uses less memory",
            apply_alias_corrections(
                "the open type uses less memory", ["OpenTypeless"], ["OPEN TYPE LESS"]
            ),
        )
        self.assertEqual(
            "请用OpenTypeless输入",
            apply_alias_corrections(
                "请用open type less输入", ["OpenTypeless"], ["OPEN TYPE LESS"]
            ),
        )
        self.assertEqual(
            "opentypelesser",
            apply_alias_corrections(
                "opentypelesser", ["OpenTypeless"], ["OPEN TYPE LESS"]
            ),
        )

    def test_alias_correction_requires_aligned_pairs(self) -> None:
        with self.assertRaises(ValueError):
            apply_alias_corrections("hello", ["one"], [])

    def test_percentile_interpolates_and_handles_empty_input(self) -> None:
        self.assertEqual(0.0, percentile([], 0.95))
        self.assertAlmostEqual(3.7, percentile([1.0, 2.0, 3.0, 4.0], 0.9))

    def test_model_hotwords_are_uppercased_for_case_sensitive_model(self) -> None:
        self.assertEqual("OPEN TYPE LESS", model_hotword_phrase("Open Type Less"))
        self.assertEqual("小米 SU7", model_hotword_phrase("小米 su7"))


if __name__ == "__main__":
    unittest.main()
