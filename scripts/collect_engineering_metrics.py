#!/usr/bin/env python3
"""Collect deterministic, advisory engineering trend metrics."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ElementTree


SCHEMA_VERSION = 1
KEY_SOURCES = (
    "android/app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java",
    "android/app/src/main/java/com/opentypeless/android/editor/host/EditorSessionManager.java",
    "android/app/src/main/java/com/opentypeless/android/editor/host/EditorTransactionManager.java",
    "android/app/src/main/java/com/opentypeless/android/editor/CompositionCoordinator.java",
    "android/app/src/main/java/com/opentypeless/android/ime/VoiceController.java",
    "android/app/src/main/java/com/opentypeless/android/ime/VoicePipeline.java",
    "android/app/src/main/java/com/opentypeless/android/settings/SettingsRepository.java",
)
APK_PATHS = (
    "android/app/build/outputs/apk/debug/app-debug.apk",
    "android/app/build/outputs/apk/release/app-release-unsigned.apk",
    "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    "android/test-host/build/outputs/apk/debug/test-host-debug.apk",
    "android/test-host/build/outputs/apk/androidTest/debug/test-host-debug-androidTest.apk",
)
METHOD_PATTERN = re.compile(
    r"(?m)^[ \t]*(?:@[A-Za-z_$][^\n]*\n[ \t]*)*"
    r"(?:(?:public|protected|private|static|final|synchronized|native|strictfp|default)\s+)+"
    r"(?:<[^>{}]+>\s+)?"
    r"[A-Za-z_$][\w$\.\[\]<>?, @]*\s+"
    r"(?P<name>[A-Za-z_$][\w$]*)\s*\([^;{}]*\)"
    r"(?:\s+throws\s+[^\{]+)?\s*\{"
)
DECISION_PATTERN = re.compile(r"\b(?:if|for|while|case|catch)\b|&&|\|\||\?")


def _sanitize_java(text: str) -> str:
    output = list(text)
    index = 0
    state = "code"
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if state == "code" and char == "/" and following == "/":
            output[index] = output[index + 1] = " "
            state = "line"
            index += 2
            continue
        if state == "code" and char == "/" and following == "*":
            output[index] = output[index + 1] = " "
            state = "block"
            index += 2
            continue
        if state == "code" and char in {'"', "'"}:
            output[index] = " "
            state = "string" if char == '"' else "character"
            index += 1
            continue
        if state in {"string", "character"}:
            if char == "\\":
                output[index] = " "
                if index + 1 < len(text):
                    if text[index + 1] != "\n":
                        output[index + 1] = " "
                    index += 2
                    continue
            if (state == "string" and char == '"') or (
                state == "character" and char == "'"
            ):
                output[index] = " "
                state = "code"
            elif char != "\n":
                output[index] = " "
            index += 1
            continue
        if state == "line":
            if char == "\n":
                state = "code"
            else:
                output[index] = " "
            index += 1
            continue
        if state == "block":
            if char == "*" and following == "/":
                output[index] = output[index + 1] = " "
                state = "code"
                index += 2
                continue
            if char != "\n":
                output[index] = " "
            index += 1
            continue
        index += 1
    return "".join(output)


def _matching_brace(text: str, opening: int) -> int | None:
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    return None


def source_metrics(path: Path) -> dict[str, object]:
    source = path.read_text(encoding="utf-8")
    sanitized = _sanitize_java(source)
    methods: list[dict[str, object]] = []
    for match in METHOD_PATTERN.finditer(sanitized):
        opening = sanitized.find("{", match.start(), match.end())
        closing = _matching_brace(sanitized, opening)
        if opening < 0 or closing is None:
            continue
        body = sanitized[opening : closing + 1]
        methods.append(
            {
                "name": match.group("name"),
                "line": sanitized.count("\n", 0, match.start()) + 1,
                "complexity_proxy": 1 + len(DECISION_PATTERN.findall(body)),
                "lines": body.count("\n") + 1,
            }
        )
    methods.sort(key=lambda item: (-int(item["complexity_proxy"]), -int(item["lines"]), str(item["name"])))
    return {
        "bytes": len(source.encode("utf-8")),
        "lines": len(source.splitlines()),
        "nonblank_lines": sum(bool(line.strip()) for line in source.splitlines()),
        "method_count": len(methods),
        "max_complexity_proxy": int(methods[0]["complexity_proxy"]) if methods else 0,
        "top_methods": methods[:10],
    }


def _test_results(root: Path) -> dict[str, int]:
    result = {"xml_suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in sorted(root.glob("android/**/build/test-results/**/*.xml")):
        try:
            suite = ElementTree.parse(path).getroot()
        except (ElementTree.ParseError, OSError):
            continue
        if suite.tag != "testsuite":
            continue
        result["xml_suites"] += 1
        for key in ("tests", "failures", "errors", "skipped"):
            try:
                result[key] += int(suite.attrib.get(key, "0"))
            except ValueError:
                continue
    return result


def _source_test_declarations(root: Path) -> dict[str, int]:
    patterns = {
        "android_jvm": "android/**/src/test/**/*Test.java",
        "android_instrumentation": "android/**/src/androidTest/**/*Test.java",
        "python": "**/test_*.py",
    }
    counts: dict[str, int] = {}
    for name, pattern in patterns.items():
        files = [path for path in root.glob(pattern) if path.is_file() and "/build/" not in path.as_posix()]
        if name.startswith("android"):
            counts[name] = sum(path.read_text(encoding="utf-8").count("@Test") for path in files)
        else:
            counts[name] = sum(
                len(re.findall(r"(?m)^\s*def\s+test_[A-Za-z0-9_]+\s*\(", path.read_text(encoding="utf-8")))
                for path in files
            )
    return counts


def _apk_metrics(root: Path) -> list[dict[str, object]]:
    values: list[dict[str, object]] = []
    for relative in APK_PATHS:
        path = root / relative
        if path.is_file() and not path.is_symlink():
            data = path.read_bytes()
            values.append(
                {
                    "path": relative,
                    "available": True,
                    "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
            )
        else:
            values.append({"path": relative, "available": False})
    return values


def collect(repo_root: Path) -> dict[str, object]:
    root = repo_root.resolve()
    sources: dict[str, object] = {}
    for relative in KEY_SOURCES:
        path = root / relative
        if not path.is_file() or path.is_symlink():
            sources[relative] = {"available": False}
        else:
            sources[relative] = {"available": True, **source_metrics(path)}
    try:
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=root,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        head = None
    return {
        "schema_version": SCHEMA_VERSION,
        "advisory_only": True,
        "method_complexity_definition": (
            "source proxy: 1 + if/for/while/case/catch/ternary/boolean decision tokens"
        ),
        "git_head": head,
        "key_sources": sources,
        "apk_artifacts": _apk_metrics(root),
        "test_results": _test_results(root),
        "source_test_declarations": _source_test_declarations(root),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    metrics = collect(args.repo_root)
    output = args.output
    if not output.is_absolute():
        output = args.repo_root.resolve() / output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metrics, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        "engineering metrics generated: "
        f"{len(metrics['key_sources'])} sources, "
        f"{metrics['test_results']['tests']} XML tests, "
        f"{sum(item['available'] for item in metrics['apk_artifacts'])} APKs"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
