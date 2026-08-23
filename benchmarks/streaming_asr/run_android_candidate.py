#!/usr/bin/env python3
"""Run the STR-004 exact-model benchmark on one explicitly selected Android device.

The runner installs the supplied debug APKs, stages only revision-pinned public artifacts,
runs a content-free instrumentation benchmark, and writes an atomic redacted JSON report.
It never records microphone audio, changes the default IME, or stores a transcript.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time


PACKAGE = "com.opentypeless.android"
TEST_PACKAGE = f"{PACKAGE}.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = (
    "com.opentypeless.android.offline."
    "StreamingCandidateBenchmarkInstrumentedTest#"
    "exactPinnedCandidateReportsFreshProcessAndWarmLatencyWithPeakPss"
)
MODEL_ID = "streaming-paraformer-bilingual-zh-en-int8-2023-08-14"
MODEL_REVISION = "8e40c43232a1c5c66c82111efc5820d3accca11b"
ACCURACY_REPORT_SHA256 = "1e3071c739fafb597ff63fc07da3153f61b4dbf28a8cbb3fd2a2ee533bf00ed6"
ZIPFORMER_REPORT_SHA256 = "4adc683ff31120322ae804d0a1be941bad31c68129bcad929f38614d89ef0f4e"


@dataclass(frozen=True)
class Artifact:
    name: str
    bytes: int
    sha256: str


MODEL_ARTIFACTS = (
    Artifact(
        "encoder.int8.onnx",
        165_462_184,
        "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a",
    ),
    Artifact(
        "decoder.int8.onnx",
        71_664_561,
        "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f",
    ),
    Artifact(
        "tokens.txt",
        75_756,
        "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
    ),
)
AUDIO_ARTIFACT = Artifact(
    "str004-official-0.wav",
    321_744,
    "7d93384ca14702cc584a7a33fe2fed92e89e708549161cb12ea38c916882103b",
)

STATUS_KEYS = {
    "str004_candidate_id",
    "str004_model_bytes",
    "str004_audio_sha256",
    "str004_audio_duration_ms",
    "str004_fresh_first_partial_ms",
    "str004_fresh_stop_to_final_ms",
    "str004_fresh_total_ms",
    "str004_fresh_peak_pss_kib",
    "str004_warm_first_partial_ms_p50",
    "str004_warm_first_partial_ms_p95",
    "str004_warm_stop_to_final_ms_p50",
    "str004_warm_stop_to_final_ms_p95",
    "str004_warm_total_ms_p50",
    "str004_warm_total_ms_p95",
    "str004_warm_peak_pss_kib_max",
    "str004_fresh_partial_count",
    "str004_warm_partial_count_min",
    "str004_final_code_points_min",
    "str004_contains_audio",
    "str004_contains_transcript",
}
STRING_STATUS_KEYS = {
    "str004_candidate_id",
    "str004_audio_sha256",
    "str004_contains_audio",
    "str004_contains_transcript",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require_file(path: Path, artifact: Artifact) -> None:
    if not path.is_file() or path.stat().st_size != artifact.bytes:
        raise ValueError(f"{artifact.name} size does not match the pinned artifact")
    if sha256(path) != artifact.sha256:
        raise ValueError(f"{artifact.name} hash does not match the pinned artifact")


def parse_instrumentation(output: str) -> dict[str, int | str]:
    if "INSTRUMENTATION_CODE: -1" not in output or "OK (1 test)" not in output:
        raise ValueError("STR-004 instrumentation did not complete successfully")
    values: dict[str, int | str] = {}
    pattern = re.compile(r"^INSTRUMENTATION_STATUS: (str004_[a-z0-9_]+)=(.*)$")
    for line in output.splitlines():
        match = pattern.match(line.strip())
        if not match:
            continue
        key, raw = match.groups()
        if key not in STATUS_KEYS or key in values:
            raise ValueError("STR-004 instrumentation returned an unexpected status field")
        if key in STRING_STATUS_KEYS:
            values[key] = raw
        else:
            if not re.fullmatch(r"0|[1-9][0-9]*", raw):
                raise ValueError("STR-004 instrumentation returned a malformed metric")
            values[key] = int(raw)
    missing = STATUS_KEYS - values.keys()
    if missing:
        raise ValueError("STR-004 instrumentation omitted required metrics")
    if values["str004_candidate_id"] != MODEL_ID:
        raise ValueError("STR-004 instrumentation measured a different candidate")
    if values["str004_audio_sha256"] != AUDIO_ARTIFACT.sha256:
        raise ValueError("STR-004 instrumentation measured a different audio artifact")
    if values["str004_contains_audio"] != "false" or values[
        "str004_contains_transcript"
    ] != "false":
        raise ValueError("STR-004 instrumentation attempted to export content")
    for key, value in values.items():
        if key in STRING_STATUS_KEYS:
            continue
        if not isinstance(value, int) or value < 0:
            raise ValueError("STR-004 instrumentation returned a negative metric")
    if int(values["str004_fresh_peak_pss_kib"]) == 0:
        raise ValueError("STR-004 instrumentation did not measure process memory")
    return values


class Adb:
    def __init__(self, executable: str, serial: str):
        if not serial or any(character.isspace() for character in serial):
            raise ValueError("An exact non-whitespace adb serial is required")
        self.executable = executable
        self.serial = serial

    def run(
        self,
        *arguments: str,
        timeout: int = 60,
        allow_failure: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [self.executable, "-s", self.serial, *arguments],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
        if result.returncode != 0 and not allow_failure:
            raise RuntimeError(
                f"adb operation failed ({result.returncode}): {result.stdout.strip()}"
            )
        return result

    def shell(
        self,
        *arguments: str,
        timeout: int = 60,
        allow_failure: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        return self.run(
            "shell", *arguments, timeout=timeout, allow_failure=allow_failure
        )


def _shell_command(*parts: str) -> str:
    return " ".join(shlex.quote(part) for part in parts)


def _run_as(
    adb: Adb,
    command: str,
    *,
    timeout: int = 60,
    allow_failure: bool = False,
) -> subprocess.CompletedProcess[str]:
    remote_command = " ".join(
        (
            "run-as",
            shlex.quote(PACKAGE),
            "sh",
            "-c",
            shlex.quote(command),
        )
    )
    return adb.shell(
        remote_command,
        timeout=timeout,
        allow_failure=allow_failure,
    )


def _device_hash(adb: Adb, relative_path: str) -> str | None:
    result = _run_as(
        adb,
        _shell_command("sha256sum", relative_path),
        allow_failure=True,
    )
    if result.returncode != 0:
        return None
    match = re.fullmatch(r"([0-9a-f]{64})\s+.*\n?", result.stdout)
    return match.group(1) if match else None


def _install(adb: Adb, apk: Path) -> None:
    result = adb.run("install", "--no-streaming", "-r", str(apk), timeout=180)
    if "Success" not in result.stdout:
        raise RuntimeError("Android did not confirm APK installation")


def stage_artifacts(adb: Adb, model_dir: Path, audio: Path) -> bool:
    for artifact in MODEL_ARTIFACTS:
        require_file(model_dir / artifact.name, artifact)
    require_file(audio, AUDIO_ARTIFACT)

    target = f"no_backup/offline_models/{MODEL_ID}"
    remote = f"/data/local/tmp/opentypeless-str004-{os.getpid()}"
    if adb.shell("test", "!", "-e", remote, allow_failure=True).returncode != 0:
        raise RuntimeError("Refusing to reuse an existing adb staging directory")
    adb.shell("mkdir", remote)
    try:
        for artifact in (*MODEL_ARTIFACTS, AUDIO_ARTIFACT):
            source = audio if artifact is AUDIO_ARTIFACT else model_dir / artifact.name
            remote_name = "official-0.wav" if artifact is AUDIO_ARTIFACT else artifact.name
            adb.run("push", str(source), f"{remote}/{remote_name}", timeout=300)

        existing = _run_as(
            adb,
            _shell_command("test", "-d", target),
            allow_failure=True,
        ).returncode == 0
        if existing:
            for artifact in MODEL_ARTIFACTS:
                if _device_hash(adb, f"{target}/{artifact.name}") != artifact.sha256:
                    raise RuntimeError("Existing private model does not match STR-004")
        else:
            staging = f"no_backup/offline_models/.str004-staging-{os.getpid()}"
            marker_text = (
                "opentypeless-streaming-model-v1\n"
                f"id={MODEL_ID}\n"
                f"revision={MODEL_REVISION}\n"
                f"encoder_sha256={MODEL_ARTIFACTS[0].sha256}\n"
                f"decoder_sha256={MODEL_ARTIFACTS[1].sha256}\n"
                f"tokens_sha256={MODEL_ARTIFACTS[2].sha256}\n"
            )
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as marker:
                marker.write(marker_text)
                marker_path = Path(marker.name)
            try:
                adb.run("push", str(marker_path), f"{remote}/installed-v1.txt")
            finally:
                marker_path.unlink(missing_ok=True)
            command = " && ".join(
                (
                    _shell_command("mkdir", "-p", "no_backup/offline_models"),
                    _shell_command("test", "!", "-e", staging),
                    _shell_command("mkdir", staging),
                    *(
                        _shell_command(
                            "cp",
                            f"{remote}/{artifact.name}",
                            f"{staging}/{artifact.name}",
                        )
                        for artifact in MODEL_ARTIFACTS
                    ),
                    _shell_command(
                        "cp", f"{remote}/installed-v1.txt", f"{staging}/installed-v1.txt"
                    ),
                    _shell_command("test", "!", "-e", target),
                    _shell_command("mv", staging, target),
                )
            )
            try:
                _run_as(adb, command, timeout=300)
            except Exception:
                _run_as(
                    adb,
                    _shell_command("rm", "-r", staging),
                    allow_failure=True,
                )
                raise
            for artifact in MODEL_ARTIFACTS:
                if _device_hash(adb, f"{target}/{artifact.name}") != artifact.sha256:
                    raise RuntimeError("Staged private model failed post-copy verification")

        benchmark_audio = f"{target}/{AUDIO_ARTIFACT.name}"
        existing_audio_hash = _device_hash(adb, benchmark_audio)
        if existing_audio_hash is not None and existing_audio_hash != AUDIO_ARTIFACT.sha256:
            raise RuntimeError("Existing benchmark WAV does not match STR-004")
        if existing_audio_hash is None:
            _run_as(
                adb,
                _shell_command(
                    "cp", f"{remote}/official-0.wav", benchmark_audio
                ),
            )
        if _device_hash(adb, benchmark_audio) != AUDIO_ARTIFACT.sha256:
            raise RuntimeError("Benchmark WAV failed post-copy verification")
        return existing
    finally:
        adb.shell("rm", "-r", remote, allow_failure=True)


def _battery(adb: Adb) -> dict[str, int | str]:
    values: dict[str, int | str] = {}
    for line in adb.shell("dumpsys", "battery").stdout.splitlines():
        if ":" not in line:
            continue
        key, value = (part.strip() for part in line.split(":", 1))
        if key not in {"level", "temperature", "status", "plugged"}:
            continue
        values[key] = int(value) if re.fullmatch(r"-?[0-9]+", value) else value
    return values


def _device(adb: Adb) -> dict[str, str]:
    properties = {
        "manufacturer": "ro.product.manufacturer",
        "model": "ro.product.model",
        "device": "ro.product.device",
        "release": "ro.build.version.release",
        "sdk": "ro.build.version.sdk",
        "abi": "ro.product.cpu.abi",
        "incremental": "ro.build.version.incremental",
    }
    return {
        name: adb.shell("getprop", key).stdout.strip()
        for name, key in properties.items()
    }


def _load_json(path: Path, expected_sha256: str) -> dict[str, object]:
    if sha256(path) != expected_sha256:
        raise ValueError(f"Pinned report changed: {path.name}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Pinned report is not an object: {path.name}")
    return value


def build_report(
    *,
    serial: str,
    device: dict[str, str],
    battery_before: dict[str, int | str],
    battery_after: dict[str, int | str],
    app_apk: Path,
    test_apk: Path,
    metrics: dict[str, int | str],
    accuracy: dict[str, object],
    zipformer: dict[str, object],
    model_was_preexisting: bool,
    head: str,
) -> dict[str, object]:
    accuracy_result = accuracy["result"]
    if not isinstance(accuracy_result, dict):
        raise ValueError("Pinned accuracy report has an invalid result")
    return {
        "schema": 1,
        "task_id": "STR-004",
        "collected_at_epoch_ms": int(time.time() * 1_000),
        "head": head,
        "serial_redacted": hashlib.sha256(serial.encode()).hexdigest()[:12],
        "device": device,
        "artifacts": {
            "app_debug": {
                "bytes": app_apk.stat().st_size,
                "sha256": sha256(app_apk),
            },
            "app_android_test": {
                "bytes": test_apk.stat().st_size,
                "sha256": sha256(test_apk),
            },
            "candidate": {
                "id": MODEL_ID,
                "revision": MODEL_REVISION,
                "model_bytes": sum(item.bytes for item in MODEL_ARTIFACTS),
                "files": {
                    item.name: {"bytes": item.bytes, "sha256": item.sha256}
                    for item in MODEL_ARTIFACTS
                },
                "was_preexisting": model_was_preexisting,
            },
            "public_device_wave": {
                "bytes": AUDIO_ARTIFACT.bytes,
                "sha256": AUDIO_ARTIFACT.sha256,
                "source": "revision-pinned upstream model test_wavs/0.wav",
            },
        },
        "accuracy_screening": {
            "platform": accuracy["runtime"]["platform"],
            "cases": accuracy["corpus"]["cases"],
            "mandarin_cer": accuracy_result["zh_cer"],
            "english_wer": accuracy_result["en_wer"],
            "mixed_mer": accuracy_result["mixed_mer"],
            "partial_coverage": accuracy_result["partial_coverage"],
            "first_partial_audio_seconds_p50": accuracy_result[
                "first_partial_audio_seconds_p50"
            ],
            "first_partial_audio_seconds_p95": accuracy_result[
                "first_partial_audio_seconds_p95"
            ],
            "processing_rtf_p50": accuracy_result["processing_rtf_p50"],
            "processing_rtf_p95": accuracy_result["processing_rtf_p95"],
            "earlier_text_revision_count": accuracy_result[
                "earlier_text_revision_count"
            ],
            "report_sha256": ACCURACY_REPORT_SHA256,
        },
        "rejected_baseline": {
            "candidate": zipformer["candidate"]["archive"],
            "decision": zipformer["decision"],
            "public_real_speech": zipformer["public_real_speech"],
            "report_sha256": ZIPFORMER_REPORT_SHA256,
        },
        "xiaomi_device_performance": metrics,
        "battery": {"before": battery_before, "after": battery_after},
        "decision": {
            "selected_for_str005": MODEL_ID,
            "role": "on-device replaceable first-pass streaming candidate",
            "authoritative_final": False,
            "supports_earlier_text_revision": False,
            "reasons": [
                "exact Android model has pinned public Mandarin/English/mixed accuracy evidence",
                "exact arm64 runtime produced live partials and finals with measured device PSS",
                "the prior bilingual Zipformer baseline was rejected as the bundled default",
            ],
            "limitations": [
                "English WER remains materially weaker than Mandarin CER",
                "the model produced no earlier-visible-text rewrite in the 200-case run",
                "device performance uses one public upstream WAV and is not a phone-microphone accuracy test",
                "battery delta from this short run is observational, not a release endurance result",
            ],
        },
        "privacy": {
            "contains_user_audio": False,
            "contains_transcript": False,
            "contains_adb_serial": False,
            "changes_default_ime": False,
            "records_microphone": False,
        },
    }


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
        temporary.unlink(missing_ok=True)


def main() -> int:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    app_apk = args.app_apk.resolve(strict=True)
    test_apk = args.test_apk.resolve(strict=True)
    model_dir = args.model_dir.resolve(strict=True)
    audio = args.audio.resolve(strict=True)
    adb = Adb(args.adb, args.serial)
    if adb.run("get-state").stdout.strip() != "device":
        raise RuntimeError("The explicitly selected Android device is not ready")

    _install(adb, app_apk)
    _install(adb, test_apk)
    model_was_preexisting = stage_artifacts(adb, model_dir, audio)
    battery_before = _battery(adb)
    adb.shell("am", "force-stop", PACKAGE)
    try:
        instrumentation = adb.shell(
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            TEST_CLASS,
            f"{TEST_PACKAGE}/{RUNNER}",
            timeout=180,
        ).stdout
        metrics = parse_instrumentation(instrumentation)
        battery_after = _battery(adb)
        device = _device(adb)
        accuracy = _load_json(
            repository
            / "benchmarks/offline_asr/reports/2026-08-12-streaming-paraformer-summary.json",
            ACCURACY_REPORT_SHA256,
        )
        zipformer = _load_json(
            repository / "benchmarks/offline_asr/reports/2026-08-09-zipformer-summary.json",
            ZIPFORMER_REPORT_SHA256,
        )
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repository,
            check=True,
            stdout=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        ).stdout.strip()
        report = build_report(
            serial=args.serial,
            device=device,
            battery_before=battery_before,
            battery_after=battery_after,
            app_apk=app_apk,
            test_apk=test_apk,
            metrics=metrics,
            accuracy=accuracy,
            zipformer=zipformer,
            model_was_preexisting=model_was_preexisting,
            head=head,
        )
        write_json_atomic(args.output, report)
    finally:
        adb.shell("am", "force-stop", PACKAGE, allow_failure=True)
    print(f"STR-004 benchmark PASS: {args.output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
