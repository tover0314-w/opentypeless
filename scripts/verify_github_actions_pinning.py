#!/usr/bin/env python3
"""Fail closed when workflow actions or token permissions drift."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re


WORKFLOW_ROOT = Path(".github/workflows")
VERIFY_SCRIPT = Path("scripts/verify_android.sh")

ACTION_PINS = {
    "actions/checkout": ("3d3c42e5aac5ba805825da76410c181273ba90b1", "v7.0.1"),
    "actions/first-interaction": ("1c4688942c71f71d4f5502a26ea67c331730fa4d", "v3.1.0"),
    "actions/labeler": ("bf12e9b00b37c5c0ca2b87b79b2daf7891dbda13", "v7.0.0"),
    "actions/setup-java": ("b6effb05e454b25005698d916606bdc6ffcbf961", "v5.7.0"),
    "actions/setup-node": ("820762786026740c76f36085b0efc47a31fe5020", "v7.0.0"),
    "actions/stale": ("4391f3da665fdf50b6810c1a66712fb9ba21aa93", "v11.0.0"),
    "actions/upload-artifact": ("043fb46d1a93c77aae656e7c1c64a875d1fc6a0a", "v7.0.1"),
    "amannn/action-semantic-pull-request": (
        "48f256284bd46cdaab1048c3721360e808335d50",
        "v6.1.1",
    ),
    "android-actions/setup-android": (
        "40fd30fb8d7440372e1316f5d1809ec01dcd3699",
        "v4.0.1",
    ),
    "codelytv/pr-size-labeler": (
        "095a41fca88b8764fd9e008ad269bcdb82bb38b9",
        "v1.10.4",
    ),
    "crate-ci/typos": ("8a48f81b6c64dcfea44b3633223084c4be58ac5f", "v1.49.0"),
    "dessant/lock-threads": (
        "89ae32b08ed1a541efecbab17912962a5e38981c",
        "v6.0.2",
    ),
    "dtolnay/rust-toolchain": (
        "4360b52568e2003a75bf9bc1d59f33a8e3fc893c",
        "stable 2026-08-05",
    ),
    "github/codeql-action/init": (
        "ff2f1c621b7f889edc0d3c761ac2e6a3f8cdb0dd",
        "v4.37.7",
    ),
    "github/codeql-action/analyze": (
        "ff2f1c621b7f889edc0d3c761ac2e6a3f8cdb0dd",
        "v4.37.7",
    ),
    "gradle/actions/setup-gradle": (
        "9c971963bec38e04b3d30dcc455b5382be2fdbfb",
        "v6.3.0",
    ),
    "reactivecircus/android-emulator-runner": (
        "a421e43855164a8197daf9d8d40fe71c6996bb0d",
        "v2.38.0",
    ),
    "release-drafter/release-drafter": (
        "34d80673e067bdc0c24568d3af899c216adcfaa9",
        "v7.7.0",
    ),
    "signpath/github-action-submit-signing-request": (
        "b9d91eadd323de506c0c81cf0c7fe7438f3360fd",
        "v2.2",
    ),
    "swatinem/rust-cache": (
        "6323deb102c322ba6fcbdcafc7e3dddab59af2b6",
        "v2.9.2",
    ),
    "tauri-apps/tauri-action": (
        "1deb371b0cd8bd54025b384f1cd735e725c4060f",
        "action-v1.0.0",
    ),
}

ALLOWED_ROOT_WRITES = {
    "codeql.yml": frozenset({"security-events"}),
    "labeler.yml": frozenset({"pull-requests"}),
    "lock-threads.yml": frozenset({"issues", "pull-requests"}),
    "release-drafter.yml": frozenset({"pull-requests"}),
    "stale.yml": frozenset({"issues", "pull-requests"}),
    "welcome.yml": frozenset({"issues", "pull-requests"}),
}


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _root_permissions(text: str) -> dict[str, str] | None:
    match = re.search(r"(?m)^permissions:\s*\n(?P<body>(?:^  [a-z-]+:\s*[^\n]+\n)+)", text)
    if match is None:
        return None
    return {
        key: value
        for key, value in re.findall(
            r"(?m)^  ([a-z-]+):\s*([^\s#]+)", match.group("body")
        )
    }


def inspect_action_pinning(repo_root: Path) -> tuple[Violation, ...]:
    root = repo_root.resolve()
    workflow_root = root / WORKFLOW_ROOT
    violations: list[Violation] = []
    if not workflow_root.is_dir() or workflow_root.is_symlink():
        return (Violation("BLD003_WORKFLOW_ROOT", str(WORKFLOW_ROOT)),)

    workflows = sorted(
        path
        for path in workflow_root.iterdir()
        if path.suffix in {".yml", ".yaml"}
    )
    if not workflows:
        return (Violation("BLD003_WORKFLOW_ROOT", "no workflows"),)

    for path in workflows:
        relative = path.relative_to(root)
        if not path.is_file() or path.is_symlink():
            violations.append(Violation("BLD003_REGULAR_FILE", str(relative)))
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeError:
            violations.append(Violation("BLD003_UTF8", str(relative)))
            continue

        permissions = _root_permissions(text)
        if permissions is None:
            violations.append(Violation("BLD003_EXPLICIT_PERMISSIONS", str(relative)))
        else:
            allowed_writes = ALLOWED_ROOT_WRITES.get(path.name, frozenset())
            actual_writes = {key for key, value in permissions.items() if value == "write"}
            if actual_writes - allowed_writes:
                violations.append(
                    Violation(
                        "BLD003_ROOT_WRITE_PERMISSION",
                        f"{relative}: {sorted(actual_writes - allowed_writes)}",
                    )
                )

        if re.search(r"(?m)^\s*permissions:\s*(?:write-all|read-all)\s*$", text):
            violations.append(Violation("BLD003_BROAD_PERMISSIONS", str(relative)))
        if re.search(r"(?m)^\s+(?:id-token|actions):\s*write\s*$", text):
            violations.append(Violation("BLD003_DANGEROUS_PERMISSION", str(relative)))

        remote_uses: list[tuple[int, str, str, str]] = []
        lines = text.splitlines()
        for index, line in enumerate(lines):
            match = re.search(r"\buses:\s*([^\s#]+)(?:\s+#\s*(.+))?$", line)
            if match is None:
                continue
            reference = match.group(1)
            if reference.startswith("./") or reference.startswith("docker://"):
                continue
            if "@" not in reference:
                violations.append(
                    Violation("BLD003_ACTION_REFERENCE", f"{relative}:{index + 1}")
                )
                continue
            action, revision = reference.rsplit("@", 1)
            version_note = (match.group(2) or "").strip()
            remote_uses.append((index, action, revision, version_note))
            expected = ACTION_PINS.get(action)
            if expected is None:
                violations.append(
                    Violation("BLD003_UNAUDITED_ACTION", f"{relative}:{index + 1}: {action}")
                )
                continue
            expected_revision, expected_note = expected
            if revision != expected_revision:
                violations.append(
                    Violation("BLD003_ACTION_PIN", f"{relative}:{index + 1}: {action}")
                )
            if version_note != expected_note:
                violations.append(
                    Violation(
                        "BLD003_VERSION_PROVENANCE", f"{relative}:{index + 1}: {action}"
                    )
                )

            if action == "actions/checkout":
                following = "\n".join(lines[index + 1 : index + 7])
                if "persist-credentials: false" not in following:
                    violations.append(
                        Violation(
                            "BLD003_CHECKOUT_CREDENTIALS", f"{relative}:{index + 1}"
                        )
                    )

        if "pull_request_target:" in text and any(
            action == "actions/checkout" for _, action, _, _ in remote_uses
        ):
            violations.append(Violation("BLD003_TARGET_CHECKOUT", str(relative)))

        if path.name == "codeql.yml" and permissions is not None:
            if permissions.get("contents") != "read" or permissions.get(
                "security-events"
            ) != "write":
                violations.append(Violation("BLD003_CODEQL_PERMISSIONS", str(relative)))

    verify_script = root / VERIFY_SCRIPT
    if not verify_script.is_file() or verify_script.is_symlink():
        violations.append(Violation("BLD003_LOCAL_GATE", str(VERIFY_SCRIPT)))
    else:
        script = verify_script.read_text(encoding="utf-8")
        if (
            'python3 "$REPO_ROOT/scripts/verify_github_actions_pinning.py"' not in script
            or '--repo-root "$REPO_ROOT"' not in script
        ):
            violations.append(Violation("BLD003_LOCAL_GATE", str(VERIFY_SCRIPT)))

    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    violations = inspect_action_pinning(args.repo_root)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}")
        return 1
    print(f"GitHub Actions pinning passed: {len(ACTION_PINS)} audited action surfaces")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
