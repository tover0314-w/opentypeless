from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

from rime_runtime_contract import (
    AAR,
    APP_NOTICE,
    ADAPTER,
    BUILD,
    GRADLE,
    JNI,
    JNI_CMAKE,
    MAIN,
    NOTICE,
    PACKAGE,
    PATCH,
    SERVICE,
    TEST,
    inspect_android,
)


class RimeRuntimeContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "android"
        self.root.mkdir()
        android = Path(__file__).resolve().parents[1]
        for relative in (
            AAR, ADAPTER, BUILD, PACKAGE, PATCH, NOTICE, APP_NOTICE, JNI_CMAKE, JNI,
            GRADLE, MAIN, SERVICE, TEST,
        ):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def test_current_runtime_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_runtime_byte_drift(self) -> None:
        path = self.root / AAR
        path.write_bytes(path.read_bytes() + b"drift")
        self.assertIn("RIM002_AAR_IDENTITY", self.rules())

    def test_rejects_source_recipe_drift(self) -> None:
        path = self.root / BUILD
        path.write_text(path.read_text(encoding="utf-8") + "\n# drift\n", encoding="utf-8")
        self.assertIn("RIM002_SOURCE_IDENTITY", self.rules())

    def test_rejects_runtime_editor_authority(self) -> None:
        path = self.root / ADAPTER
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection forbidden;\n",
            encoding="utf-8",
        )
        self.assertIn("RIM002_SOURCE_IDENTITY", self.rules())
        self.assertIn("RIM002_ADAPTER_CONTRACT", self.rules())

    def test_rejects_runtime_activation_in_service(self) -> None:
        path = self.root / SERVICE
        path.write_text(
            path.read_text(encoding="utf-8") + "\nRimeAdapter earlyRuntime;\n",
            encoding="utf-8",
        )
        self.assertIn("RIM002_NO_RUNTIME_ACTIVATION", self.rules())

    def test_rejects_aar_resource_payload(self) -> None:
        path = self.root / AAR
        rewritten = path.with_suffix(".new")
        with zipfile.ZipFile(path) as source, zipfile.ZipFile(rewritten, "w") as target:
            for item in source.infolist():
                target.writestr(item, source.read(item.filename))
            target.writestr("assets/rime/real.dict.yaml", "schema: real")
        rewritten.replace(path)
        rules = self.rules()
        self.assertIn("RIM002_AAR_IDENTITY", rules)
        self.assertIn("RIM002_AAR_CLOSED_SET", rules)

    def test_rejects_missing_notice_surface(self) -> None:
        path = self.root / MAIN
        source = path.read_text(encoding="utf-8")
        self.assertIn("readRawText(R.raw.native_engine_licenses)", source)
        path.write_text(
            source.replace("readRawText(R.raw.native_engine_licenses)", '"hidden"', 1),
            encoding="utf-8",
        )
        self.assertIn("RIM002_NOTICE_SURFACE", self.rules())


if __name__ == "__main__":
    unittest.main()
