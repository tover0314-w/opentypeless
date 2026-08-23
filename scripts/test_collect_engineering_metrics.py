from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import collect_engineering_metrics


class CollectEngineeringMetricsTest(unittest.TestCase):
    def test_source_metrics_are_deterministic_and_advisory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Example.java"
            path.write_text(
                """public final class Example {
  public int decide(int value) {
    if (value > 0 && value < 10) {
      return value > 4 ? 1 : 2;
    }
    return 0;
  }
}
""",
                encoding="utf-8",
            )
            first = collect_engineering_metrics.source_metrics(path)
            second = collect_engineering_metrics.source_metrics(path)
            self.assertEqual(first, second)
            self.assertEqual(1, first["method_count"])
            self.assertEqual(4, first["max_complexity_proxy"])

    def test_collects_xml_totals_and_apk_hashes_without_thresholds(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = root / "android/app/build/test-results/testDebugUnitTest/TEST-Sample.xml"
            result.parent.mkdir(parents=True)
            result.write_text(
                '<testsuite name="Sample" tests="3" failures="1" errors="0" skipped="1"/>\n',
                encoding="utf-8",
            )
            apk = root / collect_engineering_metrics.APK_PATHS[0]
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"apk")
            values = collect_engineering_metrics.collect(root)
            self.assertTrue(values["advisory_only"])
            self.assertEqual(3, values["test_results"]["tests"])
            self.assertEqual(1, values["test_results"]["failures"])
            self.assertEqual(3, values["apk_artifacts"][0]["bytes"])
            self.assertEqual(64, len(values["apk_artifacts"][0]["sha256"]))

    def test_missing_build_artifacts_are_recorded_not_failed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            values = collect_engineering_metrics.collect(Path(directory))
            self.assertTrue(all(not item["available"] for item in values["apk_artifacts"]))
            self.assertTrue(all(not item["available"] for item in values["key_sources"].values()))
            self.assertEqual(0, values["test_results"]["tests"])


if __name__ == "__main__":
    unittest.main()
