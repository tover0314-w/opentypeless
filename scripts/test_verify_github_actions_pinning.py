from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import verify_github_actions_pinning


class VerifyGithubActionsPinningTest(unittest.TestCase):
    def test_accepts_exact_audited_actions_and_permissions(self) -> None:
        self.assertEqual(set(), self.rules(self.valid_fixture()))

    def test_rejects_mutable_unknown_and_provenance_drift(self) -> None:
        files = self.valid_fixture()
        cases = (
            (
                "mutable tag",
                "3d3c42e5aac5ba805825da76410c181273ba90b1",
                "v7",
                "BLD003_ACTION_PIN",
            ),
            (
                "unknown action",
                "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1",
                "unknown/example@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa # v1",
                "BLD003_UNAUDITED_ACTION",
            ),
            ("version note", "# v7.0.1", "# latest", "BLD003_VERSION_PROVENANCE"),
        )
        for name, old, new, rule in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                mutated[".github/workflows/ci.yml"] = mutated[
                    ".github/workflows/ci.yml"
                ].replace(old, new, 1)
                self.assertIn(rule, self.rules(mutated))

    def test_rejects_checkout_credentials_target_checkout_and_dangerous_writes(self) -> None:
        files = self.valid_fixture()
        cases = (
            (
                "checkout credential",
                "          persist-credentials: false\n",
                "",
                "BLD003_CHECKOUT_CREDENTIALS",
            ),
            (
                "target checkout",
                "  pull_request:\n",
                "  pull_request_target:\n",
                "BLD003_TARGET_CHECKOUT",
            ),
            (
                "root write",
                "  contents: read\n",
                "  contents: write\n",
                "BLD003_ROOT_WRITE_PERMISSION",
            ),
            (
                "id token",
                "  contents: read\n",
                "  contents: read\n  id-token: write\n",
                "BLD003_DANGEROUS_PERMISSION",
            ),
            (
                "local gate",
                "verify_github_actions_pinning.py",
                "removed_actions_gate.py",
                "BLD003_LOCAL_GATE",
            ),
        )
        for name, old, new, rule in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                path = (
                    "scripts/verify_android.sh"
                    if name == "local gate"
                    else ".github/workflows/ci.yml"
                )
                mutated[path] = mutated[path].replace(old, new, 1)
                self.assertIn(rule, self.rules(mutated))

    @staticmethod
    def rules(files: dict[str, str]) -> set[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return {
                violation.rule
                for violation in verify_github_actions_pinning.inspect_action_pinning(root)
            }

    @staticmethod
    def valid_fixture() -> dict[str, str]:
        workflow = """name: CI
on:
  pull_request:
permissions:
  contents: read
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false
"""
        return {
            ".github/workflows/ci.yml": workflow,
            "scripts/verify_android.sh": (
                'python3 "$REPO_ROOT/scripts/verify_github_actions_pinning.py" \\\n'
                '  --repo-root "$REPO_ROOT"\n'
            ),
        }


if __name__ == "__main__":
    unittest.main()
