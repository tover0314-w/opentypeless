from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import verify_docs


class VerifyDocsTest(unittest.TestCase):
    def _fixture(self) -> Path:
        root = Path(self.temp_directory.name)
        spec_root = root / verify_docs.SPEC_ROOT
        spec_root.mkdir(parents=True)
        entrypoint = verify_docs.SPEC_INDEX.as_posix()
        for relative in verify_docs.ENTRYPOINT_FILES:
            (root / relative).write_text(f"[spec]({entrypoint})\n", encoding="utf-8")
        links = []
        for name in verify_docs.PACKAGE_FILES:
            path = spec_root / name
            path.write_text(f"# {name}\n", encoding="utf-8")
            if name != verify_docs.SPEC_INDEX.name:
                links.append(f"[{name}]({name})")
        (root / verify_docs.SPEC_INDEX).write_text("\n".join(links) + "\n", encoding="utf-8")
        return root

    def setUp(self) -> None:
        self.temp_directory = tempfile.TemporaryDirectory()

    def tearDown(self) -> None:
        self.temp_directory.cleanup()

    def test_accepts_complete_index_and_local_links(self) -> None:
        self.assertEqual([], verify_docs.validate_repository(self._fixture()))

    def test_rejects_broken_link_and_missing_root_entrypoint(self) -> None:
        root = self._fixture()
        (root / "README.md").write_text("[missing](docs/missing.md)\n", encoding="utf-8")

        errors = verify_docs.validate_repository(root)

        self.assertTrue(any("does not reference" in error for error in errors))
        self.assertTrue(any("missing local link target" in error for error in errors))

    def test_rejects_absolute_and_repository_escape_links(self) -> None:
        root = self._fixture()
        index = verify_docs.SPEC_INDEX.as_posix()
        (root / "README.md").write_text(
            f"[spec]({index})\n[absolute](/tmp/outside.md)\n[escape](../outside.md)\n",
            encoding="utf-8",
        )

        errors = verify_docs.validate_repository(root)

        self.assertTrue(any("absolute local link is forbidden" in error for error in errors))
        self.assertTrue(any("local link escapes repository" in error for error in errors))

    def test_rejects_symlink_and_invalid_utf8_specification_files(self) -> None:
        root = self._fixture()
        spec_root = root / verify_docs.SPEC_ROOT
        symlink = spec_root / "01_PRODUCT_DESIGN.md"
        symlink.unlink()
        outside = root / "outside.md"
        outside.write_text("outside\n", encoding="utf-8")
        symlink.symlink_to(outside)
        (spec_root / "02_ARCHITECTURE_DEVELOPMENT.md").write_bytes(b"\xff")

        errors = verify_docs.validate_repository(root)

        self.assertTrue(any("missing or non-regular specification file" in error for error in errors))
        self.assertTrue(any("invalid UTF-8" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
