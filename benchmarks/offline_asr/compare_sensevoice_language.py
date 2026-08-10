#!/usr/bin/env python3
"""Compare SenseVoice auto language detection with explicit language locks."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import sherpa_onnx

if __package__:
    from . import run_sherpa_offline as baseline_runner
    from .compare_sensevoice_itn import (
        audio_set_sha256,
        paired_counts,
        validate_baseline,
    )
else:
    import run_sherpa_offline as baseline_runner
    from compare_sensevoice_itn import (
        audio_set_sha256,
        paired_counts,
        validate_baseline,
    )


SUPPORTED_LANGUAGES = ("zh", "en", "yue", "ja", "ko")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare SenseVoice auto detection with explicit languages"
    )
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--baseline-results", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--language", action="append", choices=SUPPORTED_LANGUAGES, required=True
    )
    parser.add_argument("--num-threads", type=int, default=4)
    return parser.parse_args()


def unique_languages(languages: list[str]) -> list[str]:
    if len(languages) != len(set(languages)):
        raise ValueError("language values must be unique")
    return languages


def create_recognizer(model_dir: Path, num_threads: int, language: str):
    if language not in SUPPORTED_LANGUAGES:
        raise ValueError(f"unsupported SenseVoice language: {language}")
    paths = baseline_runner.model_paths(model_dir)
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(paths["model.int8.onnx"]),
        tokens=str(paths["tokens.txt"]),
        num_threads=num_threads,
        decoding_method="greedy_search",
        provider="cpu",
        language=language,
        use_itn=False,
    )


def percent(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.2%}"


def render_markdown(payload: dict) -> str:
    rows = [("auto", payload["baseline_summary"], None)]
    rows.extend(
        (language, item["summary"], item["paired_counts"])
        for language, item in payload["language_results"].items()
    )
    lines = [
        "# SenseVoice language-lock A/B",
        "",
        "Only the SenseVoice language parameter differs between rows; ITN remains off.",
        "",
        "| Language | Overall | zh CER | en WER | mixed MER | Improved / worsened / tied |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for language, summary, counts in rows:
        rates = summary["language_error_rate"]
        paired = (
            "baseline"
            if counts is None
            else f"{counts['improved']} / {counts['worsened']} / {counts['tied']}"
        )
        lines.append(
            f"| {language} | {percent(summary['micro_error_rate'])} | "
            f"{percent(rates.get('zh'))} | {percent(rates.get('en'))} | "
            f"{percent(rates.get('mixed'))} | {paired} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    languages = unique_languages(args.language)
    if not 1 <= args.num_threads <= 32:
        raise ValueError("num-threads must be between 1 and 32")
    output = args.output_dir.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output directory must be empty")

    model_dir = args.model_dir.resolve()
    manifest = args.manifest.resolve()
    paths = baseline_runner.model_paths(model_dir)
    records = baseline_runner.load_manifest(manifest, None)
    manifest_hash = baseline_runner.sha256_file(manifest)
    audio_hash = audio_set_sha256(records)
    model_files = {
        path.name: {"bytes": path.stat().st_size, "sha256": baseline_runner.sha256_file(path)}
        for path in paths.values()
    }
    baseline_path = args.baseline_results.resolve()
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    validate_baseline(baseline, records, manifest_hash, audio_hash, model_files)
    entities, corrections = baseline_runner.global_personalization(records)

    language_results = {}
    for language in languages:
        recognizer = create_recognizer(model_dir, args.num_threads, language)
        evaluated = []
        for index, record in enumerate(records, start=1):
            evaluated.append(
                baseline_runner.evaluate_record(record, recognizer, entities, corrections)
            )
            if index % 20 == 0 or index == len(records):
                print(f"sense_voice_{language}: {index}/{len(records)}", flush=True)
        language_results[language] = {
            "summary": baseline_runner.summarize(evaluated)["offline_greedy"],
            "paired_counts": paired_counts(baseline["records"], evaluated),
            "records": evaluated,
        }

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
            "only_changed_parameter": {
                "sense_voice_language": {"baseline": "auto", "candidates": languages}
            },
            "use_itn": False,
        },
        "baseline_summary": baseline["summary"]["offline_greedy"],
        "language_results": language_results,
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
