from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
import shutil
import tempfile
import unittest

import verify_compatibility


class VerifyCompatibilityTest(unittest.TestCase):
    SOURCE_ROOT = Path(__file__).resolve().parents[1]
    REQUIRED_FILES = frozenset(
        {
            verify_compatibility.MATRIX_PATH.as_posix(),
            verify_compatibility.CHANGELOG_PATH.as_posix(),
            *(path.as_posix() for path in verify_compatibility.README_PATHS),
            ".github/workflows/ci.yml",
            "android/app/build.gradle.kts",
            "package.json",
            "src-tauri/Cargo.toml",
            "src-tauri/tauri.conf.json",
            "src/lib/constants.ts",
            "src/lib/scenes/sceneImportExport.ts",
            "src-tauri/src/dictionary_io.rs",
            "src-tauri/src/storage/mod.rs",
            *(
                authority
                for expectation in verify_compatibility.EXPECTED_ROWS.values()
                for authority in expectation.authorities
            ),
            *(path for path, _ in verify_compatibility.EXPECTED_VERSION_CONSTANTS),
        }
    )

    @contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in self.REQUIRED_FILES:
                source = self.SOURCE_ROOT / relative
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            yield root

    def test_accepts_current_cross_platform_version_inventory(self) -> None:
        with self.fixture() as root:
            self.assertEqual([], verify_compatibility.validate_repository(root))

    def test_rejects_runtime_version_drift_and_missing_changelog_trace(self) -> None:
        cases = (
            (
                "android-version",
                "android/app/build.gradle.kts",
                'versionName = "0.3.0"',
                'versionName = "0.3.1"',
                "Android versionName 0.3.0",
            ),
            (
                "desktop-version",
                "package.json",
                '"version": "1.2.0"',
                '"version": "1.2.1"',
                "desktop application version authority drift",
            ),
            (
                "changelog",
                "CHANGELOG.md",
                verify_compatibility.BASELINE_CHANGE_ID,
                "REMOVED-CHANGE-ID",
                "missing compatibility history token",
            ),
        )
        for name, relative, old, new, expected in cases:
            with self.subTest(name=name), self.fixture() as root:
                path = root / relative
                text = path.read_text(encoding="utf-8")
                self.assertIn(old, text)
                path.write_text(text.replace(old, new, 1), encoding="utf-8")
                errors = verify_compatibility.validate_repository(root)
                self.assertTrue(any(expected in error for error in errors), errors)

    def test_rejects_untracked_authority_missing_row_and_placeholder_policy(self) -> None:
        with self.fixture() as root:
            path = root / "android/app/src/main/java/com/opentypeless/android/config/GlobalConfig.java"
            text = path.read_text(encoding="utf-8")
            path.write_text(
                text.replace(
                    "public static final int FORMAT_VERSION = 1;",
                    "public static final int FORMAT_VERSION = 1;\n"
                    "    private static final int EXPORT_VERSION = 2;",
                    1,
                ),
                encoding="utf-8",
            )
            errors = verify_compatibility.validate_repository(root)
            self.assertTrue(any("version authority inventory drift" in error for error in errors), errors)

        with self.fixture() as root:
            path = root / verify_compatibility.MATRIX_PATH
            lines = path.read_text(encoding="utf-8").splitlines()
            path.write_text(
                "\n".join(line for line in lines if not line.startswith("| `android-engine-trace`"))
                + "\n",
                encoding="utf-8",
            )
            errors = verify_compatibility.validate_repository(root)
            self.assertTrue(any("row set drift" in error for error in errors), errors)

        with self.fixture() as root:
            path = root / verify_compatibility.MATRIX_PATH
            text = path.read_text(encoding="utf-8")
            path.write_text(
                text.replace(
                    "| `android-override-value` | config | `1` | exact v1 array or DB row;",
                    "| `android-override-value` | config | `1` | TODO;",
                    1,
                ),
                encoding="utf-8",
            )
            errors = verify_compatibility.validate_repository(root)
            self.assertTrue(any("placeholder compatibility policy" in error for error in errors), errors)

    def test_rejects_implemented_spec_only_protocol_and_silent_schema_versioning(self) -> None:
        cases = (
            (
                "action-production",
                "src/lib/constants.ts",
                "\nexport const ACTION_PROTOCOL = 'opentypeless.action.v1'\n",
                "Action Protocol is no longer spec-only",
            ),
            (
                "desktop-config-version",
                "src-tauri/src/storage/mod.rs",
                "\n    pub format_version: u32,\n",
                "desktop config legacy-unversioned boundary drift",
            ),
            (
                "desktop-history-version",
                "src-tauri/src/storage/mod.rs",
                "\n// PRAGMA user_version = 1\n",
                "desktop history DB is no longer legacy-unversioned",
            ),
        )
        for name, relative, injection, expected in cases:
            with self.subTest(name=name), self.fixture() as root:
                path = root / relative
                text = path.read_text(encoding="utf-8")
                if name == "desktop-config-version":
                    text = text.replace("pub struct AppConfig {", "pub struct AppConfig {" + injection, 1)
                else:
                    text += injection
                path.write_text(text, encoding="utf-8")
                errors = verify_compatibility.validate_repository(root)
                self.assertTrue(any(expected in error for error in errors), errors)

    def test_rejects_streaming_protocol_literal_schema_and_change_id_drift(self) -> None:
        cases = (
            (
                "source-protocol",
                "android/app/src/main/java/com/opentypeless/android/net/streaming/StreamingRecognitionWireEvent.java",
                "opentypeless.streaming.v1",
                "opentypeless.streaming.v2",
                "streaming RecognitionEvent protocol v1",
            ),
            (
                "schema-protocol",
                "android/app/src/main/resources/schemas/opentypeless-streaming-recognition-event-v1.schema.json",
                "opentypeless.streaming.v1",
                "opentypeless.streaming.v2",
                "streaming RecognitionEvent JSON Schema authority drift",
            ),
            (
                "change-id",
                verify_compatibility.MATRIX_PATH.as_posix(),
                verify_compatibility.STR001_CHANGE_ID,
                "UNTRACKED-STREAMING-V1",
                "untracked change id",
            ),
        )
        for name, relative, old, new, expected in cases:
            with self.subTest(name=name), self.fixture() as root:
                path = root / relative
                text = path.read_text(encoding="utf-8")
                self.assertIn(old, text)
                path.write_text(text.replace(old, new, 1), encoding="utf-8")
                errors = verify_compatibility.validate_repository(root)
                self.assertTrue(any(expected in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
