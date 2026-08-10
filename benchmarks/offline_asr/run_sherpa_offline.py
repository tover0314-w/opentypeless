#!/usr/bin/env python3
"""Benchmark non-streaming sherpa-onnx models on the shared ASR corpus.

The first benchmark runner is intentionally kept immutable because its hash is
part of the published Zipformer result.  This runner reuses only its scoring
helpers and records its own provenance for SenseVoice and Paraformer candidates.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
import time
from collections import defaultdict
from pathlib import Path

import sherpa_onnx

if __package__:
    from .asr_metrics import (
        apply_alias_corrections,
        contains_entity,
        count_entity_occurrences,
        error_details,
        normalize_characters,
    )
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
    from run_sherpa_zipformer import (
        count_expected_entity_hits,
        load_manifest,
        read_wave,
        sha256_file,
        summarize,
    )


MODEL_FILES = ("tokens.txt", "model.int8.onnx")
MODEL_TYPES = ("sense_voice", "paraformer")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Benchmark sherpa-onnx offline SenseVoice or Paraformer ASR"
    )
    parser.add_argument("--model-type", choices=MODEL_TYPES, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--num-threads", type=int, default=4)
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
    common = {
        "tokens": str(paths["tokens.txt"]),
        "num_threads": args.num_threads,
        "decoding_method": "greedy_search",
        "provider": "cpu",
    }
    if args.model_type == "sense_voice":
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=str(paths["model.int8.onnx"]),
            language="auto",
            use_itn=False,
            **common,
        )
    if args.model_type == "paraformer":
        return sherpa_onnx.OfflineRecognizer.from_paraformer(
            paraformer=str(paths["model.int8.onnx"]),
            **common,
        )
    raise ValueError(f"unsupported model type: {args.model_type}")


def decode(recognizer, sample_rate, samples) -> dict:
    started = time.perf_counter()
    stream = recognizer.create_stream()
    stream.accept_waveform(sample_rate, samples)
    recognizer.decode_stream(stream)
    result = stream.result
    hypothesis = result.text if hasattr(result, "text") else str(result)
    return {
        "hypothesis": hypothesis.strip(),
        "streaming_processing_seconds": time.perf_counter() - started,
        "first_partial_audio_seconds": None,
    }


def evaluate_record(
    record: dict,
    recognizer,
    global_entity_forms: list[str],
    global_corrections: list[tuple[str, str]],
) -> dict:
    sample_rate, samples = read_wave(record["audio_path"])
    decoded = decode(recognizer, sample_rate, samples)
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
        "mode": "offline_greedy",
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


def global_personalization(records: list[dict]) -> tuple[list[str], list[tuple[str, str]]]:
    entity_by_key: dict[str, str] = {}
    correction_by_alias: dict[str, tuple[str, str]] = {}
    for record in records:
        for entity in record["hotwords"] + record.get("bias_phrases", record["hotwords"]):
            entity_by_key.setdefault(normalize_characters(entity), entity)
        for canonical, alias in zip(record["hotwords"], record["correction_aliases"]):
            key = normalize_characters(alias)
            existing = correction_by_alias.get(key)
            if existing is not None and existing[0] != canonical:
                raise ValueError(f"ambiguous correction alias {alias!r}")
            correction_by_alias[key] = (canonical, alias)
    return (
        sorted(entity_by_key.values(), key=str.casefold),
        sorted(correction_by_alias.values(), key=lambda pair: pair[1]),
    )


def render_markdown(metadata: dict, summary: dict, records: list[dict]) -> str:
    mode = summary["offline_greedy"]
    language = mode["language_error_rate"]

    def percent(value: float | None) -> str:
        return "n/a" if value is None else f"{value:.1%}"

    lines = [
        "# Offline ASR candidate result",
        "",
        f"- Model type: `{metadata['model_type']}`",
        f"- Platform: `{metadata['platform']}`",
        f"- sherpa-onnx: `{metadata['sherpa_onnx_version']}`",
        f"- Audio cases: {metadata['audio_cases']}",
        f"- Manifest SHA-256: `{metadata['manifest_sha256']}`",
        f"- Audio-set SHA-256: `{metadata['audio_set_sha256']}`",
        "",
        "This recognizer is non-streaming. Processing RTF covers waveform acceptance, decode, and result retrieval; partial-result coverage is intentionally zero.",
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


def main() -> None:
    args = parse_args()
    runner_path = Path(__file__).resolve()
    metrics_path = runner_path.with_name("asr_metrics.py")
    manifest_hash = sha256_file(args.manifest)
    paths = model_paths(args.model_dir)
    records = load_manifest(args.manifest, args.limit)
    entity_forms, global_corrections = global_personalization(records)
    recognizer = create_recognizer(args)
    evaluated: list[dict] = []
    for index, record in enumerate(records, start=1):
        evaluated.append(
            evaluate_record(record, recognizer, entity_forms, global_corrections)
        )
        if index % 20 == 0 or index == len(records):
            print(f"{args.model_type}: {index}/{len(records)}", flush=True)

    audio_set_digest = hashlib.sha256()
    for record in records:
        audio_set_digest.update(str(record["id"]).encode("utf-8"))
        audio_set_digest.update(b"\0")
        audio_set_digest.update(record["audio_sha256"].encode("ascii"))
        audio_set_digest.update(b"\n")
    metadata = {
        "model_type": args.model_type,
        "platform": platform.platform(),
        "machine": platform.machine(),
        "python": sys.version,
        "sherpa_onnx_version": sherpa_onnx.__version__,
        "manifest_sha256": manifest_hash,
        "audio_set_sha256": audio_set_digest.hexdigest(),
        "audio_cases": len(records),
        "model_files": {
            path.name: {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
            for path in paths.values()
        },
        "script_files": {
            runner_path.name: sha256_file(runner_path),
            metrics_path.name: sha256_file(metrics_path),
        },
        "num_threads": args.num_threads,
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
