from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from benchmarks.offline_asr import compare_results as subject


def payload(audio_set: str = "audio", manifest: str = "manifest", edits: int = 1) -> dict:
    record = {
        "mode": "offline_greedy",
        "category": "public_fleurs",
        "language": "en",
        "edit_distance": edits,
        "reference_units": 10,
    }
    return {
        "metadata": {
            "audio_set_sha256": audio_set,
            "manifest_sha256": manifest,
            "model_type": "sense_voice",
            "model_files": {"model": {"bytes": 1024}},
        },
        "summary": {
            "offline_greedy": {
                "language_error_rate": {"en": edits / 10},
                "streaming_processing_rtf_p50": 0.1,
                "streaming_processing_rtf_p95": 0.2,
                "recognized_entity_recall": 0.3,
                "canonical_entity_recall": 0.4,
            }
        },
        "records": [record],
    }


class CompareResultsTest(unittest.TestCase):
    def write_payload(self, directory: Path, name: str, value: dict) -> Path:
        path = directory / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_compares_only_identical_complete_audio_sets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = self.write_payload(root, "first.json", payload(edits=1))
            second = self.write_payload(root, "second.json", payload(edits=2))
            comparison = subject.compare([f"first={first}", f"second={second}"])

        self.assertEqual(1, comparison["cases"])
        self.assertEqual(
            0.2,
            comparison["candidates"]["second"]["groups"]["public_fleurs:en"][
                "micro_error_rate"
            ],
        )

    def test_accepts_single_model_file_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = payload()
            value["metadata"].pop("model_files")
            value["metadata"].pop("model_type")
            value["metadata"]["runtime"] = "whisper.cpp"
            value["metadata"]["model_file"] = {"bytes": 190_000_000}
            result = self.write_payload(root, "whisper.json", value)
            comparison = subject.compare([f"whisper={result}"])

        candidate = comparison["candidates"]["whisper"]
        self.assertEqual(190_000_000, candidate["model_bytes"])
        self.assertEqual("whisper.cpp", candidate["model_type"])

    def test_rejects_mismatched_audio_sets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = self.write_payload(root, "first.json", payload(audio_set="a"))
            second = self.write_payload(root, "second.json", payload(audio_set="b"))
            with self.assertRaisesRegex(ValueError, "same complete manifest and audio set"):
                subject.compare([f"first={first}", f"second={second}"])

    def test_rejects_duplicate_labels(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = self.write_payload(root, "first.json", payload())
            second = self.write_payload(root, "second.json", payload())
            with self.assertRaisesRegex(ValueError, "labels must be unique"):
                subject.compare([f"same={first}", f"same={second}"])

    def test_requires_mode_selection_for_multi_mode_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = payload()
            value["summary"]["beam"] = value["summary"]["offline_greedy"].copy()
            value["records"][0]["mode"] = "beam"
            result = self.write_payload(root, "result.json", value)
            with self.assertRaisesRegex(ValueError, "select one with LABEL@MODE"):
                subject.compare([f"candidate={result}"])
            comparison = subject.compare([f"candidate@beam={result}"])

        self.assertEqual("beam", comparison["candidates"]["candidate"]["decoder_mode"])


if __name__ == "__main__":
    unittest.main()
