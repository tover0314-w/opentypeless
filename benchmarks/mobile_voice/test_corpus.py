import json
import re
import unittest
from collections import Counter
from pathlib import Path


CORPUS = Path(__file__).with_name("corpus.jsonl")
VALID_LANGUAGES = {"zh-CN", "en-US", "mixed"}
REQUIRED_CATEGORIES = {
    "short",
    "general",
    "code_switch",
    "numbers",
    "formatting",
    "punctuation",
    "entity",
    "entity_control",
    "long_pause",
    "self_correction",
}


def load_cases():
    cases = []
    for line_number, raw in enumerate(CORPUS.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            case = json.loads(raw)
        except json.JSONDecodeError as error:
            raise AssertionError(f"invalid JSON on line {line_number}: {error}") from error
        case["_line"] = line_number
        cases.append(case)
    return cases


class MobileVoiceCorpusTest(unittest.TestCase):
    def setUp(self):
        self.cases = load_cases()

    def test_schema_and_safe_identifiers(self):
        required = {
            "id",
            "language",
            "category",
            "reference",
            "repetitions",
            "dictionary_terms",
            "notes",
        }
        for case in self.cases:
            self.assertEqual(required | {"_line"}, set(case), case)
            self.assertRegex(case["id"], r"^[a-z0-9_]+$", case)
            self.assertIn(case["language"], VALID_LANGUAGES, case)
            self.assertIn(case["category"], REQUIRED_CATEGORIES, case)
            self.assertEqual(case["reference"], case["reference"].strip(), case)
            self.assertTrue(case["reference"], case)
            self.assertIsInstance(case["notes"], str, case)
            self.assertTrue(case["notes"].strip(), case)
            self.assertIsInstance(case["dictionary_terms"], list, case)
            self.assertEqual(len(case["dictionary_terms"]), len(set(case["dictionary_terms"])), case)
            self.assertGreaterEqual(case["repetitions"], 1, case)
            self.assertLessEqual(case["repetitions"], 20, case)

    def test_ids_are_unique(self):
        ids = [case["id"] for case in self.cases]
        self.assertEqual(len(ids), len(set(ids)))

    def test_required_coverage_is_present(self):
        categories = Counter(case["category"] for case in self.cases)
        languages = Counter(case["language"] for case in self.cases)
        self.assertEqual(REQUIRED_CATEGORIES, set(categories))
        self.assertGreaterEqual(languages["zh-CN"], 12)
        self.assertGreaterEqual(languages["en-US"], 12)
        self.assertGreaterEqual(languages["mixed"], 5)
        self.assertGreaterEqual(categories["short"], 10)
        self.assertGreaterEqual(categories["entity_control"], 3)

    def test_short_utterances_have_statistical_repetitions(self):
        short_cases = [case for case in self.cases if case["category"] == "short"]
        self.assertTrue(short_cases)
        for case in short_cases:
            self.assertEqual(20, case["repetitions"], case)
            if case["language"] == "zh-CN":
                han = re.findall(r"[\u3400-\u9fff]", case["reference"])
                self.assertLessEqual(len(han), 4, case)
            elif case["language"] == "en-US":
                self.assertLessEqual(len(case["reference"].split()), 3, case)

    def test_dictionary_positive_and_negative_cases_are_separate(self):
        positives = [case for case in self.cases if case["category"] == "entity"]
        controls = [case for case in self.cases if case["category"] == "entity_control"]
        self.assertTrue(all(case["dictionary_terms"] for case in positives))
        self.assertTrue(all(not case["dictionary_terms"] for case in controls))


if __name__ == "__main__":
    unittest.main()
