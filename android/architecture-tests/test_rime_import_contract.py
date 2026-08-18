from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from rime_import_contract import (
    ACTIVITY,
    ADAPTER,
    DEVICE_TEST,
    EXPECTED_IMPORT_SOURCES,
    EXPECTED_UNIT_SOURCES,
    IMPORT_ROOT,
    MANIFEST,
    SETTINGS,
    UNIT_ROOT,
    inspect_android,
)


class RimeImportContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "android"
        self.root.mkdir()
        android = Path(__file__).resolve().parents[1]
        paths = [ACTIVITY, ADAPTER, DEVICE_TEST, MANIFEST, SETTINGS]
        paths.extend(IMPORT_ROOT / name for name in EXPECTED_IMPORT_SOURCES)
        paths.extend(UNIT_ROOT / name for name in EXPECTED_UNIT_SOURCES)
        for relative in paths:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def mutate(self, relative: Path, old: str, new: str) -> None:
        path = self.root / relative
        source = path.read_text(encoding="utf-8")
        self.assertIn(old, source)
        path.write_text(source.replace(old, new, 1), encoding="utf-8")

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_non_saf_or_network_import(self) -> None:
        self.mutate(ACTIVITY, "Intent.ACTION_OPEN_DOCUMENT", "Intent.ACTION_VIEW")
        self.assertIn("RIM003_EXPLICIT_UI", self.rules())

    def test_rejects_backup_eligible_storage(self) -> None:
        path = IMPORT_ROOT / "RimeResourceStore.java"
        self.mutate(path, "getNoBackupFilesDir()", "getFilesDir()")
        self.assertIn("RIM003_AUTHORITY_BOUNDARY", self.rules())
        self.assertIn("RIM003_ATOMIC_STORE", self.rules())

    def test_rejects_trust_elevation(self) -> None:
        path = IMPORT_ROOT / "RimeResourceManifest.java"
        self.mutate(path, '"USER_PROVIDED_UNVERIFIED"', '"TRUSTED"')
        self.assertIn("RIM003_MANIFEST_CONTRACT", self.rules())

    def test_rejects_removed_compression_or_symlink_gate(self) -> None:
        path = IMPORT_ROOT / "RimeResourceArchive.java"
        self.mutate(path, "MAXIMUM_COMPRESSION_RATIO = 200L", "MAXIMUM_COMPRESSION_RATIO = 0L")
        self.assertIn("RIM003_ARCHIVE_GATE", self.rules())

    def test_rejects_non_atomic_activation(self) -> None:
        path = IMPORT_ROOT / "RimeResourceStore.java"
        self.mutate(path, "staged.root.renameTo(current)", "staged.root.exists()")
        self.assertIn("RIM003_ATOMIC_STORE", self.rules())

    def test_rejects_exported_import_activity(self) -> None:
        self.mutate(
            MANIFEST,
            'android:name=".RimeResourceActivity"\n            android:exported="false"',
            'android:name=".RimeResourceActivity"\n            android:exported="true"',
        )
        self.assertIn("RIM003_PRIVATE_COMPONENT", self.rules())


if __name__ == "__main__":
    unittest.main()
