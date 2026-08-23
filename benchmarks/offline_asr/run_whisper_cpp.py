#!/usr/bin/env python3
"""Benchmark a pinned whisper.cpp server on the shared offline-ASR corpus.

The runner starts the supplied binary on an ephemeral loopback port, sends one
WAV at a time, and applies the exact metric and personalization rules used by
the sherpa-onnx candidate runner. Model weights and the whisper.cpp runtime
remain external benchmark inputs; neither is added to the application.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

if __package__:
    from .asr_metrics import (
        apply_alias_corrections,
        contains_entity,
        count_entity_occurrences,
        error_details,
        normalize_characters,
    )
    from .run_sherpa_offline import global_personalization
    from .run_sherpa_zipformer import (
        count_expected_entity_hits,
        load_manifest,
        read_wave,
        sha256_file,
        summarize,
    )
else:
    from asr_metrics import (
        apply_alias_corrections,
        contains_entity,
        count_entity_occurrences,
        error_details,
        normalize_characters,
    )
    from run_sherpa_offline import global_personalization
    from run_sherpa_zipformer import (
        count_expected_entity_hits,
        load_manifest,
        read_wave,
        sha256_file,
        summarize,
    )


COMMIT_RE = re.compile(r"[0-9a-f]{40}")
MAX_RESPONSE_BYTES = 1_000_000


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Benchmark a pinned local whisper.cpp server"
    )
    parser.add_argument("--server-binary", type=Path, required=True)
    parser.add_argument("--runtime-commit", required=True)
    parser.add_argument("--model-file", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--num-threads", type=int, default=4)
    parser.add_argument("--request-timeout-seconds", type=float, default=120.0)
    parser.add_argument("--limit", type=int)
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    for path in (args.server_binary, args.model_file, args.manifest):
        if not path.is_file():
            raise FileNotFoundError(path)
    if not COMMIT_RE.fullmatch(args.runtime_commit):
        raise ValueError("runtime commit must be a lowercase 40-character SHA-1")
    if not 1 <= args.num_threads <= 16:
        raise ValueError("num threads must be between 1 and 16")
    if not 1 <= args.request_timeout_seconds <= 600:
        raise ValueError("request timeout must be between 1 and 600 seconds")
    if args.limit is not None and args.limit <= 0:
        raise ValueError("limit must be positive")


def reserve_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def server_command(args: argparse.Namespace, port: int, public_dir: Path) -> list[str]:
    return [
        str(args.server_binary.resolve()),
        "--model",
        str(args.model_file.resolve()),
        "--language",
        "auto",
        "--threads",
        str(args.num_threads),
        "--processors",
        "1",
        "--best-of",
        "5",
        "--beam-size",
        "5",
        "--no-gpu",
        "--no-timestamps",
        "--host",
        "127.0.0.1",
        "--port",
        str(port),
        "--public",
        str(public_dir.resolve()),
    ]


def wait_until_ready(process: subprocess.Popen[str], port: int, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        return_code = process.poll()
        if return_code is not None:
            raise RuntimeError(f"whisper.cpp server exited during startup: {return_code}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError:
            time.sleep(0.05)
    raise TimeoutError("whisper.cpp server did not bind its loopback port")


def multipart_body(audio_path: Path) -> tuple[bytes, str]:
    boundary = f"opentypeless-{uuid.uuid4().hex}"
    chunks: list[bytes] = []

    def field(name: str, value: str) -> None:
        chunks.extend(
            [
                f"--{boundary}\r\n".encode("ascii"),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(
                    "ascii"
                ),
                value.encode("utf-8"),
                b"\r\n",
            ]
        )

    field("temperature", "0.0")
    field("temperature_inc", "0.2")
    field("response_format", "json")
    chunks.extend(
        [
            f"--{boundary}\r\n".encode("ascii"),
            (
                'Content-Disposition: form-data; name="file"; '
                f'filename="{audio_path.name}"\r\n'
            ).encode("utf-8"),
            b"Content-Type: audio/wav\r\n\r\n",
            audio_path.read_bytes(),
            b"\r\n",
            f"--{boundary}--\r\n".encode("ascii"),
        ]
    )
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def parse_response(payload: bytes) -> str:
    if len(payload) > MAX_RESPONSE_BYTES:
        raise ValueError("whisper.cpp response exceeds the 1 MB benchmark limit")
    try:
        parsed = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("whisper.cpp returned invalid UTF-8 JSON") from error
    text = parsed.get("text") if isinstance(parsed, dict) else None
    if not isinstance(text, str):
        raise ValueError("whisper.cpp JSON response has no string text field")
    return text.strip()


def transcribe(port: int, audio_path: Path, timeout: float) -> tuple[str, float]:
    body, content_type = multipart_body(audio_path)
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/inference",
        data=body,
        headers={"Content-Type": content_type, "Accept": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.URLError as error:
        raise RuntimeError(f"whisper.cpp request failed for {audio_path.name}") from error
    return parse_response(payload), time.perf_counter() - started


def evaluate_record(
    record: dict,
    hypothesis: str,
    processing_seconds: float,
    global_entity_forms: list[str],
    global_corrections: list[tuple[str, str]],
) -> dict:
    sample_rate, samples = read_wave(record["audio_path"])
    duration = len(samples) / sample_rate
    expected_entities = record["hotwords"]
    expected_bias_phrases = record.get("bias_phrases", expected_entities)
    corrected_hypothesis = apply_alias_corrections(
        hypothesis,
        [canonical for canonical, _ in global_corrections],
        [alias for _, alias in global_corrections],
    )
    expected_forms = set(expected_entities) | set(expected_bias_phrases)
    expected_form_keys = {normalize_characters(form) for form in expected_forms}
    false_hotwords = [
        hotword
        for hotword in global_entity_forms
        if normalize_characters(hotword) not in expected_form_keys
        and not contains_entity(record["reference"], hotword)
        for _ in range(count_entity_occurrences(hypothesis, hotword))
    ]
    false_corrections = [
        canonical
        for canonical, alias in global_corrections
        if canonical not in expected_forms
        and not contains_entity(record["reference"], canonical)
        and not contains_entity(record["reference"], alias)
        and apply_alias_corrections(hypothesis, [canonical], [alias]) != hypothesis
        for _ in range(count_entity_occurrences(hypothesis, alias))
    ]
    raw_error = error_details(record["reference"], hypothesis, record["language"])
    corrected_error = error_details(
        record["reference"], corrected_hypothesis, record["language"]
    )
    return {
        "mode": "offline_beam",
        "id": record["id"],
        "language": record["language"],
        "category": record["category"],
        "voice": record["voice"],
        "condition": record["condition"],
        "audio": str(record["audio_path"]),
        "reference": record["reference"],
        "hypothesis": hypothesis,
        "metric": raw_error["metric"],
        "edit_distance": raw_error["edits"],
        "reference_units": raw_error["reference_units"],
        "hypothesis_units": raw_error["hypothesis_units"],
        "error_rate": raw_error["error_rate"],
        "corrected_hypothesis": corrected_hypothesis,
        "corrected_edit_distance": corrected_error["edits"],
        "corrected_hypothesis_units": corrected_error["hypothesis_units"],
        "corrected_error_rate": corrected_error["error_rate"],
        "duration_seconds": duration,
        "streaming_processing_seconds": processing_seconds,
        "streaming_processing_rtf": processing_seconds / duration if duration else None,
        "first_partial_audio_seconds": None,
        "partial_result_observed": False,
        "audio_sha256": record["audio_sha256"],
        "audio_bytes": record["audio_bytes"],
        "expected_entities": expected_entities,
        "expected_bias_phrases": expected_bias_phrases,
        "recognized_entity_hits": count_expected_entity_hits(
            hypothesis, expected_entities, expected_bias_phrases
        ),
        "canonical_entity_hits": count_expected_entity_hits(
            corrected_hypothesis, expected_entities, expected_entities
        ),
        "entity_total": len(expected_entities),
        "false_hotwords": false_hotwords,
        "false_corrections": false_corrections,
    }


def render_markdown(metadata: dict, summary: dict, records: list[dict]) -> str:
    mode = summary["offline_beam"]
    language = mode["language_error_rate"]

    def percent(value: float | None) -> str:
        return "n/a" if value is None else f"{value:.1%}"

    lines = [
        "# whisper.cpp offline ASR candidate result",
        "",
        f"- Runtime commit: `{metadata['runtime_commit']}`",
        f"- Model: `{metadata['model_file']['sha256']}` ({metadata['model_file']['bytes']} bytes)",
        f"- Audio cases: {metadata['audio_cases']}",
        f"- Manifest SHA-256: `{metadata['manifest_sha256']}`",
        f"- Audio-set SHA-256: `{metadata['audio_set_sha256']}`",
        "- Decode: CPU-only, auto language, beam size 5, best-of 5",
        "",
        "| zh CER | en WER | mixed MER | Entity recall | Corrected entity recall | Processing RTF p50 / p95 |",
        "| ---: | ---: | ---: | ---: | ---: | ---: |",
        f"| {percent(language.get('zh'))} | {percent(language.get('en'))} | {percent(language.get('mixed'))} | "
        f"{mode['recognized_entity_hits']}/{mode['entity_total']} ({mode['recognized_entity_recall']:.1%}) | "
        f"{mode['canonical_entity_hits']}/{mode['entity_total']} ({mode['canonical_entity_recall']:.1%}) | "
        f"{mode['streaming_processing_rtf_p50']:.3f} / {mode['streaming_processing_rtf_p95']:.3f} |",
        "",
        "## Worst cases",
        "",
        "| ID | Language | Error | Reference | Hypothesis |",
        "| --- | --- | ---: | --- | --- |",
    ]
    for record in sorted(records, key=lambda item: item["error_rate"], reverse=True)[:20]:
        reference = record["reference"].replace("|", "\\|")
        hypothesis = record["hypothesis"].replace("|", "\\|")
        lines.append(
            f"| {record['id']} | {record['language']} | {record['error_rate']:.3f} | "
            f"{reference} | {hypothesis} |"
        )
    lines.append("")
    return "\n".join(lines)


def stop_server(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def main() -> None:
    args = parse_args()
    validate_args(args)
    records = load_manifest(args.manifest, args.limit)
    entity_forms, global_corrections = global_personalization(records)
    runner_path = Path(__file__).resolve()
    metrics_path = runner_path.with_name("asr_metrics.py")
    port = reserve_loopback_port()
    evaluated: list[dict] = []

    with tempfile.TemporaryDirectory(prefix="opentypeless-whisper-server-") as temp:
        temp_path = Path(temp)
        with (temp_path / "server.log").open("w+", encoding="utf-8") as server_log:
            process = subprocess.Popen(
                server_command(args, port, temp_path),
                stdin=subprocess.DEVNULL,
                stdout=server_log,
                stderr=subprocess.STDOUT,
                text=True,
            )
            try:
                wait_until_ready(process, port, min(args.request_timeout_seconds, 30))
                for index, record in enumerate(records, start=1):
                    hypothesis, elapsed = transcribe(
                        port, record["audio_path"], args.request_timeout_seconds
                    )
                    evaluated.append(
                        evaluate_record(
                            record,
                            hypothesis,
                            elapsed,
                            entity_forms,
                            global_corrections,
                        )
                    )
                    if index % 10 == 0 or index == len(records):
                        print(f"whisper_cpp: {index}/{len(records)}", flush=True)
            except Exception:
                server_log.flush()
                server_log.seek(0)
                tail = server_log.read()[-8_000:]
                if tail:
                    print(tail, file=sys.stderr)
                raise
            finally:
                stop_server(process)

    audio_set_digest = hashlib.sha256()
    for record in records:
        audio_set_digest.update(str(record["id"]).encode("utf-8"))
        audio_set_digest.update(b"\0")
        audio_set_digest.update(record["audio_sha256"].encode("ascii"))
        audio_set_digest.update(b"\n")
    metadata = {
        "runtime": "whisper.cpp",
        "runtime_commit": args.runtime_commit,
        "platform": platform.platform(),
        "machine": platform.machine(),
        "python": sys.version,
        "manifest_sha256": sha256_file(args.manifest),
        "audio_set_sha256": audio_set_digest.hexdigest(),
        "audio_cases": len(records),
        "model_file": {
            "name": args.model_file.name,
            "bytes": args.model_file.stat().st_size,
            "sha256": sha256_file(args.model_file),
        },
        "server_binary": {
            "bytes": args.server_binary.stat().st_size,
            "sha256": sha256_file(args.server_binary),
        },
        "script_files": {
            runner_path.name: sha256_file(runner_path),
            metrics_path.name: sha256_file(metrics_path),
        },
        "num_threads": args.num_threads,
        "language": "auto",
        "gpu": False,
        "beam_size": 5,
        "best_of": 5,
        "temperature": 0.0,
        "temperature_increment": 0.2,
        "corrections": [
            {"canonical": canonical, "alias": alias}
            for canonical, alias in global_corrections
        ],
    }
    summaries = summarize(evaluated)
    payload = {"metadata": metadata, "summary": summaries, "records": evaluated}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "results.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    markdown = render_markdown(metadata, summaries, evaluated)
    (args.output_dir / "results.md").write_text(markdown, encoding="utf-8")
    print(markdown)


if __name__ == "__main__":
    main()
