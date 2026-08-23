from __future__ import annotations

import unittest
from unittest import mock

import numpy as np

try:
    from benchmarks.offline_asr import run_sherpa_streaming_paraformer as runner
except ModuleNotFoundError:  # The tool test may run without optional runtime wheels.
    runner = None


@unittest.skipIf(runner is None, "optional sherpa-onnx runtime is unavailable")
class StreamingParaformerRunnerTest(unittest.TestCase):
    def test_common_prefix_handles_unicode_code_points(self):
        self.assertEqual(3, runner._common_prefix_code_points("你好A旧", "你好A新"))
        self.assertEqual(1, runner._common_prefix_code_points("😀旧", "😀新"))

    def test_decode_tracks_earlier_text_revision_and_explicit_flush(self):
        stream = mock.Mock()
        stream.get_option.return_value = None
        recognizer = mock.Mock()
        recognizer.create_stream.return_value = stream
        recognizer.is_ready.return_value = False
        recognizer.get_result.side_effect = ["你", "你好", "您好", "您好。"]

        decoded = runner.decode(
            recognizer,
            16_000,
            np.zeros(16_000 * 3, dtype=np.float32),
            1_000,
        )

        self.assertEqual("您好。", decoded["hypothesis"])
        self.assertEqual(4, decoded["partial_revision_count"])
        self.assertEqual(1, decoded["earlier_text_revision_count"])
        self.assertEqual(1.0, decoded["first_partial_audio_seconds"])
        stream.set_option.assert_called_once_with("is_final", "1")
        stream.input_finished.assert_called_once_with()

    def test_decode_rejects_wrong_sample_rate_and_chunk(self):
        with self.assertRaisesRegex(ValueError, "16 kHz"):
            runner.decode(mock.Mock(), 8_000, np.zeros(1, dtype=np.float32), 40)
        with self.assertRaisesRegex(ValueError, "chunk_ms"):
            runner.decode(mock.Mock(), 16_000, np.zeros(1, dtype=np.float32), 0)


if __name__ == "__main__":
    unittest.main()
