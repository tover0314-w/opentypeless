#!/usr/bin/env python3
"""Validate the repository documentation entrypoints and local Markdown links."""

from __future__ import annotations

import argparse
import html
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


SPEC_ROOT = Path("docs/opentypeless_specs")
SPEC_INDEX = SPEC_ROOT / "00_README.md"
ADR_ROOT = Path("docs/adr")
PACKAGE_FILES = (
    "00_README.md",
    "01_PRODUCT_DESIGN.md",
    "02_ARCHITECTURE_DEVELOPMENT.md",
    "03_UX_DESIGN_PROTOTYPES.md",
    "04_ACTION_PROTOCOL_V1.md",
    "05_DATA_PERSONALIZATION.md",
    "06_SECURITY_PRIVACY.md",
    "07_IMPLEMENTATION_BACKLOG.md",
    "08_TEST_VALIDATION.md",
    "09_ADR_RESEARCH.md",
    "10_RELEASE_OPERATIONS.md",
    "AGENTS.md",
    "FILE_MANIFEST.md",
    "OpenTypeless_FULL_SPEC.md",
    "PACKAGE_VALIDATION.md",
    "TASK_TEMPLATE.md",
)
ENTRYPOINT_FILES = (Path("README.md"), Path("README_zh.md"), Path("AGENTS.md"))
LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
HEADING_PATTERN = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$")


def _github_slug(value: str) -> str:
    value = re.sub(r"[`*_~]", "", value.strip().lower())
    value = re.sub(r"[^\w\- ]", "", value, flags=re.UNICODE)
    return value.replace(" ", "-")


def _anchors(text: str) -> set[str]:
    anchors: set[str] = set()
    counts: dict[str, int] = {}
    fenced = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        match = HEADING_PATTERN.match(line)
        if match is None:
            continue
        base = _github_slug(match.group(1))
        count = counts.get(base, 0)
        counts[base] = count + 1
        anchors.add(base if count == 0 else f"{base}-{count}")
    return anchors


def _link_target(raw: str) -> str:
    target = html.unescape(raw.strip())
    if target.startswith("<") and ">" in target:
        return target[1 : target.index(">")]
    return target.split(maxsplit=1)[0]


def _validate_link(repo_root: Path, source: Path, raw_target: str) -> str | None:
    target = _link_target(raw_target)
    parsed = urlsplit(target)
    if parsed.scheme or target.startswith("//"):
        return None
    path_text = unquote(parsed.path)
    if not path_text:
        destination = source
    elif Path(path_text).is_absolute():
        return f"{source.relative_to(repo_root)}: absolute local link is forbidden: {target}"
    else:
        destination = (source.parent / path_text).resolve()
    try:
        destination.relative_to(repo_root)
    except ValueError:
        return f"{source.relative_to(repo_root)}: local link escapes repository: {target}"
    if not destination.exists():
        return f"{source.relative_to(repo_root)}: missing local link target: {target}"
    if parsed.fragment and destination.is_file() and destination.suffix.lower() == ".md":
        fragment = unquote(parsed.fragment).lower()
        try:
            destination_text = destination.read_text(encoding="utf-8")
        except UnicodeError as error:
            return f"{source.relative_to(repo_root)}: linked Markdown is invalid UTF-8: {error}"
        if fragment not in _anchors(destination_text):
            return f"{source.relative_to(repo_root)}: missing Markdown anchor: {target}"
    return None


def validate_repository(repo_root: Path) -> list[str]:
    repo_root = repo_root.resolve()
    errors: list[str] = []
    spec_root = repo_root / SPEC_ROOT
    for name in PACKAGE_FILES:
        path = spec_root / name
        if not path.is_file() or path.is_symlink():
            errors.append(f"missing or non-regular specification file: {path.relative_to(repo_root)}")
            continue
        try:
            path.read_text(encoding="utf-8")
        except UnicodeError as error:
            errors.append(f"{path.relative_to(repo_root)}: invalid UTF-8: {error}")

    expected_index = SPEC_INDEX.as_posix()
    for relative in ENTRYPOINT_FILES:
        path = repo_root / relative
        if not path.is_file():
            errors.append(f"missing repository documentation entrypoint: {relative}")
            continue
        text = path.read_text(encoding="utf-8")
        if expected_index not in text:
            errors.append(f"{relative}: does not reference {expected_index}")

    index = repo_root / SPEC_INDEX
    if index.is_file():
        index_text = index.read_text(encoding="utf-8")
        for name in PACKAGE_FILES:
            if name == SPEC_INDEX.name:
                continue
            if f"]({name})" not in index_text:
                errors.append(f"{SPEC_INDEX}: missing indexed link to {name}")

    markdown_files = [repo_root / relative for relative in ENTRYPOINT_FILES]
    markdown_files.extend(sorted(spec_root.glob("*.md")))
    markdown_files.extend(sorted((repo_root / ADR_ROOT).glob("*.md")))
    for source in markdown_files:
        if not source.is_file() or source.is_symlink():
            continue
        try:
            text = source.read_text(encoding="utf-8")
        except UnicodeError:
            continue
        for raw_target in LINK_PATTERN.findall(text):
            error = _validate_link(repo_root, source, raw_target)
            if error is not None:
                errors.append(error)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (defaults to the parent of scripts/)",
    )
    args = parser.parse_args()
    errors = validate_repository(args.repo_root)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(
        f"documentation validation passed: {len(ENTRYPOINT_FILES)} entrypoints, "
        f"{len(PACKAGE_FILES)} specification files"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
