#!/usr/bin/env python3
"""Fail-closed structural validation for repository architecture decision records."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ADR_ROOT = Path("docs/adr")
INDEX_NAME = "README.md"
TEMPLATE_NAME = "0000-template.md"
VALID_STATUSES = ("Proposed", "Accepted", "Rejected", "Deprecated", "Superseded")
REQUIRED_SECTIONS = ("Status", "Background", "Decision", "Consequences", "Validation")
ADR_FILE_PATTERN = re.compile(r"^(\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
ADR_TITLE_PATTERN = re.compile(r"^# ADR-(\d{4}):\s+\S.*$")
SECTION_PATTERN = re.compile(r"^## ([A-Za-z]+)\s*$")


def _sections(text: str) -> dict[str, str]:
    sections: dict[str, list[str]] = {}
    current: str | None = None
    fenced = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
        if not fenced:
            match = SECTION_PATTERN.match(line)
            if match is not None:
                current = match.group(1)
                sections.setdefault(current, [])
                continue
        if current is not None:
            sections[current].append(line)
    return {name: "\n".join(lines).strip() for name, lines in sections.items()}


def _read_regular_utf8(path: Path, root: Path, errors: list[str]) -> str | None:
    if not path.is_file() or path.is_symlink():
        errors.append(f"missing or non-regular ADR file: {path.relative_to(root)}")
        return None
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError as error:
        errors.append(f"{path.relative_to(root)}: invalid UTF-8: {error}")
        return None


def validate_repository(repo_root: Path) -> list[str]:
    repo_root = repo_root.resolve()
    adr_root = repo_root / ADR_ROOT
    errors: list[str] = []
    index_path = adr_root / INDEX_NAME
    template_path = adr_root / TEMPLATE_NAME
    index = _read_regular_utf8(index_path, repo_root, errors)
    template = _read_regular_utf8(template_path, repo_root, errors)

    if index is not None:
        for token in (*VALID_STATUSES, TEMPLATE_NAME):
            if token not in index:
                errors.append(f"{ADR_ROOT / INDEX_NAME}: missing lifecycle/index token: {token}")

    if template is not None:
        title = next(iter(template.splitlines()), "")
        if title != "# ADR-NNNN: Short decision title":
            errors.append(f"{ADR_ROOT / TEMPLATE_NAME}: template title shape drift")
        sections = _sections(template)
        for name in REQUIRED_SECTIONS:
            if not sections.get(name):
                errors.append(f"{ADR_ROOT / TEMPLATE_NAME}: missing or empty section: {name}")
        if sections.get("Status", "").splitlines()[:1] != ["Proposed"]:
            errors.append(f"{ADR_ROOT / TEMPLATE_NAME}: template status must start with Proposed")

    root_requirements = {
        Path("AGENTS.md"): ("docs/adr/README.md", "docs/adr/0000-template.md"),
        Path("README.md"): ("docs/adr/README.md",),
        Path("README_zh.md"): ("docs/adr/README.md",),
    }
    for relative, tokens in root_requirements.items():
        path = repo_root / relative
        text = _read_regular_utf8(path, repo_root, errors)
        if text is None:
            continue
        for token in tokens:
            if token not in text:
                errors.append(f"{relative}: missing ADR discovery token: {token}")

    seen_ids: set[str] = set()
    if adr_root.is_dir():
        for path in sorted(adr_root.glob("*.md")):
            if path.name in (INDEX_NAME, TEMPLATE_NAME):
                continue
            match = ADR_FILE_PATTERN.fullmatch(path.name)
            if match is None or match.group(1) == "0000":
                errors.append(f"{path.relative_to(repo_root)}: invalid ADR filename")
                continue
            text = _read_regular_utf8(path, repo_root, errors)
            if text is None:
                continue
            identifier = match.group(1)
            title = next(iter(text.splitlines()), "")
            title_match = ADR_TITLE_PATTERN.fullmatch(title)
            if title_match is None or title_match.group(1) != identifier:
                errors.append(f"{path.relative_to(repo_root)}: ADR title/id does not match filename")
            if identifier in seen_ids:
                errors.append(f"{path.relative_to(repo_root)}: duplicate ADR id {identifier}")
            seen_ids.add(identifier)
            sections = _sections(text)
            for name in REQUIRED_SECTIONS:
                if not sections.get(name):
                    errors.append(f"{path.relative_to(repo_root)}: missing or empty section: {name}")
            status_lines = sections.get("Status", "").splitlines()
            status = status_lines[0].strip() if status_lines else ""
            if status not in VALID_STATUSES:
                errors.append(f"{path.relative_to(repo_root)}: invalid ADR status: {status or '<empty>'}")
            validation = sections.get("Validation", "")
            if status == "Accepted" and re.search(r"\b(?:TODO|TBD)\b|以后验证|应该通过", validation, re.I):
                errors.append(f"{path.relative_to(repo_root)}: Accepted ADR has placeholder validation")
            if index is not None and f"]({path.name})" not in index:
                errors.append(f"{ADR_ROOT / INDEX_NAME}: missing indexed link to {path.name}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = validate_repository(args.repo_root)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    count = len(
        [
            path
            for path in (args.repo_root / ADR_ROOT).glob("*.md")
            if path.name not in (INDEX_NAME, TEMPLATE_NAME)
        ]
    )
    print(f"ADR validation passed: template + index, {count} standalone decision(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
