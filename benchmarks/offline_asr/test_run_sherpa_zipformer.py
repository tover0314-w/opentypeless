from __future__ import annotations

import argparse
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

from benchmarks.offline_asr import run_sherpa_zipformer as runner


def result_record(**overrides: object) -> dict:
    record = {
        "mode": "beam",
        "id": "case",
        "language": "en",
        "category": "general",
        "voice": "speaker-a",
        "condition": "clean",
        "metric": "wer",
        "edit_distance": 1,
        "corrected_edit_distance": 1,
        "reference_units": 1,
        "error_rate": 1.0,
        "corrected_error_rate": 1.0,
        "first_partial_audio_seconds": 0.4,
        "streaming_processing_rtf": 0.1,
        "entity_total": 0,
        "recognized_entity_hits": 0,
        "canonical_entity_hits": 0,
        "false_hotwords": [],
        "false_corrections": [],
    }
    record.update(overrides)
    return record


class RunnerScoringTest(unittest.TestCase):
    def test_decode_timer_spans_stream_accept_result_and_tail_flush(self) -> None:
        class FakeStream:
            def __init__(self) -> None:
                self.accepted_lengths: list[int] = []
                self.finished = False

            def accept_waveform(self, sample_rate: int, samples: np.ndarray) -> None:
                self.accepted_lengths.append(len(samples))

            def input_finished(self) -> None:
                self.finished = True

        class FakeRecognizer:
            def __init__(self) -> None:
                self.stream = FakeStream()
                self.results = iter(["partial", "final"])

            def create_stream(self) -> FakeStream:
                return self.stream

            def is_ready(self, stream: FakeStream) -> bool:
                return False

            def decode_stream(self, stream: FakeStream) -> None:
                raise AssertionError("fake stream is never ready")

            def get_result(self, stream: FakeStream) -> str:
                return next(self.results)

        recognizer = FakeRecognizer()
        with patch.object(runner.time, "perf_counter", side_effect=[10.0, 13.0]):
            decoded = runner.decode(recognizer, 16_000, np.zeros(1_600), 100)

        self.assertEqual("final", decoded["hypothesis"])
        self.assertEqual(3.0, decoded["streaming_processing_seconds"])
        self.assertEqual(0.1, decoded["first_partial_audio_seconds"])
        self.assertEqual([1_600, 8_000], recognizer.stream.accepted_lengths)
        self.assertTrue(recognizer.stream.finished)

    def test_summary_uses_corpus_micro_and_keeps_macro_and_strata(self) -> None:
        records = [
            result_record(),
            result_record(
                id="long",
                category="personal_entity",
                voice="speaker-b",
                condition="noise",
                edit_distance=1,
                corrected_edit_distance=0,
                reference_units=9,
                error_rate=1 / 9,
                corrected_error_rate=0.0,
                first_partial_audio_seconds=None,
                streaming_processing_rtf=0.2,
            ),
        ]

        summary = runner.summarize(records)["beam"]

        self.assertAlmostEqual(0.2, summary["micro_error_rate"])
        self.assertAlmostEqual(0.1, summary["corrected_micro_error_rate"])
        self.assertAlmostEqual((1 + 1 / 9) / 2, summary["macro_utterance_error_rate"])
        self.assertEqual(1, summary["partial_result_count"])
        self.assertEqual(0.5, summary["partial_result_coverage"])
        self.assertEqual({"en"}, set(summary["strata"]["language"]))
        self.assertEqual({"clean", "noise"}, set(summary["strata"]["condition"]))
        self.assertEqual(
            {"general", "personal_entity"}, set(summary["strata"]["category"])
        )
        self.assertEqual({"speaker-a", "speaker-b"}, set(summary["strata"]["voice"]))

    def test_repeated_entity_expectations_require_repeated_mentions(self) -> None:
        expected = ["API", "API"]
        aliases = ["A P I", "A P I"]
        self.assertEqual(0, runner.count_expected_entity_hits("capital", expected, aliases))
        self.assertEqual(1, runner.count_expected_entity_hits("API", expected, aliases))
        self.assertEqual(2, runner.count_expected_entity_hits("API and A P I", expected, aliases))

    def test_zero_hotword_score_is_not_replaced_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            model_dir = Path(temporary_directory)
            for filename in runner.MODEL_FILES.values():
                (model_dir / filename).touch()
            args = argparse.Namespace(
                model_dir=model_dir,
                num_threads=1,
                max_active_paths=4,
            )
            with patch.object(
                runner.sherpa_onnx.OnlineRecognizer,
                "from_transducer",
                return_value=object(),
            ) as constructor:
                runner.create_recognizer(args, "modified_beam_search", 0.0, model_dir / "hotwords")

        self.assertEqual(0.0, constructor.call_args.kwargs["hotwords_score"])

    def test_global_correction_map_is_applied_to_negative_records_too(self) -> None:
        record = {
            "id": "negative",
            "language": "en",
            "category": "control",
            "voice": "speaker",
            "condition": "clean",
            "audio_path": Path("unused.wav"),
            "audio_sha256": "abc",
            "audio_bytes": 1,
            "reference": "please use fewer resources",
            "hotwords": [],
            "bias_phrases": [],
        }
        decoded = {
            "hypothesis": "please use open type less",
            "streaming_processing_seconds": 0.01,
            "first_partial_audio_seconds": None,
        }
        with (
            patch.object(runner, "read_wave", return_value=(16_000, np.zeros(16_000))),
            patch.object(runner, "decode", return_value=decoded),
        ):
            evaluated = runner.evaluate_record(
                record,
                "beam",
                object(),
                100,
                ["OpenTypeless", "OPEN TYPE LESS"],
                [("OpenTypeless", "OPEN TYPE LESS")],
            )

        self.assertEqual("please use OpenTypeless", evaluated["corrected_hypothesis"])
        self.assertEqual(["OpenTypeless"], evaluated["false_corrections"])


if __name__ == "__main__":
    unittest.main()
