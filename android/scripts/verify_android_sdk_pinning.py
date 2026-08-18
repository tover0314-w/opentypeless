#!/usr/bin/env python3
"""Fail closed when CI depends on mutable runner-installed Android SDK packages."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


WORKFLOW = Path(".github/workflows/ci.yml")
VERIFY_SCRIPT = Path("scripts/verify_android.sh")
APP_GRADLE = Path("android/app/build.gradle.kts")
HOST_GRADLE = Path("android/test-host/build.gradle.kts")

COMPILE_SDK = "35"
BUILD_TOOLS = "35.0.0"
EMULATOR_TARGET = "google_apis"
EMULATOR_ARCH = "x86_64"
EMULATOR_APIS = (26, 33, 35, 36)


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _read(root: Path, relative: Path, violations: list[Violation]) -> str:
    path = root / relative
    if not path.is_file() or path.is_symlink():
        violations.append(Violation("BLD002_REGULAR_FILE", str(relative)))
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError:
        violations.append(Violation("BLD002_UTF8", str(relative)))
        return ""


def _job(workflow: str, name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(name)}:\s*\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*\n|\Z)",
        workflow,
    )
    return "" if match is None else match.group("body")


def _require_tokens(
    text: str, tokens: tuple[str, ...], rule: str, violations: list[Violation]
) -> None:
    missing = [token for token in tokens if token not in text]
    if missing:
        violations.append(Violation(rule, "missing " + ", ".join(missing)))


def inspect_sdk_pinning(repo_root: Path) -> tuple[Violation, ...]:
    root = repo_root.resolve()
    violations: list[Violation] = []
    workflow = _read(root, WORKFLOW, violations)
    verify_script = _read(root, VERIFY_SCRIPT, violations)
    app_gradle = _read(root, APP_GRADLE, violations)
    host_gradle = _read(root, HOST_GRADLE, violations)

    _require_tokens(
        workflow,
        (
            f"ANDROID_COMPILE_SDK: '{COMPILE_SDK}'",
            f"ANDROID_BUILD_TOOLS: '{BUILD_TOOLS}'",
            f"ANDROID_EMULATOR_TARGET: {EMULATOR_TARGET}",
            f"ANDROID_EMULATOR_ARCH: {EMULATOR_ARCH}",
        ),
        "BLD002_WORKFLOW_CONSTANTS",
        violations,
    )

    android_job = _job(workflow, "check-android")
    emulator_job = _job(workflow, "test-android-emulator")
    if not android_job:
        violations.append(Violation("BLD002_ANDROID_JOB", "missing check-android"))
    if not emulator_job:
        violations.append(Violation("BLD002_EMULATOR_JOB", "missing test-android-emulator"))

    shared_packages = (
        '"platform-tools"',
        '"platforms;android-${ANDROID_COMPILE_SDK}"',
        '"build-tools;${ANDROID_BUILD_TOOLS}"',
        'test -d "${ANDROID_SDK_ROOT}/platforms/android-${ANDROID_COMPILE_SDK}"',
        'test -d "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS}"',
    )
    _require_tokens(
        android_job,
        ("Install pinned Android SDK packages", "sdkmanager --install", *shared_packages),
        "BLD002_ANDROID_PACKAGES",
        violations,
    )
    _require_tokens(
        emulator_job,
        (
            "Install pinned Android SDK and emulator image",
            "sdkmanager --install",
            *shared_packages,
            '"emulator"',
            'emulator_image="system-images;android-${{ matrix.api_level }};'
            '${ANDROID_EMULATOR_TARGET};${ANDROID_EMULATOR_ARCH}"',
            'sdkmanager --list_installed | grep -F "${emulator_image}"',
            "api_level: [26, 33, 35, 36]",
            "api-level: ${{ matrix.api_level }}",
            "target: ${{ env.ANDROID_EMULATOR_TARGET }}",
            "arch: ${{ env.ANDROID_EMULATOR_ARCH }}",
        ),
        "BLD002_EMULATOR_IMAGE",
        violations,
    )

    if "continue-on-error: true" in android_job or "continue-on-error: true" in emulator_job:
        violations.append(Violation("BLD002_FAIL_CLOSED", "Android SDK job is advisory"))
    if re.search(r"sdkmanager\s+--update|build-tools;latest", workflow):
        violations.append(Violation("BLD002_MUTABLE_SDK", "mutable SDK package selection"))

    for relative, gradle in ((APP_GRADLE, app_gradle), (HOST_GRADLE, host_gradle)):
        if not re.search(rf"(?m)^\s*compileSdk\s*=\s*{COMPILE_SDK}\s*$", gradle):
            violations.append(Violation("BLD002_COMPILE_SDK", str(relative)))
        if not re.search(rf"(?m)^\s*targetSdk\s*=\s*{COMPILE_SDK}\s*$", gradle):
            violations.append(Violation("BLD002_TARGET_SDK", str(relative)))

    _require_tokens(
        verify_script,
        (
            'python3 "$ANDROID_DIR/scripts/verify_android_sdk_pinning.py"',
            '--repo-root "$REPO_ROOT"',
        ),
        "BLD002_LOCAL_GATE",
        violations,
    )
    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[2]
    )
    args = parser.parse_args()
    violations = inspect_sdk_pinning(args.repo_root)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}", file=sys.stderr)
        return 1
    print(
        "Android SDK pinning passed: "
        f"platform {COMPILE_SDK}, build-tools {BUILD_TOOLS}, "
        f"{EMULATOR_TARGET}/{EMULATOR_ARCH} APIs {EMULATOR_APIS}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
