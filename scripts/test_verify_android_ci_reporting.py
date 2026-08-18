from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import verify_android_ci_reporting


class VerifyAndroidCiReportingTest(unittest.TestCase):
    def test_accepts_split_stages_and_downloadable_reports(self) -> None:
        self.assertEqual(set(), self.rules(self.valid_fixture()))

    def test_rejects_missing_or_merged_stage_entrypoints(self) -> None:
        files = self.valid_fixture()
        cases = (
            ("unit stage", "scripts/verify_android.sh unit", "scripts/verify_android.sh", "BLD004_STAGE_TOPOLOGY"),
            ("instrumentation", "scripts/verify_android.sh instrumentation", "cd android && ./gradlew connectedDebugAndroidTest", "BLD004_INSTRUMENTATION_STAGE"),
            ("script dispatcher", "run_gradle lintRelease", "./gradlew lintRelease", "BLD004_STAGE_SCRIPT"),
            ("local gate", "verify_android_ci_reporting.py", "removed_ci_reporting.py", "BLD004_LOCAL_GATE"),
        )
        for name, old, new, rule in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                path = "scripts/verify_android.sh" if name in {"script dispatcher", "local gate"} else ".github/workflows/ci.yml"
                mutated[path] = mutated[path].replace(old, new, 1)
                self.assertIn(rule, self.rules(mutated))

    def test_rejects_missing_unconditional_or_nonunique_reports(self) -> None:
        files = self.valid_fixture()
        cases = (
            ("unit always", "if: always()", "if: success()", "BLD004_UNIT_REPORT", 1),
            ("lint xml", "android/**/build/reports/lint-results-*.xml", "android/app/lint.txt", "BLD004_LINT_REPORT", 1),
            ("apk missing", "if-no-files-found: error", "if-no-files-found: warn", "BLD004_APK_ARTIFACT", 2),
            ("metrics output", "scripts/verify_android.sh metrics", "scripts/verify_android.sh assemble", "BLD009_METRICS_GENERATION", 1),
            ("metrics artifact", "name: android-engineering-metrics", "name: metrics", "BLD009_METRICS_ARTIFACT", 1),
            ("matrix name", "android-instrumentation-api-${{ matrix.api_level }}", "android-instrumentation", "BLD004_INSTRUMENTATION_REPORT", 1),
            ("instrumentation always", "if: always()", "if: success()", "BLD004_INSTRUMENTATION_REPORT", 3),
        )
        for name, old, new, rule, occurrence in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                text = mutated[".github/workflows/ci.yml"]
                index = -1
                start = 0
                for _ in range(occurrence):
                    index = text.find(old, start)
                    self.assertGreaterEqual(index, 0)
                    start = index + len(old)
                mutated[".github/workflows/ci.yml"] = text[:index] + new + text[index + len(old):]
                self.assertIn(rule, self.rules(mutated))

    @staticmethod
    def rules(files: dict[str, str]) -> set[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return {
                violation.rule
                for violation in verify_android_ci_reporting.inspect_android_ci_reporting(root)
            }

    @staticmethod
    def valid_fixture() -> dict[str, str]:
        workflow = """name: CI
jobs:
  check-android:
    steps:
      - name: Android preflight and static policy checks
        run: scripts/verify_android.sh preflight
      - name: Android unit and architecture tests
        run: scripts/verify_android.sh unit
      - name: Upload Android unit test reports
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: android-unit-test-reports
          path: |
            android/**/build/test-results/**/*.xml
            android/**/build/reports/tests/**
          if-no-files-found: warn
          retention-days: 14
      - name: Android release lint
        run: scripts/verify_android.sh lint
      - name: Upload Android lint reports
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: android-lint-reports
          path: |
            android/**/build/reports/lint-results-*.html
            android/**/build/reports/lint-results-*.xml
            android/**/build/reports/lint-results-*.sarif
          if-no-files-found: warn
          retention-days: 14
      - name: Assemble Android APKs
        run: scripts/verify_android.sh assemble
      - name: Generate Android engineering metrics
        run: scripts/verify_android.sh metrics
      - name: Upload Android engineering metrics
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: android-engineering-metrics
          path: android/build/reports/engineering-metrics/engineering-metrics.json
          if-no-files-found: error
          retention-days: 14
      - name: Upload Android APKs
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: android-apks
          path: android/**/build/outputs/apk/**/*.apk
          if-no-files-found: error
          retention-days: 14
  test-android-emulator:
    steps:
      - name: Run Android instrumentation tests
        with:
          script: scripts/verify_android.sh instrumentation
      - name: Upload Android instrumentation reports
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: android-instrumentation-api-${{ matrix.api_level }}
          path: |
            android/**/build/outputs/androidTest-results/**
            android/**/build/reports/androidTests/**
          if-no-files-found: warn
          retention-days: 14
"""
        script = """STAGE="${1:-all}"
run_preflight() {
  python3 "$REPO_ROOT/scripts/verify_android_ci_reporting.py" --repo-root "$REPO_ROOT"
}
run_gradle() {
  true
}
run_metrics() {
  python3 "$REPO_ROOT/scripts/collect_engineering_metrics.py" \
    --repo-root "$REPO_ROOT" \
    --output "$ANDROID_DIR/build/reports/engineering-metrics/engineering-metrics.json"
}
case "$STAGE" in
  preflight)
    run_preflight
    ;;
  unit)
    run_gradle clean :architecture-gate:check testDebugUnitTest
    ;;
  lint)
    run_gradle lintRelease
    ;;
  assemble)
    run_gradle assembleDebug assembleRelease assembleDebugAndroidTest
    ;;
  metrics)
    run_metrics
    ;;
  instrumentation)
    run_gradle connectedDebugAndroidTest
    ;;
esac
"""
        return {
            ".github/workflows/ci.yml": workflow,
            "scripts/verify_android.sh": script,
        }


if __name__ == "__main__":
    unittest.main()
