#!/usr/bin/env python3
"""Fail closed when Android CI stages or downloadable reports drift."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path


CI_WORKFLOW = Path(".github/workflows/ci.yml")
VERIFY_SCRIPT = Path("scripts/verify_android.sh")
UPLOAD_ARTIFACT = (
    "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1"
)


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _step(text: str, name: str) -> str | None:
    marker = f"      - name: {name}\n"
    start = text.find(marker)
    if start < 0:
        return None
    end = text.find("\n      - ", start + len(marker))
    return text[start:] if end < 0 else text[start:end]


def _require_step(
    text: str,
    name: str,
    fragments: tuple[str, ...],
    rule: str,
    violations: list[Violation],
) -> None:
    block = _step(text, name)
    if block is None or any(fragment not in block for fragment in fragments):
        violations.append(Violation(rule, name))


def inspect_android_ci_reporting(repo_root: Path) -> tuple[Violation, ...]:
    root = repo_root.resolve()
    violations: list[Violation] = []
    workflow = root / CI_WORKFLOW
    script_path = root / VERIFY_SCRIPT
    if not workflow.is_file() or workflow.is_symlink():
        return (Violation("BLD004_CI_WORKFLOW", str(CI_WORKFLOW)),)
    if not script_path.is_file() or script_path.is_symlink():
        return (Violation("BLD004_VERIFY_SCRIPT", str(VERIFY_SCRIPT)),)

    workflow_text = workflow.read_text(encoding="utf-8")
    script = script_path.read_text(encoding="utf-8")

    stages = ("preflight", "unit", "lint", "assemble")
    positions = []
    for stage in stages:
        invocation = f"run: scripts/verify_android.sh {stage}"
        if workflow_text.count(invocation) != 1:
            violations.append(Violation("BLD004_STAGE_TOPOLOGY", stage))
        positions.append(workflow_text.find(invocation))
    if any(position < 0 for position in positions) or positions != sorted(positions):
        violations.append(Violation("BLD004_STAGE_ORDER", str(positions)))
    if workflow_text.count("script: scripts/verify_android.sh instrumentation") != 1:
        violations.append(Violation("BLD004_INSTRUMENTATION_STAGE", "instrumentation"))

    _require_step(
        workflow_text,
        "Upload Android unit test reports",
        (
            "if: always()",
            f"uses: {UPLOAD_ARTIFACT}",
            "name: android-unit-test-reports",
            "android/**/build/test-results/**/*.xml",
            "android/**/build/reports/tests/**",
            "if-no-files-found: warn",
            "retention-days: 14",
        ),
        "BLD004_UNIT_REPORT",
        violations,
    )
    _require_step(
        workflow_text,
        "Upload Android lint reports",
        (
            "if: always()",
            f"uses: {UPLOAD_ARTIFACT}",
            "name: android-lint-reports",
            "android/**/build/reports/lint-results-*.html",
            "android/**/build/reports/lint-results-*.xml",
            "android/**/build/reports/lint-results-*.sarif",
            "if-no-files-found: warn",
            "retention-days: 14",
        ),
        "BLD004_LINT_REPORT",
        violations,
    )
    _require_step(
        workflow_text,
        "Generate Android engineering metrics",
        (
            "run: scripts/verify_android.sh metrics",
        ),
        "BLD009_METRICS_GENERATION",
        violations,
    )
    _require_step(
        workflow_text,
        "Upload Android engineering metrics",
        (
            f"uses: {UPLOAD_ARTIFACT}",
            "name: android-engineering-metrics",
            "android/build/reports/engineering-metrics/engineering-metrics.json",
            "if-no-files-found: error",
            "retention-days: 14",
        ),
        "BLD009_METRICS_ARTIFACT",
        violations,
    )
    _require_step(
        workflow_text,
        "Upload Android APKs",
        (
            f"uses: {UPLOAD_ARTIFACT}",
            "name: android-apks",
            "android/**/build/outputs/apk/**/*.apk",
            "if-no-files-found: error",
            "retention-days: 14",
        ),
        "BLD004_APK_ARTIFACT",
        violations,
    )
    _require_step(
        workflow_text,
        "Upload Android instrumentation reports",
        (
            "if: always()",
            f"uses: {UPLOAD_ARTIFACT}",
            "name: android-instrumentation-api-${{ matrix.api_level }}",
            "android/**/build/outputs/androidTest-results/**",
            "android/**/build/reports/androidTests/**",
            "if-no-files-found: warn",
            "retention-days: 14",
        ),
        "BLD004_INSTRUMENTATION_REPORT",
        violations,
    )

    script_contract = (
        'STAGE="${1:-all}"',
        "run_preflight()",
        "run_gradle()",
        "  preflight)",
        "  unit)",
        "run_gradle clean :architecture-gate:check testDebugUnitTest",
        "  lint)",
        "run_gradle lintRelease",
        "  assemble)",
        "run_gradle assembleDebug assembleRelease assembleDebugAndroidTest",
        "  metrics)",
        "run_metrics",
        "collect_engineering_metrics.py",
        "engineering-metrics/engineering-metrics.json",
        "  instrumentation)",
        "run_gradle connectedDebugAndroidTest",
    )
    if any(fragment not in script for fragment in script_contract):
        violations.append(Violation("BLD004_STAGE_SCRIPT", str(VERIFY_SCRIPT)))
    if (
        'python3 "$REPO_ROOT/scripts/verify_android_ci_reporting.py"' not in script
        or '--repo-root "$REPO_ROOT"' not in script
    ):
        violations.append(Violation("BLD004_LOCAL_GATE", str(VERIFY_SCRIPT)))

    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    violations = inspect_android_ci_reporting(args.repo_root)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}")
        return 1
    print("Android CI reporting passed: 5 stages, 5 artifact families")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
