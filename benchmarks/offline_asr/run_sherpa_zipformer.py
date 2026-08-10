#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import statistics
import sys
import time
import wave
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import sherpa_onnx

if __package__:
    from .asr_metrics import (
        apply_alias_corrections,
        contains_entity,
        count_entity_occurrences,
        error_details,
        model_hotword_phrase,
        normalize_characters,
        percentile,
    )
else:
    from asr_metrics import (
        apply_alias_corrections,
        contains_entity,
        count_entity_occurrences,
        error_details,
        model_hotword_phrase,
        normalize_characters,
        percentile,
    )


MODEL_FILES = {
    "tokens": "tokens.txt",
    "encoder": "encoder-epoch-99-avg-1.int8.onnx",
    "decoder": "decoder-epoch-99-avg-1.onnx",
    "joiner": "joiner-epoch-99-avg-1.int8.onnx",
    "bpe_vocab": "bpe.vocab",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark sherpa-onnx streaming Zipformer ASR")
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--num-threads", type=int, default=4)
    parser.add_argument("--max-active-paths", type=int, default=4)
    parser.add_argument("--hotword-scores", default="1.5,2.0,3.0")
    parser.add_argument("--chunk-ms", type=int, default=100)
    parser.add_argument("--limit", type=int)
    return parser.parse_args()


def load_manifest(path: Path, limit: int | None) -> list[dict]:
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
    if limit is not None:
        records = records[:limit]
    for record in records:
        record.setdefault("hotwords", [])
        record.setdefault("bias_phrases", record["hotwords"])
        record.setdefault("correction_aliases", [])
        record.setdefault("voice", record.get("source", "unknown"))
        record.setdefault("condition", "clean")
        if len(record["hotwords"]) != len(record["bias_phrases"]):
            raise ValueError(f"{record.get('id', '<unknown>')}: hotwords and bias_phrases differ")
        if record["correction_aliases"] and len(record["hotwords"]) != len(
            record["correction_aliases"]
        ):
            raise ValueError(
                f"{record.get('id', '<unknown>')}: hotwords and correction_aliases differ"
            )
        audio = Path(record["audio"])
        if not audio.is_absolute():
            audio = path.parent / audio
        if not audio.is_file():
            raise FileNotFoundError(audio)
        record["audio_path"] = audio
        record["audio_sha256"] = sha256_file(audio)
        record["audio_bytes"] = audio.stat().st_size
    return records


def read_wave(path: Path) -> tuple[int, np.ndarray]:
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise ValueError(f"{path} must be mono 16-bit PCM WAV")
        sample_rate = wav.getframerate()
        samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")
    return sample_rate, samples.astype(np.float32) / 32768.0


def create_recognizer(
    args: argparse.Namespace,
    decoding_method: str,
    score: float | None,
    hotwords_file: Path,
):
    paths = {key: args.model_dir / filename for key, filename in MODEL_FILES.items()}
    for path in paths.values():
        if not path.is_file():
            raise FileNotFoundError(path)
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(paths["tokens"]),
        encoder=str(paths["encoder"]),
        decoder=str(paths["decoder"]),
        joiner=str(paths["joiner"]),
        num_threads=args.num_threads,
        decoding_method=decoding_method,
        max_active_paths=args.max_active_paths,
        hotwords_file=str(hotwords_file) if score is not None else "",
        hotwords_score=score if score is not None else 1.5,
        modeling_unit="cjkchar+bpe",
        bpe_vocab=str(paths["bpe_vocab"]),
        model_type="zipformer",
        provider="cpu",
    )


def decode(recognizer, sample_rate: int, samples: np.ndarray, chunk_ms: int) -> dict:
    processing_started = time.perf_counter()
    stream = recognizer.create_stream()
    chunk_samples = max(1, int(sample_rate * chunk_ms / 1000))
    first_partial_audio_seconds = None

    for start in range(0, len(samples), chunk_samples):
        end = min(start + chunk_samples, len(samples))
        stream.accept_waveform(sample_rate, samples[start:end])
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        partial = recognizer.get_result(stream).strip()
        if first_partial_audio_seconds is None and partial:
            first_partial_audio_seconds = end / sample_rate

    stream.accept_waveform(sample_rate, np.zeros(int(sample_rate * 0.5), dtype=np.float32))
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    hypothesis = recognizer.get_result(stream).strip()
    streaming_processing_seconds = time.perf_counter() - processing_started

    return {
        "hypothesis": hypothesis,
        "streaming_processing_seconds": streaming_processing_seconds,
        "first_partial_audio_seconds": first_partial_audio_seconds,
    }


