#!/usr/bin/env python3
"""Fail closed when main protection or release-source gates drift."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import subprocess
from typing import Any


POLICY_PATH = Path(".github/main-branch-protection.json")
VERIFY_SCRIPT = Path("scripts/verify_android.sh")
RELEASE_SOURCE_SCRIPT = Path("scripts/verify_release_source.py")
WORKFLOWS = (
    Path(".github/workflows/release.yml"),
    Path(".github/workflows/release-windows-signpath.yml"),
)
EXPECTED_CONTEXTS = (
    "check-android",
    "Android device tests (API 26)",
    "Android device tests (API 33)",
    "Android device tests (API 35)",
    "Android device tests (API 36)",
    "check-frontend",
    "check-offline-asr-tools",
    "check-rust (windows-latest, windows, x86_64-pc-windows-msvc, python)",
    "check-rust (macos-latest, macos, aarch64-apple-darwin, python3)",
    "check-rust (ubuntu-latest, linux, x86_64-unknown-linux-gnu, python3)",
    "check-rust (ubuntu-22.04-arm, linux, aarch64-unknown-linux-gnu, python3)",
    "audit",
    "analyze-javascript",
    "typos",
    "check",
)


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _enabled(value: Any) -> Any:
    return value.get("enabled") if isinstance(value, dict) else value


def _validate_policy(policy: dict[str, Any], prefix: str) -> list[Violation]:
    violations: list[Violation] = []
    status = policy.get("required_status_checks")
    if not isinstance(status, dict) or status.get("strict") is not True:
        violations.append(Violation(f"{prefix}_STRICT_CHECKS", "strict"))
    contexts = tuple(status.get("contexts", ())) if isinstance(status, dict) else ()
    if contexts != EXPECTED_CONTEXTS:
        violations.append(Violation(f"{prefix}_REQUIRED_CONTEXTS", str(contexts)))

    reviews = policy.get("required_pull_request_reviews")
    if not isinstance(reviews, dict):
        violations.append(Violation(f"{prefix}_PULL_REQUEST", "missing"))
    else:
        if reviews.get("dismiss_stale_reviews") is not True:
            violations.append(Violation(f"{prefix}_STALE_REVIEWS", "disabled"))
        if reviews.get("required_approving_review_count") != 0:
            violations.append(Violation(f"{prefix}_SOLO_REVIEW_COUNT", "must be zero"))
        if reviews.get("require_code_owner_reviews") is not False:
            violations.append(Violation(f"{prefix}_CODE_OWNER", "unexpected"))
        if reviews.get("require_last_push_approval") is not False:
            violations.append(Violation(f"{prefix}_LAST_PUSH", "unexpected"))

    boolean_contract = {
        "enforce_admins": True,
        "required_linear_history": True,
        "allow_force_pushes": False,
        "allow_deletions": False,
        "block_creations": False,
        "required_conversation_resolution": True,
        "lock_branch": False,
        "allow_fork_syncing": False,
    }
    for key, expected in boolean_contract.items():
        if _enabled(policy.get(key)) is not expected:
            violations.append(Violation(f"{prefix}_{key.upper()}", str(policy.get(key))))
    return violations


def inspect_branch_protection(repo_root: Path) -> tuple[Violation, ...]:
    root = repo_root.resolve()
    violations: list[Violation] = []
    policy_path = root / POLICY_PATH
    if not policy_path.is_file() or policy_path.is_symlink():
        return (Violation("BLD007_POLICY", str(POLICY_PATH)),)
    try:
        policy = json.loads(policy_path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        return (Violation("BLD007_POLICY", "invalid JSON"),)
    if not isinstance(policy, dict):
        return (Violation("BLD007_POLICY", "root must be an object"),)
    violations.extend(_validate_policy(policy, "BLD007_POLICY"))

    check_fragments = {
        Path(".github/workflows/ci.yml"): (
            "  check-android:",
            "name: Android device tests (API ${{ matrix.api_level }})",
            "api_level: [26, 33, 35, 36]",
            "  check-frontend:",
            "  check-offline-asr-tools:",
            "  check-rust:",
            "platform: windows-latest",
            "platform: macos-latest",
            "platform: ubuntu-latest",
            "platform: ubuntu-22.04-arm",
            "  audit:",
        ),
        Path(".github/workflows/codeql.yml"): ("  analyze-javascript:",),
        Path(".github/workflows/typos.yml"): ("  typos:",),
        Path(".github/workflows/pr-title.yml"): ("  check:",),
    }
    for relative, fragments in check_fragments.items():
        path = root / relative
        if not path.is_file() or path.is_symlink():
            violations.append(Violation("BLD007_CHECK_TOPOLOGY", str(relative)))
            continue
        text = path.read_text(encoding="utf-8")
        if any(fragment not in text for fragment in fragments):
            violations.append(Violation("BLD007_CHECK_TOPOLOGY", str(relative)))

    if not (root / RELEASE_SOURCE_SCRIPT).is_file():
        violations.append(Violation("BLD007_RELEASE_SOURCE_SCRIPT", str(RELEASE_SOURCE_SCRIPT)))
    for workflow in WORKFLOWS:
        path = root / workflow
        if not path.is_file() or path.is_symlink():
            violations.append(Violation("BLD007_RELEASE_WORKFLOW", str(workflow)))
            continue
        text = path.read_text(encoding="utf-8")
        fragments = (
            "  verify-release-source:",
            "fetch-depth: 0",
            "git fetch --no-tags origin +refs/heads/main:refs/remotes/origin/main",
            "Verify release tag is on protected main",
            'python3 scripts/verify_release_source.py --repo-root . --tag "$TAG_NAME"',
            "needs: verify-release-source",
        )
        if any(fragment not in text for fragment in fragments):
            violations.append(Violation("BLD007_RELEASE_WORKFLOW", str(workflow)))

    verify_path = root / VERIFY_SCRIPT
    if not verify_path.is_file() or verify_path.is_symlink():
        violations.append(Violation("BLD007_LOCAL_GATE", str(VERIFY_SCRIPT)))
    else:
        text = verify_path.read_text(encoding="utf-8")
        if (
            'python3 "$REPO_ROOT/scripts/verify_github_branch_protection.py"' not in text
            or '--repo-root "$REPO_ROOT"' not in text
        ):
            violations.append(Violation("BLD007_LOCAL_GATE", str(VERIFY_SCRIPT)))
    return tuple(violations)


def read_remote_protection(repository: str) -> dict[str, Any]:
    if repository != "dengxuezhao/opentypeless":
        raise ValueError("only the audited repository may be queried")
    command = [
        "gh",
        "api",
        "-H",
        "Accept: application/vnd.github+json",
        "-H",
        "X-GitHub-Api-Version: 2022-11-28",
        f"/repos/{repository}/branches/main/protection",
    ]
    result = subprocess.run(
        command,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    value = json.loads(result.stdout)
    if not isinstance(value, dict):
        raise ValueError("remote protection response must be an object")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--repository")
    args = parser.parse_args()
    violations = list(inspect_branch_protection(args.repo_root))
    if args.repository:
        try:
            remote = read_remote_protection(args.repository)
        except (OSError, ValueError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
            violations.append(Violation("BLD007_REMOTE_READ", type(error).__name__))
        else:
            violations.extend(_validate_policy(remote, "BLD007_REMOTE"))
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}")
        return 1
    suffix = " + live remote" if args.repository else ""
    print(f"GitHub main protection passed: 15 required checks{suffix}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
