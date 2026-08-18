from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from run_android_candidate import (
    ACCURACY_REPORT_SHA256,
    AUDIO_ARTIFACT,
    MODEL_ID,
    STATUS_KEYS,
    ZIPFORMER_REPORT_SHA256,
    Artifact,
    _load_json,
    parse_instrumentation,
    require_file,
    write_json_atomic,
)


def successful_output(**overrides: str) -> str:
    values = {
        key: "1"
        for key in STATUS_KEYS
    }
    values.update({
        "str004_candidate_id": MODEL_ID,
        "str004_audio_sha256": AUDIO_ARTIFACT.sha256,
        "str004_contains_audio": "false",
        "str004_contains_transcript": "false",
    })
    values.update(overrides)
    lines = [f"INSTRUMENTATION_STATUS: {key}={values[key]}" for key in sorted(values)]
    lines.extend(("INSTRUMENTATION_STATUS_CODE: 2", "OK (1 test)", "INSTRUMENTATION_CODE: -1"))
    return "\n".join(lines)


class AndroidCandidateBenchmarkTest(unittest.TestCase):
    def test_parses_exact_content_free_metrics(self):
        parsed = parse_instrumentation(successful_output())
        self.assertEqual(MODEL_ID, parsed["str004_candidate_id"])
        self.assertEqual(1, parsed["str004_fresh_peak_pss_kib"])
        self.assertEqual("false", parsed["str004_contains_transcript"])

    def test_rejects_failure_missing_duplicate_or_content(self):
        with self.assertRaisesRegex(ValueError, "did not complete"):
            parse_instrumentation("FAILURES!!!\nINSTRUMENTATION_CODE: -1")
        missing = successful_output().replace(
            "INSTRUMENTATION_STATUS: str004_fresh_peak_pss_kib=1\n", ""
        )
        with self.assertRaisesRegex(ValueError, "omitted"):
            parse_instrumentation(missing)
        duplicate = successful_output().replace(
            "INSTRUMENTATION_STATUS_CODE: 2",
            "INSTRUMENTATION_STATUS: str004_model_bytes=1\nINSTRUMENTATION_STATUS_CODE: 2",
        )
        with self.assertRaisesRegex(ValueError, "unexpected"):
            parse_instrumentation(duplicate)
        with self.assertRaisesRegex(ValueError, "export content"):
            parse_instrumentation(successful_output(str004_contains_transcript="true"))

    def test_rejects_wrong_candidate_audio_and_malformed_metric(self):
        with self.assertRaisesRegex(ValueError, "different candidate"):
            parse_instrumentation(successful_output(str004_candidate_id="other"))
        with self.assertRaisesRegex(ValueError, "different audio"):
            parse_instrumentation(successful_output(str004_audio_sha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "malformed"):
            parse_instrumentation(successful_output(str004_warm_total_ms_p50="-1"))

    def test_file_verification_checks_size_and_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "artifact"
            path.write_bytes(b"pinned")
            spec = Artifact("artifact", 6, hashlib.sha256(b"pinned").hexdigest())
            require_file(path, spec)
            with self.assertRaisesRegex(ValueError, "size"):
                require_file(path, Artifact("artifact", 7, spec.sha256))
            with self.assertRaisesRegex(ValueError, "hash"):
                require_file(path, Artifact("artifact", 6, "0" * 64))

    def test_pinned_accuracy_reports_and_atomic_writer(self):
        repository = Path(__file__).resolve().parents[2]
        accuracy = _load_json(
            repository
            / "benchmarks/offline_asr/reports/2026-08-12-streaming-paraformer-summary.json",
            ACCURACY_REPORT_SHA256,
        )
        zipformer = _load_json(
            repository / "benchmarks/offline_asr/reports/2026-08-09-zipformer-summary.json",
            ZIPFORMER_REPORT_SHA256,
        )
        self.assertEqual(200, accuracy["corpus"]["cases"])
        self.assertEqual("reject_as_bundled_default", zipformer["decision"])
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "report.json"
            write_json_atomic(output, {"task_id": "STR-004"})
            self.assertEqual({"task_id": "STR-004"}, json.loads(output.read_text()))


if __name__ == "__main__":
    unittest.main()
