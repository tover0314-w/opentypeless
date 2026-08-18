#!/usr/bin/env python3
"""Validate the root coding-agent contract and its canonical entrypoints."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path


ROOT_AGENTS = Path("AGENTS.md")
SPEC_AGENTS = Path("docs/opentypeless_specs/AGENTS.md")


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def inspect_agents(repo_root: Path) -> tuple[Violation, ...]:
    root = repo_root.resolve()
    violations: list[Violation] = []
    root_path = root / ROOT_AGENTS
    spec_path = root / SPEC_AGENTS
    for path, rule in ((root_path, "DOC004_ROOT_FILE"), (spec_path, "DOC004_SPEC_FILE")):
        if not path.is_file() or path.is_symlink():
            violations.append(Violation(rule, str(path.relative_to(root))))
    if violations:
        return tuple(violations)
    try:
        root_text = root_path.read_text(encoding="utf-8")
        spec_text = spec_path.read_text(encoding="utf-8")
    except UnicodeError:
        return (Violation("DOC004_UTF8", "AGENTS.md"),)

    ordered_preflight = (
        "1. 读取本文件；",
        "2. 读取 `docs/opentypeless_specs/00_README.md`；",
        "3. 读取任务指定的设计文档；",
        "4. 读取 `docs/opentypeless_specs/07_IMPLEMENTATION_BACKLOG.md` 中对应任务；",
        "5. 从 `docs/adr/README.md` 读取关联 ADR；",
        "6. 检查当前 git status、分支和 HEAD；",
        "7. 检查最新 CI；",
        "8. 只实现一个任务 ID。",
    )
    positions = tuple(root_text.find(fragment) for fragment in ordered_preflight)
    if any(position < 0 for position in positions) or positions != tuple(sorted(positions)):
        violations.append(Violation("DOC004_PREFLIGHT_ORDER", str(positions)))
    if "2. 读取 `00_README.md`；" in root_text or "4. 读取 `07_IMPLEMENTATION_BACKLOG.md`" in root_text:
        violations.append(Violation("DOC004_CANONICAL_PATHS", "stale root-relative path"))

    entrypoints = (
        "docs/opentypeless_specs/00_README.md",
        "docs/opentypeless_specs/07_IMPLEMENTATION_BACKLOG.md",
        "docs/adr/README.md",
        "docs/adr/0000-template.md",
    )
    if any(fragment not in root_text for fragment in entrypoints):
        violations.append(Violation("DOC004_ENTRYPOINTS", "canonical documentation or ADR"))

    missing_safety = tuple(
        line
        for line in spec_text.splitlines()
        if line.startswith("- 不得") and line not in root_text
    )
    if missing_safety:
        violations.append(Violation("DOC004_SAFETY_PARITY", missing_safety[0]))

    required_commands = (
        "./gradlew testDebugUnitTest",
        "./gradlew lintRelease",
        "./gradlew assembleDebug",
        "./gradlew assembleRelease",
        "./gradlew assembleDebugAndroidTest",
        "./gradlew connectedDebugAndroidTest",
    )
    if any(command not in root_text for command in required_commands):
        violations.append(Violation("DOC004_TEST_COMMANDS", "Android baseline"))

    report_contract = (
        "# Task Report: <ID>",
        "DONE / PARTIAL / BLOCKED",
        "## Tests actually run",
        "| Command | Result | Notes |",
        "## Evidence",
        "## Risks",
        "## Rollback",
        "## Follow-ups",
        "## Git",
        "PASS",
        "FAIL",
        "NOT RUN — reason",
    )
    if any(fragment not in root_text for fragment in report_contract):
        violations.append(Violation("DOC004_REPORT_CONTRACT", "missing result or evidence field"))

    headings = tuple(f"## {number}." for number in range(1, 13))
    if any(heading not in root_text for heading in headings):
        violations.append(Violation("DOC004_SECTION_SURFACE", "sections 1 through 12"))
    if "此时输出 BLOCKED 报告和最小证据" not in root_text:
        violations.append(Violation("DOC004_BLOCKER_POLICY", "missing fail-closed stop rule"))
    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    violations = inspect_agents(args.repo_root)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}")
        return 1
    print("root AGENTS contract passed: 12 sections + canonical preflight/report policy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
