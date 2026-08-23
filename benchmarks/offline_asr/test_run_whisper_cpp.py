import argparse
import json
import tempfile
import unittest
from pathlib import Path

from benchmarks.offline_asr.run_whisper_cpp import (
    MAX_RESPONSE_BYTES,
    multipart_body,
    parse_response,
    server_command,
    validate_args,
)


class WhisperCppRunnerTest(unittest.TestCase):
    def test_validates_pinned_inputs_and_commit(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ("server", "model", "manifest"):
                (root / name).write_bytes(b"x")
            args = argparse.Namespace(
                server_binary=root / "server",
                model_file=root / "model",
                manifest=root / "manifest",
                runtime_commit="a" * 40,
                num_threads=4,
                request_timeout_seconds=120.0,
                limit=None,
            )
            validate_args(args)
            args.runtime_commit = "main"
            with self.assertRaisesRegex(ValueError, "40-character"):
                validate_args(args)

    def test_server_is_loopback_cpu_only_and_has_fixed_decode_policy(self):
        args = argparse.Namespace(
            server_binary=Path("/tmp/server"),
            model_file=Path("/tmp/model"),
            num_threads=4,
        )
        command = server_command(args, 8123, Path("/tmp/public"))
        self.assertIn("127.0.0.1", command)
        self.assertIn("--no-gpu", command)
        self.assertEqual("auto", command[command.index("--language") + 1])
        self.assertEqual("5", command[command.index("--beam-size") + 1])
        self.assertEqual("5", command[command.index("--best-of") + 1])

    def test_multipart_contains_only_fixed_fields_and_audio(self):
        with tempfile.TemporaryDirectory() as temp:
            audio = Path(temp) / "sample.wav"
            audio.write_bytes(b"RIFF-audio")
            body, content_type = multipart_body(audio)
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        self.assertIn(b'name="temperature"', body)
        self.assertIn(b'name="temperature_inc"', body)
        self.assertIn(b'name="response_format"', body)
        self.assertIn(b'filename="sample.wav"', body)
        self.assertIn(b"RIFF-audio", body)

    def test_response_parser_is_bounded_and_strict(self):
        self.assertEqual("hello", parse_response(json.dumps({"text": " hello "}).encode()))
        with self.assertRaisesRegex(ValueError, "no string text"):
            parse_response(b'{"text": 3}')
        with self.assertRaisesRegex(ValueError, "invalid UTF-8 JSON"):
            parse_response(b"not-json")
        with self.assertRaisesRegex(ValueError, "1 MB"):
            parse_response(b"x" * (MAX_RESPONSE_BYTES + 1))


if __name__ == "__main__":
    unittest.main()
