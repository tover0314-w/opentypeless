#!/usr/bin/env python3
"""Fail closed when the production editor-writer gates are disconnected from CI."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


@dataclass(frozen=True, order=True)
class WiringViolation:
    relative_path: str
    rule: str
    detail: str

    def __str__(self) -> str:
        return f"{self.relative_path}: {self.rule}: {self.detail}"


def _require(
    pattern: str,
    text: str,
    relative_path: str,
    rule: str,
    detail: str,
    violations: list[WiringViolation],
) -> None:
    if not re.search(pattern, text, re.MULTILINE | re.DOTALL):
        violations.append(WiringViolation(relative_path, rule, detail))


def _read(
    repo_root: Path,
    relative_path: str,
    violations: list[WiringViolation],
) -> str:
    path = repo_root / relative_path
    if not path.is_file():
        violations.append(
            WiringViolation(relative_path, "CI_EDITOR_WRITE_GATE_FILE", "required file is missing")
        )
        return ""
    return path.read_text(encoding="utf-8")


def _workflow_job(workflow: str, job_name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(job_name)}:\s*\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*(?:#.*)?$|\Z)",
        workflow,
    )
    return match.group("body") if match else ""


def inspect_ci_wiring(repo_root: Path) -> tuple[WiringViolation, ...]:
    root = repo_root.resolve()
    violations: list[WiringViolation] = []

    workflow_path = ".github/workflows/ci.yml"
    verify_path = "scripts/verify_android.sh"
    settings_path = "android/settings.gradle.kts"
    gate_build_path = "android/architecture-gate/build.gradle.kts"
    app_build_path = "android/app/build.gradle.kts"

    workflow = _read(root, workflow_path, violations)
    verify = _read(root, verify_path, violations)
    settings = _read(root, settings_path, violations)
    gate_build = _read(root, gate_build_path, violations)
    app_build = _read(root, app_build_path, violations)

    android_job = _workflow_job(workflow, "check-android")
    if not android_job:
        violations.append(
            WiringViolation(
                workflow_path,
                "CI_ANDROID_JOB",
                "check-android must remain a required fail-closed job",
            )
        )
    else:
        _require(
            r"(?m)^\s*- name: Android preflight and static policy checks\s*\n\s*"
            r"run:\s*scripts/verify_android\.sh preflight\s*$",
            android_job,
            workflow_path,
            "CI_EDITOR_WRITE_GATE_STEP",
            "check-android preflight must verify its editor-writer gate wiring",
            violations,
        )
        _require(
            r"(?m)^\s*- name: Android unit and architecture tests\s*\n\s*"
            r"run:\s*scripts/verify_android\.sh unit\s*$",
            android_job,
            workflow_path,
            "CI_ANDROID_VERIFY_STEP",
            "check-android must run the strict compiled architecture verifier",
            violations,
        )
        if re.search(r"(?m)^\s*continue-on-error:\s*true\s*$", android_job):
            violations.append(
                WiringViolation(
                    workflow_path,
                    "CI_ANDROID_CONTINUE_ON_ERROR",
                    "editor-writer verification may not be advisory",
                )
            )

    _require(
        r"(?m)^set -euo pipefail\s*$",
        verify,
        verify_path,
        "VERIFY_SCRIPT_FAIL_FAST",
        "the Android verifier must stop on every gate failure",
        violations,
    )
    _require(
        r"python3\s+\"\$ANDROID_DIR/architecture-tests/editor_write_ci_gate\.py\"\s*"
        r"(?:\\\s*\n\s*)?--repo-root\s+\"\$REPO_ROOT\"",
        verify,
        verify_path,
        "VERIFY_SCRIPT_SELF_CHECK",
        "local/full verification must check the same CI wiring",
        violations,
    )
    _require(
        r"python3 -m unittest discover -s \"\$ANDROID_DIR/architecture-tests\" "
        r"-p 'test_\*\.py' -v",
        verify,
        verify_path,
        "VERIFY_SCRIPT_SOURCE_TESTS",
        "all source architecture fault-injection tests must run",
        violations,
    )
    _require(
        r"python3\s+\"\$ANDROID_DIR/architecture-tests/architecture_contracts\.py\"\s*\\?\s*\n?\s*"
        r"--android-root\s+\"\$ANDROID_DIR\"",
        verify,
        verify_path,
        "VERIFY_SCRIPT_SOURCE_SCAN",
        "the production source tree must be scanned",
        violations,
    )
    _require(
        r"--dependency-verification=strict",
        verify,
        verify_path,
        "VERIFY_SCRIPT_STRICT_DEPENDENCIES",
        "compiled gates must retain strict dependency verification",
        violations,
    )
    if re.search(r"--dependency-verification=(?:lenient|off)", verify):
        violations.append(
            WiringViolation(
                verify_path,
                "VERIFY_SCRIPT_DEPENDENCY_BYPASS",
                "dependency verification may not be weakened",
            )
        )
    _require(
        r":architecture-gate:check(?:\s|\\)",
        verify,
        verify_path,
        "VERIFY_SCRIPT_COMPILED_GATE",
        "compiled writer tests and Debug/Release inspection must run",
        violations,
    )

    _require(
        r"include\(\s*\":architecture-gate\"\s*\)",
        settings,
        settings_path,
        "GRADLE_ARCHITECTURE_MODULE",
        "the compiled gate module must remain included",
        violations,
    )
    _require(
        r"val\s+compiledArchitectureVariants\s*=\s*listOf\(\s*\"debug\"\s*,\s*\"release\"\s*\)",
        gate_build,
        gate_build_path,
        "GRADLE_COMPILED_VARIANTS",
        "both production variants must be inspected",
        violations,
    )
    _require(
        r"tasks\.named\(\s*\"check\"\s*\)\s*\{\s*"
        r"dependsOn\(\s*tasks\.named\(\s*\"test\"\s*\)\s*,\s*"
        r"verifyCompiledArchitecture\s*\)\s*\}",
        gate_build,
        gate_build_path,
        "GRADLE_COMPILED_CHECK",
        "Gradle check must run fault-injection tests and compiled inspection",
        violations,
    )
    _require(
        r"if\s*\(\s*variant\.name\s*!in\s*setOf\(\s*\"debug\"\s*,\s*\"release\"\s*\)\s*\)",
        app_build,
        app_build_path,
        "GRADLE_COMPILED_EXPORT",
        "the app must export both production variants to the compiled gate",
        violations,
    )

    return tuple(sorted(set(violations)))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (default: two parents above this file)",
    )
    args = parser.parse_args(argv)
    violations = inspect_ci_wiring(args.repo_root)
    if violations:
        print("Editor-writer CI wiring violations:", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print("Editor-writer CI wiring passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
