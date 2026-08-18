from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile
import unittest

import verify_release_source


class VerifyReleaseSourceTest(unittest.TestCase):
    def test_accepts_tag_on_protected_main_history(self) -> None:
        with self.repository() as root:
            self.assertRegex(
                verify_release_source.resolve_release_source(root, "v1.2.3"),
                r"^[0-9a-f]{40}$",
            )

    def test_rejects_tag_from_side_branch(self) -> None:
        with self.repository() as root:
            self.git(root, "switch", "--detach", "HEAD~1")
            (root / "side.txt").write_text("side\n", encoding="utf-8")
            self.git(root, "add", "side.txt")
            self.git(root, "commit", "-m", "side")
            self.git(root, "tag", "v2.0.0")
            with self.assertRaisesRegex(
                verify_release_source.ReleaseSourceError,
                "not on protected main history",
            ):
                verify_release_source.resolve_release_source(root, "v2.0.0")

    def test_rejects_missing_and_unsafe_tags(self) -> None:
        with self.repository() as root:
            for tag in ("missing", "../v1.0.0", "v1/evil"):
                with self.subTest(tag=tag), self.assertRaises(
                    verify_release_source.ReleaseSourceError
                ):
                    verify_release_source.resolve_release_source(root, tag)

    class repository:
        def __enter__(self) -> Path:
            self.temporary = tempfile.TemporaryDirectory()
            root = Path(self.temporary.name)
            VerifyReleaseSourceTest.git(root, "init", "-b", "main")
            VerifyReleaseSourceTest.git(root, "config", "user.email", "test@example.invalid")
            VerifyReleaseSourceTest.git(root, "config", "user.name", "Test")
            (root / "file.txt").write_text("one\n", encoding="utf-8")
            VerifyReleaseSourceTest.git(root, "add", "file.txt")
            VerifyReleaseSourceTest.git(root, "commit", "-m", "one")
            (root / "file.txt").write_text("two\n", encoding="utf-8")
            VerifyReleaseSourceTest.git(root, "commit", "-am", "two")
            VerifyReleaseSourceTest.git(root, "tag", "v1.2.3", "HEAD~1")
            main = VerifyReleaseSourceTest.git(root, "rev-parse", "HEAD").stdout.strip()
            VerifyReleaseSourceTest.git(root, "update-ref", "refs/remotes/origin/main", main)
            return root

        def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
            self.temporary.cleanup()

    @staticmethod
    def git(root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", *arguments],
            cwd=root,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )


if __name__ == "__main__":
    unittest.main()
