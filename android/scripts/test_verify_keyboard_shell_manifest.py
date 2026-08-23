from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import verify_keyboard_shell_manifest as gate


class KeyboardShellManifestGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_accepts_current_source_manifest_and_rules(self) -> None:
        repo = Path(__file__).resolve().parents[1]
        self.assertEqual((), gate.inspect_manifest(
            repo / "app/src/main/AndroidManifest.xml", "source"))
        self.assertEqual((), gate.inspect_rules(
            repo / "app/src/main/res/xml/data_extraction_rules.xml"))

    def test_rejects_backup_permission_and_upstream_surface(self) -> None:
        manifest = self._copy_manifest()
        text = manifest.read_text(encoding="utf-8")
        text = text.replace('android:allowBackup="false"', 'android:allowBackup="true"')
        text = text.replace(
            '<uses-permission android:name="android.permission.INTERNET" />',
            '<uses-permission android:name="android.permission.INTERNET" />\n'
            '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
        )
        text = text.replace(
            "</application>",
            '<service android:name="com.florisboard.SpellCheckerService" '
            'android:exported="true" />\n</application>',
        )
        manifest.write_text(text, encoding="utf-8")
        rules = {item.rule for item in gate.inspect_manifest(manifest, "source")}
        self.assertTrue({
            "KBD001_ALLOW_BACKUP", "KBD001_PERMISSION_SET",
            "KBD001_COMPONENT_SET", "KBD001_UPSTREAM_SURFACE",
        }.issubset(rules))

    def test_rejects_profileable_import_and_share_surfaces(self) -> None:
        manifest = self._copy_manifest()
        text = manifest.read_text(encoding="utf-8").replace(
            "</application>",
            '<profileable android:shell="true" />\n'
            '<activity android:name=".ImportActivity" android:exported="true">\n'
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.BROWSABLE" /></intent-filter>\n'
            '</activity></application>',
        )
        manifest.write_text(text, encoding="utf-8")
        rules = {item.rule for item in gate.inspect_manifest(manifest, "source")}
        self.assertTrue({
            "KBD001_PROFILEABLE", "KBD001_COMPONENT_SET",
            "KBD001_IMPORT_SHARE_SURFACE",
        }.issubset(rules))

    def test_rejects_debuggable_release(self) -> None:
        manifest = self._copy_manifest()
        text = manifest.read_text(encoding="utf-8").replace(
            "<application", '<application android:debuggable="true"', 1)
        manifest.write_text(text, encoding="utf-8")
        self.assertIn(
            "KBD001_RELEASE_DEBUGGABLE",
            {item.rule for item in gate.inspect_manifest(manifest, "release")},
        )

    def test_rejects_missing_device_protected_backup_domain(self) -> None:
        rules = self._copy_rules()
        rules.write_text(
            rules.read_text(encoding="utf-8").replace(
                '<exclude domain="device_database" path="." />', "", 1),
            encoding="utf-8",
        )
        self.assertIn(
            "KBD001_RULES_DOMAINS",
            {item.rule for item in gate.inspect_rules(rules)},
        )

    def _copy_manifest(self) -> Path:
        repo = Path(__file__).resolve().parents[1]
        target = self.root / "AndroidManifest.xml"
        target.write_bytes((repo / "app/src/main/AndroidManifest.xml").read_bytes())
        return target

    def _copy_rules(self) -> Path:
        repo = Path(__file__).resolve().parents[1]
        target = self.root / "data_extraction_rules.xml"
        target.write_bytes(
            (repo / "app/src/main/res/xml/data_extraction_rules.xml").read_bytes())
        return target


if __name__ == "__main__":
    unittest.main()