def count_expected_entity_hits(
    text: str, canonical_entities: list[str], alternate_forms: list[str]
) -> int:
    """Count expected mentions without reusing one recognized occurrence.

    Repeated expectations require repeated output occurrences. Canonical and
    spoken forms that normalize identically are counted only once.
    """
    if len(canonical_entities) != len(alternate_forms):
        raise ValueError("canonical_entities and alternate_forms must have equal length")
    expected_counts = Counter(normalize_characters(entity) for entity in canonical_entities)
    forms_by_canonical: dict[str, dict[str, str]] = defaultdict(dict)
    for canonical, alternate in zip(canonical_entities, alternate_forms):
        canonical_key = normalize_characters(canonical)
        for form in (canonical, alternate):
            forms_by_canonical[canonical_key].setdefault(normalize_characters(form), form)
    hits = 0
    for canonical_key, expected_count in expected_counts.items():
        observed_count = sum(
            count_entity_occurrences(text, form)
            for form in forms_by_canonical[canonical_key].values()
        )
        hits += min(expected_count, observed_count)
    return hits


def evaluate_record(
    record: dict,
    mode: str,
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
    recognized_entity_hits = count_expected_entity_hits(
        hypothesis, expected_entities, expected_bias_phrases
    )
    corrected_hypothesis = apply_alias_corrections(
        hypothesis,
        [canonical for canonical, _ in global_corrections],
        [alias for _, alias in global_corrections],
    )
    canonical_entity_hits = count_expected_entity_hits(
        corrected_hypothesis, expected_entities, expected_entities
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
        "mode": mode,
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
        "audio_sha256": record["audio_sha256"],
        "audio_bytes": record["audio_bytes"],
        "expected_entities": expected_entities,
        "expected_bias_phrases": expected_bias_phrases,
        "recognized_entity_hits": recognized_entity_hits,
        "canonical_entity_hits": canonical_entity_hits,
        "entity_total": len(expected_entities),
        "false_hotwords": false_hotwords,
        "false_corrections": false_corrections,
    }


def _micro_rate(edits: int, reference_units: int) -> float | None:
    return edits / reference_units if reference_units else None


def _percentile_or_none(values: list[float], percentile_value: float) -> float | None:
    return percentile(values, percentile_value) if values else None


def summarize_group(records: list[dict]) -> dict:
    metrics = sorted({record["metric"] for record in records})
    raw_edits = sum(record["edit_distance"] for record in records)
    corrected_edits = sum(record["corrected_edit_distance"] for record in records)
    reference_units = sum(record["reference_units"] for record in records)
    partials = [
        record["first_partial_audio_seconds"]
        for record in records
        if record["first_partial_audio_seconds"] is not None
    ]
    rtfs = [
        record["streaming_processing_rtf"]
        for record in records
        if record["streaming_processing_rtf"] is not None
    ]
    return {
        "cases": len(records),
        "metric": metrics[0] if len(metrics) == 1 else "mixed-unit",
        "edit_distance": raw_edits,
        "corrected_edit_distance": corrected_edits,
        "reference_units": reference_units,
        "micro_error_rate": _micro_rate(raw_edits, reference_units),
        "corrected_micro_error_rate": _micro_rate(corrected_edits, reference_units),
        "macro_utterance_error_rate": statistics.mean(
            record["error_rate"] for record in records
        ),
        "corrected_macro_utterance_error_rate": statistics.mean(
            record["corrected_error_rate"] for record in records
        ),
        "partial_result_count": len(partials),
        "partial_result_coverage": len(partials) / len(records),
        "first_partial_audio_seconds_p50": _percentile_or_none(partials, 0.5),
        "first_partial_audio_seconds_p95": _percentile_or_none(partials, 0.95),
        "streaming_processing_rtf_p50": _percentile_or_none(rtfs, 0.5),
        "streaming_processing_rtf_p95": _percentile_or_none(rtfs, 0.95),
    }


def _stratify(records: list[dict], field: str) -> dict[str, dict]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        groups[str(record[field])].append(record)
    return {key: summarize_group(groups[key]) for key in sorted(groups, key=str.casefold)}


def summarize(records: list[dict]) -> dict:
    by_mode: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        by_mode[record["mode"]].append(record)
    summaries = {}
    for mode, mode_records in by_mode.items():
        aggregate = summarize_group(mode_records)
        entity_total = sum(record["entity_total"] for record in mode_records)
        recognized_entity_hits = sum(record["recognized_entity_hits"] for record in mode_records)
        canonical_entity_hits = sum(record["canonical_entity_hits"] for record in mode_records)
        controls = [
            record
            for record in mode_records
            if record["category"] == "hotword_control"
        ]
        language_strata = _stratify(mode_records, "language")
        aggregate.update({
            "language_error_rate": {
                language: values["micro_error_rate"]
                for language, values in language_strata.items()
            },
            "recognized_entity_recall": (
                recognized_entity_hits / entity_total if entity_total else 0.0
            ),
            "recognized_entity_hits": recognized_entity_hits,
            "canonical_entity_recall": (
                canonical_entity_hits / entity_total if entity_total else 0.0
            ),
            "canonical_entity_hits": canonical_entity_hits,
            "entity_total": entity_total,
            "hotword_control_error_rate": (
                summarize_group(controls)["micro_error_rate"] if controls else None
            ),
            "false_hotword_insertions": sum(len(record["false_hotwords"]) for record in mode_records),
            "false_correction_insertions": sum(
                len(record["false_corrections"]) for record in mode_records
            ),
            "strata": {
                "language": language_strata,
                "condition": _stratify(mode_records, "condition"),
                "category": _stratify(mode_records, "category"),
                "voice": _stratify(mode_records, "voice"),
            },
        })
        summaries[mode] = aggregate
    return summaries


def render_markdown(metadata: dict, summaries: dict, records: list[dict]) -> str:
    def rate(value: float | None, suffix: str = "") -> str:
        return "n/a" if value is None else f"{value:.3f}{suffix}"

    def seconds(value: float | None) -> str:
        return "n/a" if value is None else f"{value:.2f}s"

    lines = [
        "# Offline ASR benchmark result",
        "",
        f"- Platform: `{metadata['platform']}`",
        f"- sherpa-onnx: `{metadata['sherpa_onnx_version']}`",
        f"- Manifest SHA-256: `{metadata['manifest_sha256']}`",
        f"- Audio-set SHA-256: `{metadata['audio_set_sha256']}`",
        f"- Audio cases: {metadata['audio_cases']}",
        "",
        "Interpret results using the manifest's source and condition strata; synthetic TTS remains a smoke test, not field-quality evidence.",
        "English uses WER, Chinese uses CER, and mixed speech uses Han-character plus contiguous-English-word MER.",
        "Headline scores are corpus-micro rates (summed edits / summed reference units); utterance-macro rates are auxiliary.",
        "",
        "| Mode | Micro raw / corrected | Macro raw / corrected | zh CER | en WER | mixed MER | Recognized / canonical entities | Control | False hotwords / corrections | Full streaming RTF p50 / p95 | Partial coverage; audio p50 / p95 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for mode, summary in summaries.items():
        language = summary["language_error_rate"]
        lines.append(
            "| "
            + " | ".join(
                [
                    mode,
                    f"{rate(summary['micro_error_rate'])} / {rate(summary['corrected_micro_error_rate'])}",
                    f"{rate(summary['macro_utterance_error_rate'])} / {rate(summary['corrected_macro_utterance_error_rate'])}",
                    rate(language.get("zh")),
                    rate(language.get("en")),
                    rate(language.get("mixed")),
                    f"{summary['recognized_entity_hits']}/{summary['entity_total']} ({summary['recognized_entity_recall']:.1%}) / "
                    f"{summary['canonical_entity_hits']}/{summary['entity_total']} ({summary['canonical_entity_recall']:.1%})",
                    rate(summary["hotword_control_error_rate"]),
                    f"{summary['false_hotword_insertions']} / {summary['false_correction_insertions']}",
                    f"{rate(summary['streaming_processing_rtf_p50'])} / {rate(summary['streaming_processing_rtf_p95'])}",
                    f"{summary['partial_result_coverage']:.1%}; "
                    f"{seconds(summary['first_partial_audio_seconds_p50'])} / "
                    f"{seconds(summary['first_partial_audio_seconds_p95'])}",
                ]
            )
            + " |"
        )

    lines.extend(["", "## Stratified corpus-micro scores", ""])
    for mode, summary in summaries.items():
        lines.extend(
            [
                f"### {mode}",
                "",
                "| Dimension | Value | Cases | Metric | Raw / corrected micro | Partial coverage |",
                "| --- | --- | ---: | --- | ---: | ---: |",
            ]
        )
        for dimension, groups in summary["strata"].items():
            for value, group in groups.items():
                lines.append(
                    f"| {dimension} | {value} | {group['cases']} | {group['metric']} | "
                    f"{rate(group['micro_error_rate'])} / {rate(group['corrected_micro_error_rate'])} | "
                    f"{group['partial_result_coverage']:.1%} |"
                )

    lines.extend(
        [
            "",
            "## Worst cases",
            "",
            "| Mode | ID | Voice / condition | Error | Reference | Hypothesis |",
            "| --- | --- | --- | ---: | --- | --- |",
        ]
    )
    for record in sorted(records, key=lambda item: item["error_rate"], reverse=True)[:20]:
        reference = record["reference"].replace("|", "\\|")
        hypothesis = record["hypothesis"].replace("|", "\\|")
        lines.append(
            f"| {record['mode']} | {record['id']} | {record['voice']} / {record['condition']} | "
            f"{record['error_rate']:.3f} | {reference} | {hypothesis} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    runner_path = Path(__file__).resolve()
    metrics_path = runner_path.with_name("asr_metrics.py")
    script_provenance = {
        runner_path.name: sha256_file(runner_path),
        metrics_path.name: sha256_file(metrics_path),
    }
    manifest_hash = sha256_file(args.manifest)
    model_paths = {key: args.model_dir / filename for key, filename in MODEL_FILES.items()}
    for path in model_paths.values():
        if not path.is_file():
            raise FileNotFoundError(path)
    model_provenance = {
        path.name: {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
        for path in model_paths.values()
    }
    records = load_manifest(args.manifest, args.limit)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    hotword_bias_phrases = sorted(
        {hotword for record in records for hotword in record.get("bias_phrases", record["hotwords"])},
        key=str.casefold,
    )
    entity_by_key: dict[str, str] = {}
    for record in records:
        for entity in record["hotwords"] + record.get("bias_phrases", record["hotwords"]):
            entity_by_key.setdefault(normalize_characters(entity), entity)
    entity_forms = sorted(entity_by_key.values(), key=str.casefold)
    correction_by_alias: dict[str, tuple[str, str]] = {}
    for record in records:
        for canonical, alias in zip(record["hotwords"], record["correction_aliases"]):
            normalized_alias = normalize_characters(alias)
            existing = correction_by_alias.get(normalized_alias)
            if existing is not None and existing[0] != canonical:
                raise ValueError(f"ambiguous correction alias {alias!r}")
            correction_by_alias[normalized_alias] = (canonical, alias)
    global_corrections = sorted(
        correction_by_alias.values(),
        key=lambda pair: pair[1],
    )
    model_hotwords = sorted(
        {model_hotword_phrase(phrase) for phrase in hotword_bias_phrases}, key=str.casefold
    )
    hotwords_file = args.output_dir / "hotwords.txt"
    hotwords_file.write_text(
        "".join(f"{hotword}\n" for hotword in model_hotwords), encoding="utf-8"
    )

    scores = [float(value) for value in args.hotword_scores.split(",") if value.strip()]
    modes: list[tuple[str, str, float | None]] = [
        ("greedy", "greedy_search", None),
        ("beam_baseline", "modified_beam_search", None),
    ] + ([
        (f"hotword_{score:g}", "modified_beam_search", score) for score in scores
    ] if hotword_bias_phrases else [])
    evaluated: list[dict] = []
    for mode, decoding_method, score in modes:
        print(f"Loading {mode} recognizer...", flush=True)
        recognizer = create_recognizer(args, decoding_method, score, hotwords_file)
        for index, record in enumerate(records, start=1):
            evaluated.append(
                evaluate_record(
                    record,
                    mode,
                    recognizer,
                    args.chunk_ms,
                    entity_forms,
                    global_corrections,
                )
            )
            if index % 20 == 0 or index == len(records):
                print(f"{mode}: {index}/{len(records)}", flush=True)
        del recognizer

    audio_set_digest = hashlib.sha256()
    for record in records:
        audio_set_digest.update(str(record["id"]).encode("utf-8"))
        audio_set_digest.update(b"\0")
        audio_set_digest.update(record["audio_sha256"].encode("ascii"))
        audio_set_digest.update(b"\n")
    metadata = {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "python": sys.version,
        "sherpa_onnx_version": sherpa_onnx.__version__,
        "manifest_sha256": manifest_hash,
        "corpus_sha256": manifest_hash,
        "audio_set_sha256": audio_set_digest.hexdigest(),
        "audio_cases": len(records),
        "model_files": model_provenance,
        "script_files": script_provenance,
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
        "max_active_paths": args.max_active_paths,
        "chunk_ms": args.chunk_ms,
        "hotwords": model_hotwords,
        "corrections": [
            {"canonical": canonical, "alias": alias}
            for canonical, alias in global_corrections
        ],
    }
    summaries = summarize(evaluated)
    payload = {"metadata": metadata, "summary": summaries, "records": evaluated}
    (args.output_dir / "results.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    markdown = render_markdown(metadata, summaries, evaluated)
    (args.output_dir / "results.md").write_text(markdown, encoding="utf-8")
    print(markdown)


if __name__ == "__main__":
    main()
