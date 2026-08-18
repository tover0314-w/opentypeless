from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from rime_userdata_contract import (
    ACTIVITY,
    ADAPTER,
    BACKUP_RULES,
    ENGINE,
    ENGINE_TEST,
    ERROR,
    JNI,
    MANIFEST,
    RESTART_TEST,
    SEED_TEST,
    SERVICE,
    STORE,
    STORE_ROOT,
    STORE_TEST,
    inspect_android,
)


class RimeUserDataContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (
            STORE, ERROR, ENGINE, SERVICE, ACTIVITY, ADAPTER, JNI,
            STORE_TEST, ENGINE_TEST, SEED_TEST, RESTART_TEST, MANIFEST, BACKUP_RULES,
        ):
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

    def test_rejects_extra_userdata_source(self) -> None:
        extra = self.root / STORE_ROOT / "CloudUserDataSync.java"
        extra.write_text("final class CloudUserDataSync {}\n", encoding="utf-8")
        self.assertIn("RIM007_SOURCE_SET", self.rules())

    def test_rejects_backup_eligible_storage(self) -> None:
        self.mutate(STORE, "getNoBackupFilesDir()", "getFilesDir()")
        self.assertIn("RIM007_STORE_CONTRACT", self.rules())
        self.assertIn("RIM007_STORAGE_BOUNDARY", self.rules())

    def test_rejects_resource_and_userdata_mixing(self) -> None:
        self.mutate(
            STORE,
            'private static final String ROOT_NAME = "rime_user_data_v1";',
            'private static final String ROOT_NAME = "rime_resources";',
        )
        self.assertIn("RIM007_STORE_CONTRACT", self.rules())

    def test_rejects_checkpoint_that_copies_generated_cache(self) -> None:
        self.mutate(
            STORE,
            "if (!USERDB_NAME.matcher(child.getName()).matches()) continue;",
            "if (false) continue;",
        )
        self.assertIn("RIM007_STORE_CONTRACT", self.rules())

    def test_rejects_delivery_before_checkpoint(self) -> None:
        self.mutate(
            ENGINE,
            "committedUserData.checkpoint();",
            "committedUserData.directory();",
        )
        self.assertIn("RIM007_ENGINE_LIFECYCLE", self.rules())

    def test_rejects_unbounded_restore_loop(self) -> None:
        self.mutate(
            ENGINE,
            "if (!userDataLease.restoreLatestCheckpoint()) throw firstFailure;",
            "while (userDataLease.restoreLatestCheckpoint()) {}",
        )
        self.assertIn("RIM007_ENGINE_LIFECYCLE", self.rules())

    def test_rejects_legacy_product_constructor(self) -> None:
        self.mutate(
            SERVICE,
            "runtime.root(), config, rimeUserDataStore",
            "runtime.root(), config",
        )
        self.assertIn("RIM007_PRODUCT_WIRING", self.rules())

    def test_rejects_nonterminal_native_sync(self) -> None:
        self.mutate(
            ADAPTER,
            "} finally {\n                session = 0L;\n                closed = true;",
            "} finally {\n                session = session;\n                closed = true;",
        )
        self.assertIn("RIM007_NATIVE_SYNC", self.rules())

    def test_rejects_resource_clear_that_erases_learning(self) -> None:
        self.mutate(
            ACTIVITY,
            "runtimePreferences.clear();",
            "runtimePreferences.clear(); userDataStore.clear();",
        )
        self.assertIn("RIM007_RESOURCE_SEPARATION", self.rules())

    def test_rejects_backup_include(self) -> None:
        path = self.root / BACKUP_RULES
        source = path.read_text(encoding="utf-8")
        source = source.replace(
            '<exclude domain="file" path="." />',
            '<include domain="file" path="rime_user_data_v1" />',
            1,
        )
        path.write_text(source, encoding="utf-8")
        self.assertIn("RIM007_NO_BACKUP", self.rules())


if __name__ == "__main__":
    unittest.main()
