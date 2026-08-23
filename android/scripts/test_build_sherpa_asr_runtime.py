from io import BytesIO
from pathlib import Path
import tarfile
import tempfile
import unittest

from build_sherpa_asr_runtime import safe_tar_extract


class SafeTarExtractTest(unittest.TestCase):
    def make_archive(self, directory: Path, link: str) -> Path:
        archive = directory / "source.tar.gz"
        with tarfile.open(archive, "w:gz") as target:
            root = tarfile.TarInfo("source")
            root.type = tarfile.DIRTYPE
            root.mode = 0o755
            target.addfile(root)
            content = b"pinned source\n"
            regular = tarfile.TarInfo("source/real.txt")
            regular.size = len(content)
            regular.mode = 0o644
            target.addfile(regular, BytesIO(content))
            symlink = tarfile.TarInfo("source/nested/link.txt")
            symlink.type = tarfile.SYMTYPE
            symlink.linkname = link
            symlink.mode = 0o777
            target.addfile(symlink)
        return archive

    def test_allows_relative_link_that_resolves_inside_archive_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            extracted = safe_tar_extract(
                self.make_archive(root, "../real.txt"), root / "output"
            )
            self.assertEqual("pinned source\n", (extracted / "nested/link.txt").read_text())

    def test_rejects_link_that_escapes_archive_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(RuntimeError, "escapes root"):
                safe_tar_extract(
                    self.make_archive(root, "../../../outside"), root / "output"
                )

    def test_rejects_unknown_absolute_link(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(RuntimeError, "absolute link"):
                safe_tar_extract(
                    self.make_archive(root, "/tmp/outside"), root / "output"
                )


if __name__ == "__main__":
    unittest.main()
