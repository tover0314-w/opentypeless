from __future__ import annotations

import hashlib
from pathlib import Path
import tempfile
import unittest

import benchmark_keyboard_routes as benchmark


class BenchmarkKeyboardRoutesTest(unittest.TestCase):
    def test_percentile_uses_nearest_rank_and_preserves_samples(self) -> None:
        values = [9, 1, 5, 3, 7]
        self.assertEqual(5, benchmark.percentile(values, 0.50))
        self.assertEqual(9, benchmark.percentile(values, 0.95))
        self.assertEqual([9, 1, 5, 3, 7], benchmark.summarize(values)["samples"])

    def test_parses_successful_instrumentation_metric(self) -> None:
        output = "\n".join(
            (
                'INSTRUMENTATION_STATUS: ksp008_metric={"route":"A","metric":"qwerty_transaction","p95_us":123}',
                "INSTRUMENTATION_RESULT: stream=OK (1 test)",
                "INSTRUMENTATION_CODE: -1",
            )
        )
        self.assertEqual("qwerty_transaction", benchmark.parse_instrumentation(output)[0]["metric"])

    def test_rejects_instrumentation_failure_even_with_metric(self) -> None:
        output = "\n".join(
            (
                'INSTRUMENTATION_STATUS: ksp008_metric={"metric":"qwerty_transaction"}',
                "FAILURES!!!",
                "INSTRUMENTATION_CODE: -1",
            )
        )
        with self.assertRaises(benchmark.BenchmarkError):
            benchmark.parse_instrumentation(output)

    def test_parses_cold_activity_initial_display(self) -> None:
        output = "\n".join(
            (
                "Status: ok",
                "LaunchState: COLD",
                "TotalTime: 812",
                "WaitTime: 819",
                "Complete",
            )
        )
        self.assertEqual(
            {
                "launch_state": "COLD",
                "initial_display_total_ms": 812,
                "command_wait_ms": 819,
            },
            benchmark.parse_activity_start(output),
        )

    def test_rejects_warm_or_inconsistent_activity_timing(self) -> None:
        with self.assertRaises(benchmark.BenchmarkError):
            benchmark.parse_activity_start(
                "Status: ok\nLaunchState: WARM\nTotalTime: 10\nWaitTime: 11\n"
            )
        with self.assertRaises(benchmark.BenchmarkError):
            benchmark.parse_activity_start(
                "Status: ok\nLaunchState: COLD\nTotalTime: 12\nWaitTime: 11\n"
            )

    def test_artifact_record_binds_exact_bytes_and_rejects_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "candidate.apk"
            artifact.write_bytes(b"candidate")
            value = benchmark.artifact_record(artifact)
            self.assertEqual(9, value["bytes"])
            self.assertEqual(hashlib.sha256(b"candidate").hexdigest(), value["sha256"])
            self.assertEqual("candidate.apk", value["name"])
            self.assertNotIn("path", value)
            link = root / "linked.apk"
            link.symlink_to(artifact)
            with self.assertRaises(benchmark.BenchmarkError):
                benchmark.artifact_record(link)

    def test_parses_total_pss_or_reports_absence(self) -> None:
        self.assertEqual(12345, benchmark.parse_total_pss(" TOTAL PSS: 12,345 TOTAL RSS: 20,000\n"))
        self.assertIsNone(benchmark.parse_total_pss("No process found\n"))

    def test_device_snapshot_never_serializes_the_adb_serial(self) -> None:
        class FakeAdb:
            def shell(self, *arguments: str) -> str:
                if arguments[0] == "getprop":
                    return "test-value\n"
                if arguments[:4] == ("settings", "get", "secure", "default_input_method"):
                    return "example/.Ime\n"
                if arguments[:4] == ("settings", "get", "system", "screen_off_timeout"):
                    return "600000\n"
                if arguments[:4] == (
                    "settings", "get", "global", "stay_on_while_plugged_in"
                ):
                    return "0\n"
                raise AssertionError(arguments)

        value = benchmark._device_properties(FakeAdb())  # type: ignore[arg-type]
        self.assertEqual("explicit_serial_redacted", value["adb_target"])
        self.assertNotIn("serial", value)
        self.assertNotIn("be4e2015", str(value))


if __name__ == "__main__":
    unittest.main()
