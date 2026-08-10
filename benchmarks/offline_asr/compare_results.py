#!/usr/bin/env python3
"""Compare candidate result JSON files that used the same audio set."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare offline ASR candidate results")
    parser.add_argument(
        "--result",
        action="append",
        required=True,
        metavar="LABEL[@MODE]=RESULTS_JSON",
    )
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load_spec(spec: str) -> tuple[str, str | None, dict]:
    raw_label, separator, raw_path = spec.partition("=")
    if not separator or not raw_label.strip() or not raw_path.strip():
        raise ValueError("--result must use LABEL[@MODE]=RESULTS_JSON")
    label, mode_separator, mode = raw_label.partition("@")
    if not label.strip() or (mode_separator and not mode.strip()):
        raise ValueError("--result must use LABEL[@MODE]=RESULTS_JSON")
    path = Path(raw_path).expanduser().resolve()
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload.get("records"), list) or not isinstance(
        payload.get("metadata"), dict
    ):
        raise ValueError(f"{path} is not a benchmark results file")
    return label.strip(), mode.strip() if mode_separator else None, payload


def group_scores(records: list[dict]) -> dict[str, dict]:
    groups: dict[tuple[str, str], list[int]] = defaultdict(lambda: [0, 0, 0])
    for record in records:
        key = (str(record["category"]), str(record["language"]))
        groups[key][0] += int(record["edit_distance"])
        groups[key][1] += int(record["reference_units"])
        groups[key][2] += 1
    return {
        f"{category}:{language}": {
            "cases": cases,
            "edit_distance": edits,
            "reference_units": units,
            "micro_error_rate": edits / units if units else None,
        }
        for (category, language), (edits, units, cases) in sorted(groups.items())
    }


def model_bytes(metadata: dict) -> int:
    model_files = metadata.get("model_files")
    if isinstance(model_files, dict) and model_files:
        return sum(int(item["bytes"]) for item in model_files.values())
    model_file = metadata.get("model_file")
    if isinstance(model_file, dict) and "bytes" in model_file:
        return int(model_file["bytes"])
    raise ValueError("benchmark metadata has no model file sizes")


def compare(specs: list[str]) -> dict:
    loaded = [load_spec(spec) for spec in specs]
    labels = [label for label, _, _ in loaded]
    if len(labels) != len(set(labels)):
        raise ValueError("candidate labels must be unique")
    audio_sets = {
        payload["metadata"].get("audio_set_sha256") for _, _, payload in loaded
    }
    manifests = {
        payload["metadata"].get("manifest_sha256") for _, _, payload in loaded
    }
    selected_records = []
    for label, selected_mode, payload in loaded:
        summary = payload["summary"]
        if selected_mode is None:
            if len(summary) != 1:
                raise ValueError(f"{label} has multiple modes; select one with LABEL@MODE")
            selected_mode = next(iter(summary))
        if selected_mode not in summary:
            raise ValueError(f"{label} has no decoder mode {selected_mode!r}")
        records = [
            record for record in payload["records"] if record["mode"] == selected_mode
        ]
        selected_records.append((label, selected_mode, payload, records))
    case_counts = {len(records) for _, _, _, records in selected_records}
    if None in audio_sets or len(audio_sets) != 1 or len(manifests) != 1 or len(case_counts) != 1:
        raise ValueError("results must use the same complete manifest and audio set")
    candidates = {}
    for label, selected_mode, payload, records in selected_records:
        mode = payload["summary"][selected_mode]
        candidates[label] = {
            "model_type": payload["metadata"].get(
                "model_type", payload["metadata"].get("runtime", "streaming_zipformer")
            ),
            "language_error_rate": mode["language_error_rate"],
            "processing_rtf_p50": mode["streaming_processing_rtf_p50"],
            "processing_rtf_p95": mode["streaming_processing_rtf_p95"],
            "recognized_entity_recall": mode["recognized_entity_recall"],
            "canonical_entity_recall": mode["canonical_entity_recall"],
            "model_bytes": model_bytes(payload["metadata"]),
            "decoder_mode": selected_mode,
            "groups": group_scores(records),
        }
    return {
        "manifest_sha256": next(iter(manifests)),
        "audio_set_sha256": next(iter(audio_sets)),
        "cases": next(iter(case_counts)),
        "candidates": candidates,
    }


def render_markdown(comparison: dict) -> str:
    lines = [
        "# Offline ASR candidate comparison",
        "",
        f"- Cases: {comparison['cases']}",
        f"- Manifest SHA-256: `{comparison['manifest_sha256']}`",
        f"- Audio-set SHA-256: `{comparison['audio_set_sha256']}`",
        "",
        "| Candidate | Model MiB | zh CER | en WER | mixed MER | RTF p50 / p95 | Entity raw / corrected |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]

    def percent(value) -> str:
        return "n/a" if value is None else f"{value:.1%}"

    for label, candidate in comparison["candidates"].items():
        rates = candidate["language_error_rate"]
        lines.append(
            f"| {label} | {candidate['model_bytes'] / 1024 / 1024:.1f} | "
            f"{percent(rates.get('zh'))} | {percent(rates.get('en'))} | "
            f"{percent(rates.get('mixed'))} | {candidate['processing_rtf_p50']:.3f} / "
            f"{candidate['processing_rtf_p95']:.3f} | "
            f"{percent(candidate['recognized_entity_recall'])} / "
            f"{percent(candidate['canonical_entity_recall'])} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    comparison = compare(args.result)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.suffix.lower() == ".json":
        content = json.dumps(comparison, ensure_ascii=False, indent=2) + "\n"
    else:
        content = render_markdown(comparison)
    args.output.write_text(content, encoding="utf-8")
    print(content, end="")


if __name__ == "__main__":
    main()
