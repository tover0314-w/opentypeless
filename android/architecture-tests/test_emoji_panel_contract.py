from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from emoji_panel_contract import (
    ADR,
    ANDROID_TEST_ROOT,
    BACKUP_RULES,
    EMOJI_ROOT,
    EXPECTED_FILES,
    HOST_TEST,
    SERVICE,
    UNIT_ROOT,
    inspect_android,
)


class EmojiPanelContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for name in EXPECTED_FILES:
            self.copy(android, EMOJI_ROOT / name)
        for relative in (
            SERVICE,
            HOST_TEST,
            BACKUP_RULES,
            UNIT_ROOT / "EmojiCatalogTest.java",
            UNIT_ROOT / "EmojiPrivacyPolicyTest.java",
            UNIT_ROOT / "EmojiRecentCodecTest.java",
            UNIT_ROOT / "EmojiRecentsTest.java",
            ANDROID_TEST_ROOT / "EmojiRecentStoreInstrumentedTest.java",
            ANDROID_TEST_ROOT / "KeyboardEmojiPanelInstrumentedTest.java",
        ):
            self.copy(android, relative)
        adr_source = (android / ADR).resolve()
        adr_target = (self.root / ADR).resolve()
        adr_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(adr_source, adr_target)

    def copy(self, android: Path, relative: Path) -> None:
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

    def test_rejects_unbounded_recent_list(self) -> None:
        self.mutate(
            EMOJI_ROOT / "EmojiRecents.java",
            "public static final int MAX_ENTRIES = 21;",
            "public static final int MAX_ENTRIES = 2100;",
        )
        self.assertIn("KBD010_RECENTS_BOUND", self.rules())

    def test_rejects_synchronous_preference_commit(self) -> None:
        self.mutate(
            EMOJI_ROOT / "EmojiRecentStore.java",
            ".apply();",
            ".commit();",
        )
        self.assertIn("KBD010_PRIVATE_STORE", self.rules())

    def test_rejects_editor_writer_in_panel(self) -> None:
        panel = EMOJI_ROOT / "KeyboardEmojiPanel.java"
        path = self.root / panel
        path.write_text(
            path.read_text(encoding="utf-8") + "\neditor.commitText(emoji, 1);\n",
            encoding="utf-8",
        )
        self.assertIn("KBD010_PANEL_CAPABILITY", self.rules())

    def test_rejects_sensitive_recent_read(self) -> None:
        self.mutate(
            SERVICE,
            "emojiPrivacy.recentsVisible()\n                ? emojiRecentStore.load()",
            "true\n                ? emojiRecentStore.load()",
        )
        self.assertIn("KBD010_SERVICE_WIRING", self.rules())

    def test_rejects_unaccepted_persistence_decision(self) -> None:
        adr = (self.root / ADR).resolve()
        source = adr.read_text(encoding="utf-8")
        adr.write_text(source.replace("Accepted", "Proposed", 1), encoding="utf-8")
        self.assertIn("KBD010_ACCEPTED_ADR", self.rules())


if __name__ == "__main__":
    unittest.main()
