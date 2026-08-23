#!/usr/bin/env python3
"""Run a provenance-checked SenseVoice inverse-text-normalization A/B.

The published candidate runner stays byte-identical to the audited baseline. This companion
loads that exact baseline result, rejects any input drift, and decodes the same records with only
SenseVoice's ``use_itn`` switch changed from false to true.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import sherpa_onnx

if __package__:
    from . import run_sherpa_offline as baseline_runner
else:
    import run_sherpa_offline as baseline_runner


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare SenseVoice with ITN off versus on")
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--baseline-results", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--num-threads", type=int, default=4)
    return parser.parse_args()


def audio_set_sha256(records: list[dict]) -> str:
    digest = hashlib.sha256()
    for record in records:
        digest.update(str(record["id"]).encode("utf-8"))
        digest.update(b"\0")
        digest.update(record["audio_sha256"].encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def validate_baseline(
    baseline: dict,
    records: list[dict],
    manifest_hash: str,
    audio_hash: str,
    model_files: dict[str, dict[str, object]],
) -> None:
    metadata = baseline.get("metadata", {})
    if metadata.get("model_type") != "sense_voice":
        raise ValueError("Baseline must be a SenseVoice result")
    if metadata.get("manifest_sha256") != manifest_hash:
        raise ValueError("Baseline manifest SHA-256 does not match")
    if metadata.get("audio_set_sha256") != audio_hash:
        raise ValueError("Baseline audio-set SHA-256 does not match")
    if metadata.get("model_files") != model_files:
        raise ValueError("Baseline model hashes do not match")
    runner_hash = baseline_runner.sha256_file(Path(baseline_runner.__file__).resolve())
    if metadata.get("script_files", {}).get("run_sherpa_offline.py") != runner_hash:
        raise ValueError("Baseline was not produced by the current audited runner")
    baseline_records = baseline.get("records", [])
    if len(baseline_records) != len(records):
        raise ValueError("Baseline record count does not match")
    for expected, observed in zip(records, baseline_records):
        if (
            expected["id"] != observed.get("id")
            or expected["reference"] != observed.get("reference")
            or expected["audio_sha256"] != observed.get("audio_sha256")
        ):
            raise ValueError(f"Baseline record drift at {expected['id']}")


def create_itn_recognizer(model_dir: Path, num_threads: int):
    paths = baseline_runner.model_paths(model_dir)
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(paths["model.int8.onnx"]),
        tokens=str(paths["tokens.txt"]),
        num_threads=num_threads,
        decoding_method="greedy_search",
        provider="cpu",
        language="auto",
        use_itn=True,
    )


def paired_counts(baseline_records: list[dict], itn_records: list[dict]) -> dict[str, int]:
    if len(baseline_records) != len(itn_records):
        raise ValueError("A/B record count does not match")
    counts = {"changed": 0, "improved": 0, "worsened": 0, "tied": 0}
    for baseline, itn in zip(baseline_records, itn_records):
        if baseline["id"] != itn["id"] or baseline["reference"] != itn["reference"]:
            raise ValueError("A/B record order does not match")
        if baseline["hypothesis"] != itn["hypothesis"]:
            counts["changed"] += 1
        delta = itn["edit_distance"] - baseline["edit_distance"]
        if delta < 0:
            counts["improved"] += 1
        elif delta > 0:
            counts["worsened"] += 1
        else:
            counts["tied"] += 1
    return counts


def percent(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.2%}"


def render_markdown(payload: dict) -> str:
    baseline = payload["baseline_summary"]
    itn = payload["itn_summary"]
    base_lang = baseline["language_error_rate"]
    itn_lang = itn["language_error_rate"]
    counts = payload["paired_counts"]
    return "\n".join(
        [
            "# SenseVoice ITN A/B",
            "",
            "Only the SenseVoice `use_itn` switch differs between these two runs.",
            "",
            "| Mode | Overall | zh CER | en WER | mixed MER | Entity recall |",
            "| --- | ---: | ---: | ---: | ---: | ---: |",
            f"| ITN off | {percent(baseline['micro_error_rate'])} | "
            f"{percent(base_lang.get('zh'))} | {percent(base_lang.get('en'))} | "
            f"{percent(base_lang.get('mixed'))} | "
            f"{baseline['recognized_entity_hits']}/{baseline['entity_total']} |",
            f"| ITN on | {percent(itn['micro_error_rate'])} | "
            f"{percent(itn_lang.get('zh'))} | {percent(itn_lang.get('en'))} | "
            f"{percent(itn_lang.get('mixed'))} | "
            f"{itn['recognized_entity_hits']}/{itn['entity_total']} |",
            "",
            f"Hypotheses changed: {counts['changed']}; improved: {counts['improved']}; "
            f"worsened: {counts['worsened']}; tied: {counts['tied']}.",
            "",
        ]
    )


def main() -> None:
    args = parse_args()
    if args.num_threads < 1 or args.num_threads > 32:
        raise ValueError("num-threads must be between 1 and 32")
    output = args.output_dir.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output directory must be empty")

    paths = baseline_runner.model_paths(args.model_dir.resolve())
    records = baseline_runner.load_manifest(args.manifest.resolve(), None)
    manifest_hash = baseline_runner.sha256_file(args.manifest.resolve())
    audio_hash = audio_set_sha256(records)
    model_files = {
        path.name: {"bytes": path.stat().st_size, "sha256": baseline_runner.sha256_file(path)}
        for path in paths.values()
    }
    baseline_path = args.baseline_results.resolve()
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    validate_baseline(baseline, records, manifest_hash, audio_hash, model_files)

    recognizer = create_itn_recognizer(args.model_dir.resolve(), args.num_threads)
    entities, corrections = baseline_runner.global_personalization(records)
    evaluated = []
    for index, record in enumerate(records, start=1):
        evaluated.append(
            baseline_runner.evaluate_record(record, recognizer, entities, corrections)
        )
        if index % 20 == 0 or index == len(records):
            print(f"sense_voice_itn: {index}/{len(records)}", flush=True)
    summaries = baseline_runner.summarize(evaluated)
    baseline_summary = baseline["summary"]["offline_greedy"]
    itn_summary = summaries["offline_greedy"]
    payload = {
        "schema_version": 1,
        "metadata": {
            "manifest_sha256": manifest_hash,
            "audio_set_sha256": audio_hash,
            "baseline_results_sha256": baseline_runner.sha256_file(baseline_path),
            "baseline_runner_sha256": baseline_runner.sha256_file(
                Path(baseline_runner.__file__).resolve()
            ),
            "experiment_script_sha256": baseline_runner.sha256_file(Path(__file__).resolve()),
            "model_files": model_files,
            "num_threads": args.num_threads,
            "only_changed_parameter": {"use_itn": {"baseline": False, "candidate": True}},
        },
        "baseline_summary": baseline_summary,
        "itn_summary": itn_summary,
        "paired_counts": paired_counts(baseline["records"], evaluated),
        "itn_records": evaluated,
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "results.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    markdown = render_markdown(payload)
    (output / "results.md").write_text(markdown, encoding="utf-8")
    print(markdown)


if __name__ == "__main__":
    main()
