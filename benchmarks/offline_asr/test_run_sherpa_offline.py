from __future__ import annotations

import argparse
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

from benchmarks.offline_asr import run_sherpa_offline as runner


class OfflineRunnerTest(unittest.TestCase):
    def test_decode_records_processing_time_and_no_partial(self) -> None:
        class Result:
            text = "  hello world  "

        class Stream:
            def accept_waveform(self, sample_rate, samples) -> None:
                self.accepted = (sample_rate, len(samples))

            @property
            def result(self):
                return Result()

        class Recognizer:
            def create_stream(self):
                self.stream = Stream()
                return self.stream

            def decode_stream(self, stream) -> None:
                self.decoded = stream

        recognizer = Recognizer()
        with patch.object(runner.time, "perf_counter", side_effect=[2.0, 2.25]):
            result = runner.decode(recognizer, 16_000, np.zeros(8_000))

        self.assertEqual("hello world", result["hypothesis"])
        self.assertEqual(0.25, result["streaming_processing_seconds"])
        self.assertIsNone(result["first_partial_audio_seconds"])
        self.assertEqual((16_000, 8_000), recognizer.stream.accepted)

    def test_sense_voice_uses_auto_language_without_itn(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_dir = Path(directory)
            for filename in runner.MODEL_FILES:
                (model_dir / filename).touch()
            args = argparse.Namespace(
                model_type="sense_voice", model_dir=model_dir, num_threads=2
            )
            with patch.object(
                runner.sherpa_onnx.OfflineRecognizer,
                "from_sense_voice",
                return_value=object(),
            ) as constructor:
                runner.create_recognizer(args)

        self.assertEqual("auto", constructor.call_args.kwargs["language"])
        self.assertFalse(constructor.call_args.kwargs["use_itn"])
        self.assertEqual(2, constructor.call_args.kwargs["num_threads"])

    def test_paraformer_uses_int8_model(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_dir = Path(directory)
            for filename in runner.MODEL_FILES:
                (model_dir / filename).touch()
            args = argparse.Namespace(
                model_type="paraformer", model_dir=model_dir, num_threads=4
            )
            with patch.object(
                runner.sherpa_onnx.OfflineRecognizer,
                "from_paraformer",
                return_value=object(),
            ) as constructor:
                runner.create_recognizer(args)

        self.assertTrue(constructor.call_args.kwargs["paraformer"].endswith("model.int8.onnx"))

    def test_global_personalization_rejects_ambiguous_aliases(self) -> None:
        records = [
            {
                "hotwords": ["OpenTypeless"],
                "bias_phrases": ["OPEN TYPE LESS"],
                "correction_aliases": ["OPEN TYPE LESS"],
            },
            {
                "hotwords": ["Open TypeScript"],
                "bias_phrases": ["OPEN TYPE LESS"],
                "correction_aliases": ["OPEN TYPE LESS"],
            },
        ]
        with self.assertRaisesRegex(ValueError, "ambiguous correction alias"):
            runner.global_personalization(records)


if __name__ == "__main__":
    unittest.main()
