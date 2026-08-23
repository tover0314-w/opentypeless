#!/usr/bin/env python3
"""Verify that a release tag resolves to a commit on protected main history."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess


TAG_PATTERN = re.compile(r"v[0-9][0-9A-Za-z.-]{0,63}")


class ReleaseSourceError(RuntimeError):
    """A release source cannot be proven safe."""


def _git(repo_root: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *arguments],
        cwd=repo_root,
        check=check,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def resolve_release_source(repo_root: Path, tag: str) -> str:
    root = repo_root.resolve()
    if not root.is_dir() or root.is_symlink():
        raise ReleaseSourceError("repository root must be a regular directory")
    if TAG_PATTERN.fullmatch(tag) is None or ".." in tag:
        raise ReleaseSourceError("release tag has an unsafe or unsupported shape")

    tag_ref = f"refs/tags/{tag}^{{commit}}"
    main_ref = "refs/remotes/origin/main^{commit}"
    try:
        tag_commit = _git(root, "rev-parse", "--verify", tag_ref).stdout.strip()
        main_commit = _git(root, "rev-parse", "--verify", main_ref).stdout.strip()
    except subprocess.CalledProcessError as error:
        raise ReleaseSourceError("release tag or protected main ref is unavailable") from error

    ancestry = _git(
        root,
        "merge-base",
        "--is-ancestor",
        tag_commit,
        main_commit,
        check=False,
    )
    if ancestry.returncode == 1:
        raise ReleaseSourceError("release tag commit is not on protected main history")
    if ancestry.returncode != 0:
        raise ReleaseSourceError("git could not prove release tag ancestry")
    if re.fullmatch(r"[0-9a-f]{40}", tag_commit) is None:
        raise ReleaseSourceError("release tag did not resolve to a full commit SHA")
    return tag_commit


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    try:
        commit = resolve_release_source(args.repo_root, args.tag)
    except ReleaseSourceError as error:
        print(f"release source verification failed: {error}")
        return 1
    print(f"release source verified on protected main: {args.tag} -> {commit}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
