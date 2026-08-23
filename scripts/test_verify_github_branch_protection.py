from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

import verify_github_branch_protection


class VerifyGithubBranchProtectionTest(unittest.TestCase):
    def test_accepts_exact_policy_checks_and_release_gates(self) -> None:
        self.assertEqual(set(), self.rules(self.valid_fixture()))

    def test_rejects_relaxed_policy(self) -> None:
        files = self.valid_fixture()
        cases = (
            ("strict", '"strict": true', '"strict": false', "BLD007_POLICY_STRICT_CHECKS"),
            ("context", '"check-android",', "", "BLD007_POLICY_REQUIRED_CONTEXTS"),
            ("admins", '"enforce_admins": true', '"enforce_admins": false', "BLD007_POLICY_ENFORCE_ADMINS"),
            ("force", '"allow_force_pushes": false', '"allow_force_pushes": true', "BLD007_POLICY_ALLOW_FORCE_PUSHES"),
            ("delete", '"allow_deletions": false', '"allow_deletions": true', "BLD007_POLICY_ALLOW_DELETIONS"),
        )
        for name, old, new, expected in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                path = ".github/main-branch-protection.json"
                mutated[path] = mutated[path].replace(old, new, 1)
                self.assertIn(expected, self.rules(mutated))

    def test_rejects_missing_check_release_or_local_gate(self) -> None:
        files = self.valid_fixture()
        cases = (
            ("check", ".github/workflows/ci.yml", "  check-android:", "  missing-android:", "BLD007_CHECK_TOPOLOGY"),
            ("release", ".github/workflows/release.yml", "fetch-depth: 0", "fetch-depth: 1", "BLD007_RELEASE_WORKFLOW"),
            ("windows", ".github/workflows/release-windows-signpath.yml", "needs: verify-release-source", "needs: build", "BLD007_RELEASE_WORKFLOW"),
            ("local", "scripts/verify_android.sh", "verify_github_branch_protection.py", "removed_branch_gate.py", "BLD007_LOCAL_GATE"),
        )
        for name, path, old, new, expected in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                mutated[path] = mutated[path].replace(old, new, 1)
                self.assertIn(expected, self.rules(mutated))

    @staticmethod
    def rules(files: dict[str, str]) -> set[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return {
                item.rule
                for item in verify_github_branch_protection.inspect_branch_protection(root)
            }

    @staticmethod
    def valid_fixture() -> dict[str, str]:
        root = Path(__file__).resolve().parents[1]
        policy = json.loads((root / ".github/main-branch-protection.json").read_text())
        ci = """jobs:
  check-android:
  test-android-emulator:
    name: Android device tests (API ${{ matrix.api_level }})
    matrix:
      api_level: [26, 33, 35, 36]
  check-frontend:
  check-offline-asr-tools:
  check-rust:
    matrix:
      platform: windows-latest
      platform: macos-latest
      platform: ubuntu-latest
      platform: ubuntu-22.04-arm
  audit:
"""
        release = """jobs:
  verify-release-source:
    steps:
      - with:
          fetch-depth: 0
      - run: git fetch --no-tags origin +refs/heads/main:refs/remotes/origin/main
      - name: Verify release tag is on protected main
        run: python3 scripts/verify_release_source.py --repo-root . --tag "$TAG_NAME"
  build:
    needs: verify-release-source
"""
        return {
            ".github/main-branch-protection.json": json.dumps(policy),
            ".github/workflows/ci.yml": ci,
            ".github/workflows/codeql.yml": "jobs:\n  analyze-javascript:\n",
            ".github/workflows/typos.yml": "jobs:\n  typos:\n",
            ".github/workflows/pr-title.yml": "jobs:\n  check:\n",
            ".github/workflows/release.yml": release,
            ".github/workflows/release-windows-signpath.yml": release,
            "scripts/verify_release_source.py": "pass\n",
            "scripts/verify_android.sh": (
                'python3 "$REPO_ROOT/scripts/verify_github_branch_protection.py" '
                '--repo-root "$REPO_ROOT"\n'
            ),
        }


if __name__ == "__main__":
    unittest.main()
