#!/usr/bin/env python3
"""Summarize local Xiaomi acceptance latency and character error rate without uploading text."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import unicodedata
from collections import defaultdict
from pathlib import Path

REQUIRED = {
    "route",
    "sample_id",
    "first_partial_ms",
    "final_after_eos_ms",
    "expected",
    "recognized",
}
MINIMUM_SAMPLES_PER_ROUTE = 20
MAXIMUM_ROWS = 10_000
MAXIMUM_TEXT_CODE_POINTS = 20_000


def normalized(value: str) -> str:
    return "".join(
        character
        for character in unicodedata.normalize("NFKC", value)
        if not character.isspace()
    )


def edit_distance(left: str, right: str) -> int:
    if len(left) > len(right):
        left, right = right, left
    previous = list(range(len(left) + 1))
    for right_index, right_character in enumerate(right, 1):
        current = [right_index]
        for left_index, left_character in enumerate(left, 1):
            current.append(
                min(
                    current[-1] + 1,
                    previous[left_index] + 1,
                    previous[left_index - 1] + (left_character != right_character),
                )
            )
        previous = current
    return previous[-1]


def number(value: str, label: str, row: int) -> float | None:
    clean = value.strip()
    if not clean:
        return None
    parsed = float(clean)
    if not math.isfinite(parsed) or parsed < 0:
        raise ValueError(f"row {row}: {label} must be a non-negative finite number")
    return parsed


def percentile(values: list[float], proportion: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(proportion * len(ordered)) - 1)]


def summarize(path: Path) -> dict[str, object]:
    routes: dict[str, list[dict[str, object]]] = defaultdict(list)
    sample_keys: set[tuple[str, str]] = set()
    with path.open(newline="", encoding="utf-8-sig") as source:
        reader = csv.DictReader(source)
        missing = REQUIRED - set(reader.fieldnames or ())
        if missing:
            raise ValueError("missing CSV columns: " + ", ".join(sorted(missing)))
        for row_number, row in enumerate(reader, 2):
            if row_number > MAXIMUM_ROWS + 1:
                raise ValueError(f"CSV exceeds the {MAXIMUM_ROWS:,}-row safety limit")
            route = (row["route"] or "").strip()
            sample_id = (row["sample_id"] or "").strip()
            if not route or not sample_id:
                raise ValueError(f"row {row_number}: route and sample_id are required")
            if len(route) > 100 or len(sample_id) > 100:
                raise ValueError(
                    f"row {row_number}: route and sample_id must be at most 100 characters"
                )
            sample_key = (route, sample_id)
            if sample_key in sample_keys:
                raise ValueError(
                    f"row {row_number}: duplicate sample_id {sample_id!r} for route {route!r}"
                )
            sample_keys.add(sample_key)
            expected = normalized(row["expected"] or "")
            recognized = normalized(row["recognized"] or "")
            if max(len(expected), len(recognized)) > MAXIMUM_TEXT_CODE_POINTS:
                raise ValueError(
                    f"row {row_number}: transcript exceeds the "
                    f"{MAXIMUM_TEXT_CODE_POINTS:,}-character safety limit"
                )
            routes[route].append(
                {
                    "first": number(row["first_partial_ms"], "first_partial_ms", row_number),
                    "final": number(row["final_after_eos_ms"], "final_after_eos_ms", row_number),
                    "expected": expected,
                    "recognized": recognized,
                }
            )

    result: dict[str, object] = {"source": path.name, "routes": {}}
    route_results: dict[str, object] = result["routes"]  # type: ignore[assignment]
    for route, samples in sorted(routes.items()):
        first = [sample["first"] for sample in samples if sample["first"] is not None]
        final = [sample["final"] for sample in samples if sample["final"] is not None]
        expected_characters = 0
        errors = 0
        accuracy_samples = 0
        for sample in samples:
            expected = str(sample["expected"])
            recognized = str(sample["recognized"])
            if not expected:
                continue
            expected_characters += len(expected)
            errors += edit_distance(expected, recognized)
            accuracy_samples += 1
        route_results[route] = {
            "samples": len(samples),
            "minimum_20_reference_samples_met": (
                accuracy_samples >= MINIMUM_SAMPLES_PER_ROUTE
            ),
            "first_partial_samples": len(first),
            "first_partial_p50_ms": statistics.median(first) if first else None,
            "first_partial_p95_ms": percentile(first, 0.95),
            "final_after_eos_samples": len(final),
            "final_after_eos_p50_ms": statistics.median(final) if final else None,
            "final_after_eos_p95_ms": percentile(final, 0.95),
            "accuracy_samples": accuracy_samples,
            "character_error_rate": (
                errors / expected_characters if expected_characters else None
            ),
        }
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    try:
        if arguments.output and arguments.output.exists():
            raise FileExistsError(
                f"refusing to overwrite existing output: {arguments.output}"
            )
        report = summarize(arguments.csv)
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if arguments.output:
            arguments.output.write_text(rendered, encoding="utf-8")
        else:
            print(rendered, end="")
    except (OSError, ValueError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
