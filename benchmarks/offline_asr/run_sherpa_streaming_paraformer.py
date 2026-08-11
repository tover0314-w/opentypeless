#!/usr/bin/env python3
"""Benchmark the exact streaming Paraformer model shipped by Android.

The older Zipformer benchmark remains immutable because its script hash belongs
to a published result.  This runner intentionally has a separate provenance
chain and mirrors ``OfflineStreamingRecognizer``: 16 kHz PCM, greedy search,
endpointing disabled, 40 ms chunks, and the Paraformer final-flush option.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx

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
        render_markdown,
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
        render_markdown,
        sha256_file,
        summarize,
    )


MODEL_FILES = ("tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx")
MODEL_REVISION = "8e40c43232a1c5c66c82111efc5820d3accca11b"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Benchmark Android's pinned streaming Paraformer model"
    )
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--num-threads", type=int, default=2)
    parser.add_argument("--chunk-ms", type=int, default=40)
    parser.add_argument("--limit", type=int)
    return parser.parse_args()


def model_paths(model_dir: Path) -> dict[str, Path]:
    paths = {name: model_dir / name for name in MODEL_FILES}
    for path in paths.values():
        if not path.is_file():
            raise FileNotFoundError(path)
    return paths


def create_recognizer(args: argparse.Namespace):
    paths = model_paths(args.model_dir)
    return sherpa_onnx.OnlineRecognizer.from_paraformer(
        tokens=str(paths["tokens.txt"]),
        encoder=str(paths["encoder.int8.onnx"]),
        decoder=str(paths["decoder.int8.onnx"]),
        num_threads=args.num_threads,
        sample_rate=16_000,
        feature_dim=80,
        enable_endpoint_detection=False,
        decoding_method="greedy_search",
        provider="cpu",
    )


def _result_text(recognizer, stream) -> str:
    result = recognizer.get_result(stream)
    if result is None:
        return ""
    return str(result).strip()


def _common_prefix_code_points(left: str, right: str) -> int:
    left_points = list(left)
    right_points = list(right)
    count = 0
    for left_point, right_point in zip(left_points, right_points):
        if left_point != right_point:
            break
        count += 1
    return count


def decode(recognizer, sample_rate: int, samples: np.ndarray, chunk_ms: int) -> dict:
    if sample_rate != 16_000:
        raise ValueError("streaming Paraformer input must be 16 kHz")
    if chunk_ms <= 0 or chunk_ms > 1_000:
        raise ValueError("chunk_ms must be between 1 and 1000")
    started = time.perf_counter()
    stream = recognizer.create_stream()
    chunk_samples = max(1, int(sample_rate * chunk_ms / 1_000))
    first_partial_audio_seconds = None
    partials: list[str] = []
    earlier_text_revisions = 0
    last = ""

    for start in range(0, len(samples), chunk_samples):
        end = min(start + chunk_samples, len(samples))
        stream.accept_waveform(sample_rate, samples[start:end])
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        current = _result_text(recognizer, stream)
        if current and current != last:
            if first_partial_audio_seconds is None:
                first_partial_audio_seconds = end / sample_rate
            if last and _common_prefix_code_points(last, current) < len(last):
                earlier_text_revisions += 1
            partials.append(current)
            last = current

    # Match Android's explicit user-stop flush.  No synthetic silence is added:
    # doing so would change both latency and the acoustic input under test.
    stream.set_option("is_final", "1")
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    hypothesis = _result_text(recognizer, stream)
    if not hypothesis:
        hypothesis = last
    if hypothesis and hypothesis != last:
        if last and _common_prefix_code_points(last, hypothesis) < len(last):
            earlier_text_revisions += 1
        partials.append(hypothesis)

    return {
        "hypothesis": hypothesis,
        "streaming_processing_seconds": time.perf_counter() - started,
        "first_partial_audio_seconds": first_partial_audio_seconds,
        "partial_revision_count": len(partials),
        "earlier_text_revision_count": earlier_text_revisions,
        "last_partial": last,
    }


def evaluate_record(
    record: dict,
    recognizer,
    chunk_ms: int,
    global_entity_forms: list[str],
    global_corrections: list[tuple[str, str]],
) -> dict:
    sample_rate, samples = read_wave(record["audio_path"])
    decoded = decode(recognizer, sample_rate, samples, chunk_ms)
    duration = len(samples) / sample_rate
    hypothesis = decoded["hypothesis"]
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
        entity
        for entity in global_entity_forms
        if normalize_characters(entity) not in expected_form_keys
        and not contains_entity(record["reference"], entity)
        for _ in range(count_entity_occurrences(hypothesis, entity))
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
        "mode": "streaming_paraformer_greedy",
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
        "streaming_processing_seconds": decoded["streaming_processing_seconds"],
        "streaming_processing_rtf": (
            decoded["streaming_processing_seconds"] / duration if duration else None
        ),
        "first_partial_audio_seconds": decoded["first_partial_audio_seconds"],
        "partial_result_observed": decoded["first_partial_audio_seconds"] is not None,
        "partial_revision_count": decoded["partial_revision_count"],
        "earlier_text_revision_count": decoded["earlier_text_revision_count"],
        "last_partial": decoded["last_partial"],
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


def main() -> None:
    args = parse_args()
    runner_path = Path(__file__).resolve()
    metrics_path = runner_path.with_name("asr_metrics.py")
    model = model_paths(args.model_dir)
    records = load_manifest(args.manifest, args.limit)
    entity_forms, global_corrections = global_personalization(records)
    recognizer = create_recognizer(args)
    evaluated = []
    for index, record in enumerate(records, start=1):
        evaluated.append(
            evaluate_record(
                record,
                recognizer,
                args.chunk_ms,
                entity_forms,
                global_corrections,
            )
        )
        if index % 20 == 0 or index == len(records):
            print(f"streaming_paraformer_greedy: {index}/{len(records)}", flush=True)

    audio_set_digest = hashlib.sha256()
    for record in records:
        audio_set_digest.update(str(record["id"]).encode("utf-8"))
        audio_set_digest.update(b"\0")
        audio_set_digest.update(record["audio_sha256"].encode("ascii"))
        audio_set_digest.update(b"\n")
    metadata = {
        "model_revision": MODEL_REVISION,
        "platform": platform.platform(),
        "machine": platform.machine(),
        "python": sys.version,
        "sherpa_onnx_version": sherpa_onnx.__version__,
        "manifest_sha256": sha256_file(args.manifest),
        "corpus_sha256": sha256_file(args.manifest),
        "audio_set_sha256": audio_set_digest.hexdigest(),
        "audio_cases": len(records),
        "model_files": {
            path.name: {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
            for path in model.values()
        },
        "script_files": {
            runner_path.name: sha256_file(runner_path),
            metrics_path.name: sha256_file(metrics_path),
        },
        "audio_files": [
            {
                "id": record["id"],
                "voice": record["voice"],
                "condition": record["condition"],
                "bytes": record["audio_bytes"],
                "sha256": record["audio_sha256"],
            }
            for record in records
        ],
        "num_threads": args.num_threads,
        "chunk_ms": args.chunk_ms,
        "endpoint_detection": False,
        "corrections": [
            {"canonical": canonical, "alias": alias}
            for canonical, alias in global_corrections
        ],
    }
    summary = summarize(evaluated)
    mode = summary["streaming_paraformer_greedy"]
    mode["partial_revision_count"] = sum(
        record["partial_revision_count"] for record in evaluated
    )
    mode["earlier_text_revision_count"] = sum(
        record["earlier_text_revision_count"] for record in evaluated
    )
    mode["utterances_with_earlier_text_revision"] = sum(
        record["earlier_text_revision_count"] > 0 for record in evaluated
    )
    payload = {"metadata": metadata, "summary": summary, "records": evaluated}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "results.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    markdown = render_markdown(metadata, summary, evaluated)
    (args.output_dir / "results.md").write_text(markdown, encoding="utf-8")
    print(markdown)


if __name__ == "__main__":
    main()
