import unittest

from device_preflight import (
    parse_meminfo,
    parse_package,
    parse_start,
    percentile_nearest_rank,
)


class DevicePreflightTest(unittest.TestCase):
    def test_parses_successful_activity_launch(self):
        self.assertEqual(
            {
                "Status": "ok",
                "LaunchState": "COLD",
                "Activity": "com.opentypeless.android/.MainActivity",
                "TotalTime": 1119,
                "WaitTime": 1124,
            },
            parse_start(
                "Status: ok\nLaunchState: COLD\n"
                "Activity: com.opentypeless.android/.MainActivity\n"
                "TotalTime: 1119\nWaitTime: 1124\n"
            ),
        )

    def test_rejects_unmeasured_or_failed_launch(self):
        with self.assertRaises(ValueError):
            parse_start("Status: timeout\nTotalTime: 9000\n")

    def test_parses_memory_summary(self):
        self.assertEqual(
            {"pss_kib": 69852, "rss_kib": 155244, "swap_pss_kib": 270},
            parse_meminfo(
                "TOTAL PSS:    69,852   TOTAL RSS:   155,244 "
                "TOTAL SWAP PSS:      270\n"
            ),
        )

    def test_parses_package_version(self):
        self.assertEqual(
            {"version_name": "0.3.0", "version_code": 3},
            parse_package("versionCode=3 minSdk=26 targetSdk=36\nversionName=0.3.0\n"),
        )

    def test_uses_nearest_rank_for_small_release_samples(self):
        values = [1229, 1231, 1059, 1214, 1576]
        self.assertEqual(1229, percentile_nearest_rank(values, 0.5))
        self.assertEqual(1576, percentile_nearest_rank(values, 0.95))


if __name__ == "__main__":
    unittest.main()
