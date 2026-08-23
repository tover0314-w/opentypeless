#!/usr/bin/env python3
"""Collect a redacted Android startup/resource baseline before the spoken acceptance run."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import statistics
import subprocess
import tempfile
import time
from pathlib import Path


PACKAGE = "com.opentypeless.android"
ACTIVITY = f"{PACKAGE}/.MainActivity"
PROPERTY_KEYS = {
    "manufacturer": "ro.product.manufacturer",
    "model": "ro.product.model",
    "device": "ro.product.device",
    "sdk": "ro.build.version.sdk",
    "release": "ro.build.version.release",
    "abi": "ro.product.cpu.abi",
    "fingerprint": "ro.build.fingerprint",
}


def parse_start(output: str) -> dict[str, int | str]:
    values: dict[str, int | str] = {}
    for line in output.splitlines():
        if ":" not in line:
            continue
        key, value = (part.strip() for part in line.split(":", 1))
        if key in {"ThisTime", "TotalTime", "WaitTime"}:
            values[key] = int(value)
        elif key in {"Status", "LaunchState", "Activity"}:
            values[key] = value
    if values.get("Status") != "ok" or "TotalTime" not in values:
        raise ValueError("Android did not report a successful measured launch")
    return values


def parse_meminfo(output: str) -> dict[str, int]:
    match = re.search(
        r"TOTAL PSS:\s*([0-9,]+)\s+TOTAL RSS:\s*([0-9,]+)\s+TOTAL SWAP PSS:\s*([0-9,]+)",
        output,
    )
    if not match:
        raise ValueError("Unable to find the App Summary totals in dumpsys meminfo")
    return {
        "pss_kib": int(match.group(1).replace(",", "")),
        "rss_kib": int(match.group(2).replace(",", "")),
        "swap_pss_kib": int(match.group(3).replace(",", "")),
    }


def parse_package(output: str) -> dict[str, str | int]:
    version_name = re.search(r"\bversionName=([^\s]+)", output)
    version_code = re.search(r"\bversionCode=(\d+)", output)
    if not version_name or not version_code:
        raise ValueError("OpenTypeless is not installed or has no readable version metadata")
    return {
        "version_name": version_name.group(1),
        "version_code": int(version_code.group(1)),
    }


def percentile_nearest_rank(values: list[int], percentile: float) -> int:
    if not values:
        raise ValueError("At least one sample is required")
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile * len(ordered)))
    return ordered[rank - 1]


class Adb:
    def __init__(self, executable: str, serial: str):
        self.executable = executable
        self.serial = serial

    def run(
        self, *arguments: str, timeout: int = 30, allow_failure: bool = False
    ) -> str:
        command = [self.executable, "-s", self.serial, *arguments]
        result = subprocess.run(
            command,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        if result.returncode != 0 and not allow_failure:
            raise RuntimeError(f"adb command failed ({result.returncode}): {result.stdout.strip()}")
        return result.stdout

    def shell(
        self, *arguments: str, timeout: int = 30, allow_failure: bool = False
    ) -> str:
        return self.run(
            "shell", *arguments, timeout=timeout, allow_failure=allow_failure
        )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def battery_snapshot(adb: Adb) -> dict[str, int | str]:
    values: dict[str, int | str] = {}
    for line in adb.shell("dumpsys", "battery").splitlines():
        if ":" not in line:
            continue
        key, value = (part.strip() for part in line.split(":", 1))
        if key in {"level", "temperature", "status", "plugged"}:
            try:
                values[key] = int(value)
            except ValueError:
                values[key] = value
    return values


def collect(args: argparse.Namespace) -> dict[str, object]:
    adb = Adb(args.adb, args.serial)
    state = adb.run("get-state").strip()
    if state != "device":
        raise RuntimeError(f"Device {args.serial} is not ready (state={state!r})")

    device = {
        name: adb.shell("getprop", key).strip()
        for name, key in PROPERTY_KEYS.items()
    }
    package = parse_package(adb.shell("dumpsys", "package", PACKAGE, timeout=45))
    apk = None
    if args.apk:
        apk_path = Path(args.apk).resolve(strict=True)
        apk = {
            "path_basename": apk_path.name,
            "bytes": apk_path.stat().st_size,
            "sha256": sha256(apk_path),
        }

    launches: list[dict[str, int | str]] = []
    for _ in range(args.runs):
        adb.shell("am", "force-stop", PACKAGE)
        launches.append(parse_start(adb.shell(
            "am", "start", "-W", "-S", "-n", ACTIVITY, timeout=60
        )))
    # Let the first frame settle before asking Android for a steady foreground-process sample.
    time.sleep(1.0)
    memory = parse_meminfo(adb.shell("dumpsys", "meminfo", PACKAGE, timeout=45))
    total_times = [int(run["TotalTime"]) for run in launches]

    local_pid = adb.shell(
        "pidof", f"{PACKAGE}:local_asr", allow_failure=True
    ).strip()
    result: dict[str, object] = {
        "schema": 1,
        "collected_at_epoch_ms": int(time.time() * 1000),
        "serial_redacted": hashlib.sha256(args.serial.encode("utf-8")).hexdigest()[:12],
        "device": device,
        "package": package,
        "apk": apk,
        "startup": {
            "kind": "force_stop_cold_activity_launch",
            "samples": launches,
            "total_time_ms_p50": int(statistics.median(total_times)),
            "total_time_ms_p95": percentile_nearest_rank(total_times, 0.95),
        },
        "foreground_process_memory": memory,
        "local_asr_process_active": bool(local_pid),
        "battery": battery_snapshot(adb),
        "privacy": {
            "contains_audio": False,
            "contains_transcript": False,
            "contains_api_key": False,
            "contains_editor_context": False,
        },
    }
    return result


def write_json_atomic(path: Path, payload: dict[str, object]) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(payload, output, ensure_ascii=False, indent=2, sort_keys=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True, help="Exact adb serial; never auto-select a phone")
    parser.add_argument("--adb", default="adb", help="Path to adb")
    parser.add_argument("--apk", help="Optional APK to hash; this script never installs it")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.runs < 3 or args.runs > 20:
        parser.error("--runs must be between 3 and 20")
    payload = collect(args)
    write_json_atomic(Path(args.output), payload)
    print(
        f"wrote {args.output}: startup p50={payload['startup']['total_time_ms_p50']} ms, "
        f"p95={payload['startup']['total_time_ms_p95']} ms, "
        f"PSS={payload['foreground_process_memory']['pss_kib']} KiB"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
