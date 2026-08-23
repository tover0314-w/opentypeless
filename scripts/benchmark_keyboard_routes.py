#!/usr/bin/env python3
"""Run the KSP-008 keyboard-route benchmark against one explicit Android device.

The script is deliberately read-only with respect to device configuration: it does
not install packages, change the default IME, disable keyguard, or alter display
timeouts. Candidate APKs must already be installed. The supplied APK paths are used
only to bind the report to exact bytes.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
from typing import Sequence


SCHEMA_VERSION = 1
METRIC_PREFIX = "INSTRUMENTATION_STATUS: ksp008_metric="
SUCCESS_MARKER = "INSTRUMENTATION_CODE: -1"
FAILURE_MARKERS = ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")


class BenchmarkError(RuntimeError):
    """A stable, plaintext-free benchmark failure."""


@dataclass(frozen=True)
class InstrumentationCase:
    component: str
    test_class: str
    expected_metrics: tuple[str, ...]


@dataclass(frozen=True)
class Route:
    route_id: str
    package_name: str
    launcher_component: str
    instrumentation: tuple[InstrumentationCase, ...]


ROUTES = (
    Route(
        route_id="A",
        package_name="dev.patrickgold.florisboard.debug",
        launcher_component=(
            "dev.patrickgold.florisboard.debug/"
            "dev.patrickgold.florisboard.SettingsLauncherAlias"
        ),
        instrumentation=(
            InstrumentationCase(
                component=(
                    "dev.patrickgold.florisboard.debug.test/"
                    "androidx.test.runner.AndroidJUnitRunner"
                ),
                test_class=(
                    "dev.patrickgold.florisboard.ime.opentypeless."
                    "Ksp008FlorisPerformanceInstrumentedTest"
                    "#qwertyTransactionLatencyAndPss"
                ),
                expected_metrics=("qwerty_transaction",),
            ),
            InstrumentationCase(
                component=(
                    "com.opentypeless.ksp004.test/"
                    "androidx.test.runner.AndroidJUnitRunner"
                ),
                test_class=(
                    "com.opentypeless.ksp004."
                    "Ksp008RimePerformanceInstrumentedTest"
                    "#candidateLatencyColdInitAndPss"
                ),
                expected_metrics=("rime_candidate_ni",),
            ),
        ),
    ),
    Route(
        route_id="B",
        package_name="org.fcitx.fcitx5.android.debug",
        launcher_component=(
            "org.fcitx.fcitx5.android.debug/"
            "org.fcitx.fcitx5.android.ui.main.MainActivity"
        ),
        instrumentation=(
            InstrumentationCase(
                component=(
                    "org.fcitx.fcitx5.android.debug.test/"
                    "androidx.test.runner.AndroidJUnitRunner"
                ),
                test_class=(
                    "org.fcitx.fcitx5.android.input.opentypeless."
                    "OpenTypelessFcitxAdapterInstrumentedTest"
                    "#ksp008QwertyTransactionLatencyAndPss"
                ),
                expected_metrics=("qwerty_transaction",),
            ),
            InstrumentationCase(
                component=(
                    "org.fcitx.fcitx5.android.debug.test/"
                    "androidx.test.runner.AndroidJUnitRunner"
                ),
                test_class=(
                    "org.fcitx.fcitx5.android.input.opentypeless."
                    "OpenTypelessFcitxAdapterInstrumentedTest"
                    "#ksp008ActualRimeCandidateLatencyColdInitAndPss"
                ),
                expected_metrics=("rime_candidate_ni",),
            ),
        ),
    ),
)


def percentile(values: Sequence[int], fraction: float) -> int:
    if not values:
        raise ValueError("percentile requires at least one value")
    if not 0.0 < fraction <= 1.0:
        raise ValueError("percentile fraction must be in (0, 1]")
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * fraction) - 1)
    return ordered[index]


def summarize(values: Sequence[int]) -> dict[str, object]:
    return {
        "samples": list(values),
        "min": min(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "max": max(values),
    }


def artifact_record(path: Path) -> dict[str, object]:
    if path.is_symlink():
        raise BenchmarkError("artifact must be a regular non-symlink file")
    resolved = path.resolve(strict=True)
    if not resolved.is_file():
        raise BenchmarkError("artifact must be a regular non-symlink file")
    digest = hashlib.sha256()
    size = 0
    with resolved.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            size += len(chunk)
            digest.update(chunk)
    return {"name": resolved.name, "bytes": size, "sha256": digest.hexdigest()}


def parse_instrumentation(output: str) -> list[dict[str, object]]:
    if SUCCESS_MARKER not in output or any(marker in output for marker in FAILURE_MARKERS):
        raise BenchmarkError("instrumentation did not finish successfully")
    metrics: list[dict[str, object]] = []
    for line in output.splitlines():
        if not line.startswith(METRIC_PREFIX):
            continue
        try:
            value = json.loads(line[len(METRIC_PREFIX) :])
        except json.JSONDecodeError as error:
            raise BenchmarkError("instrumentation emitted malformed metric JSON") from error
        if not isinstance(value, dict) or not isinstance(value.get("metric"), str):
            raise BenchmarkError("instrumentation metric has an invalid shape")
        metrics.append(value)
    if not metrics:
        raise BenchmarkError("instrumentation emitted no KSP-008 metrics")
    return metrics


def parse_activity_start(output: str) -> dict[str, object]:
    fields: dict[str, str] = {}
    for line in output.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        fields[key.strip()] = value.strip()
    if fields.get("Status") != "ok":
        raise BenchmarkError("activity cold launch did not report Status: ok")
    if fields.get("LaunchState") != "COLD":
        raise BenchmarkError("activity launch was not cold")
    try:
        total = int(fields["TotalTime"])
        wait = int(fields["WaitTime"])
    except (KeyError, ValueError) as error:
        raise BenchmarkError("activity launch timing was incomplete") from error
    if total < 0 or wait < total:
        raise BenchmarkError("activity launch timing was inconsistent")
    return {
        "launch_state": "COLD",
        "initial_display_total_ms": total,
        "command_wait_ms": wait,
    }


def parse_total_pss(output: str) -> int | None:
    match = re.search(r"(?m)^\s*TOTAL PSS:\s*([0-9,]+)", output)
    if match is None:
        return None
    return int(match.group(1).replace(",", ""))


class Adb:
    def __init__(self, executable: Path, serial: str) -> None:
        self._executable = str(executable)
        self._serial = serial

    def run(self, arguments: Sequence[str], timeout: int = 120) -> str:
        try:
            result = subprocess.run(
                [self._executable, "-s", self._serial, *arguments],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=timeout,
            )
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            raise BenchmarkError("adb command failed") from error
        return result.stdout

    def shell(self, *arguments: str, timeout: int = 120) -> str:
        return self.run(("shell", *arguments), timeout=timeout)

def _device_properties(adb: Adb) -> dict[str, object]:
    properties = {
        "manufacturer": "ro.product.manufacturer",
        "model": "ro.product.model",
        "device": "ro.product.device",
        "android_release": "ro.build.version.release",
        "api": "ro.build.version.sdk",
        "build_fingerprint": "ro.build.fingerprint",
        "security_patch": "ro.build.version.security_patch",
        "abi": "ro.product.cpu.abi",
    }
    values = {name: adb.shell("getprop", key).strip() for name, key in properties.items()}
    values["adb_target"] = "explicit_serial_redacted"
    values["default_ime"] = adb.shell("settings", "get", "secure", "default_input_method").strip()
    values["screen_off_timeout_ms"] = int(
        adb.shell("settings", "get", "system", "screen_off_timeout").strip()
    )
    values["stay_on_while_plugged_in"] = int(
        adb.shell("settings", "get", "global", "stay_on_while_plugged_in").strip()
    )
    return values


def _battery_snapshot(adb: Adb) -> dict[str, int | None]:
    output = adb.shell("dumpsys", "battery")
    values: dict[str, int | None] = {"level_percent": None, "temperature_tenths_c": None}
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("level:"):
            values["level_percent"] = int(stripped.split(":", 1)[1].strip())
        elif stripped.startswith("temperature:"):
            values["temperature_tenths_c"] = int(stripped.split(":", 1)[1].strip())
    return values


def _verify_installed(adb: Adb) -> None:
    packages = set(adb.shell("pm", "list", "packages").splitlines())
    instrumentations = adb.shell("pm", "list", "instrumentation")
    for route in ROUTES:
        if f"package:{route.package_name}" not in packages:
            raise BenchmarkError(f"route {route.route_id} package is not installed")
        for case in route.instrumentation:
            if f"instrumentation:{case.component}" not in instrumentations:
                raise BenchmarkError(f"route {route.route_id} instrumentation is not installed")


def _instrumentation_metrics(adb: Adb) -> dict[str, list[dict[str, object]]]:
    results: dict[str, list[dict[str, object]]] = {"A": [], "B": []}
    for case_index in range(max(len(route.instrumentation) for route in ROUTES)):
        for route in ROUTES:
            case = route.instrumentation[case_index]
            output = adb.shell(
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                case.test_class,
                case.component,
                timeout=180,
            )
            metrics = parse_instrumentation(output)
            names = tuple(str(metric["metric"]) for metric in metrics)
            if names != case.expected_metrics:
                raise BenchmarkError(f"route {route.route_id} emitted unexpected metrics")
            if any(metric.get("route") != route.route_id for metric in metrics):
                raise BenchmarkError("instrumentation route label did not match its package")
            for metric in metrics:
                enriched = dict(metric)
                enriched["benchmark_process_package"] = case.component.split("/", 1)[0]
                results[route.route_id].append(enriched)
    return results


def _cold_launch_metrics(adb: Adb, iterations: int) -> dict[str, dict[str, object]]:
    raw: dict[str, list[dict[str, object]]] = {"A": [], "B": []}
    route_by_id = {route.route_id: route for route in ROUTES}
    for index in range(iterations):
        order = ("A", "B") if index % 2 == 0 else ("B", "A")
        for route_id in order:
            route = route_by_id[route_id]
            adb.shell("am", "force-stop", route.package_name)
            output = adb.shell(
                "am", "start", "-W", "-S", "-n", route.launcher_component, timeout=60
            )
            raw[route_id].append(parse_activity_start(output))
            adb.shell("input", "keyevent", "KEYCODE_HOME")
    results: dict[str, dict[str, object]] = {}
    for route in ROUTES:
        values = raw[route.route_id]
        adb.shell("am", "force-stop", route.package_name)
        adb.shell("am", "start", "-W", "-S", "-n", route.launcher_component, timeout=60)
        pss = parse_total_pss(adb.shell("dumpsys", "meminfo", route.package_name))
        results[route.route_id] = {
            "iterations": iterations,
            "activity_initial_display_ms": summarize(
                [int(value["initial_display_total_ms"]) for value in values]
            ),
            "cold_command_wait_ms": summarize([int(value["command_wait_ms"]) for value in values]),
            "post_launch_total_pss_kb": pss,
        }
        adb.shell("input", "keyevent", "KEYCODE_HOME")
    return results


def benchmark(
    adb: Adb,
    artifacts: dict[str, Sequence[Path]],
    startup_iterations: int,
) -> dict[str, object]:
    if startup_iterations < 3 or startup_iterations > 50:
        raise BenchmarkError("startup iterations must be between 3 and 50")
    if set(artifacts) != {"A", "B"} or any(len(paths) != 2 for paths in artifacts.values()):
        raise BenchmarkError("each route requires exactly two distribution APKs")
    if adb.run(("get-state",)).strip() != "device":
        raise BenchmarkError("requested adb serial is not online")
    _verify_installed(adb)
    adb.shell("input", "keyevent", "KEYCODE_WAKEUP")
    before = _battery_snapshot(adb)
    instrumentation = _instrumentation_metrics(adb)
    startup = _cold_launch_metrics(adb, startup_iterations)
    after = _battery_snapshot(adb)
    return {
        "schema_version": SCHEMA_VERSION,
        "task": "KSP-008",
        "measurement_semantics": {
            "activity_initial_display_ms": (
                "ActivityManager am start -W TotalTime: cold process start through initial display"
            ),
            "cold_command_wait_ms": "ActivityManager am start -W WaitTime",
            "instrumentation_pss_kb": "process PSS sampled inside each isolated benchmark process",
            "post_launch_total_pss_kb": "dumpsys meminfo TOTAL PSS after launcher activity start",
            "rime_cold_init_us": (
                "first engine initialization in a new instrumentation process using existing app data; "
                "the script does not clear candidate storage"
            ),
        },
        "comparison_limits": {
            "route_a_rime": (
                "isolated KSP-004 adapter with a synthetic two-entry schema; it measures adapter/JNI "
                "plumbing, not full-dictionary language complexity"
            ),
            "route_a_memory_and_apk": (
                "Floris shell and KSP-004 adapter are separate spike packages, so totals are a "
                "distribution proxy rather than an integrated-process measurement"
            ),
            "route_b_rime": "official fcitx5-android Rime plugin with its actual test schema/data",
            "decision": "KSP-008 records performance only and does not choose a keyboard base",
        },
        "device": _device_properties(adb),
        "battery_before": before,
        "battery_after": after,
        "artifacts": {
            route_id: {
                "distribution_proxy_total_bytes": sum(path.stat().st_size for path in paths),
                "apks": [artifact_record(path) for path in paths],
            }
            for route_id, paths in artifacts.items()
        },
        "instrumentation": instrumentation,
        "cold_start": startup,
    }


def _default_adb() -> Path:
    return Path.home() / "Library/Android/sdk/platform-tools/adb"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run the same KSP-008 performance script for keyboard routes A and B."
    )
    parser.add_argument("--adb", type=Path, default=_default_adb())
    parser.add_argument("--serial", required=True)
    parser.add_argument("--route-a-apk", action="append", type=Path, required=True)
    parser.add_argument("--route-b-apk", action="append", type=Path, required=True)
    parser.add_argument("--startup-iterations", type=int, default=10)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = benchmark(
            Adb(args.adb, args.serial),
            {"A": args.route_a_apk, "B": args.route_b_apk},
            args.startup_iterations,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except (BenchmarkError, OSError, ValueError) as error:
        print(f"KSP-008 benchmark failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
