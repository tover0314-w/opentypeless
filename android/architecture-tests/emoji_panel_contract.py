#!/usr/bin/env python3
"""KBD-010 fail-closed categorized Emoji panel and private MRU boundary."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


EMOJI_ROOT = Path("app/src/main/java/com/opentypeless/android/keyboard/emoji")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
UNIT_ROOT = Path("app/src/test/java/com/opentypeless/android/keyboard/emoji")
ANDROID_TEST_ROOT = Path("app/src/androidTest/java/com/opentypeless/android/keyboard/emoji")
HOST_TEST = Path("test-host/src/androidTest/java/com/opentypeless/testhost/TestHostInstrumentedTest.java")
ADR = Path("../docs/adr/0013-emoji-recents-private-format.md")
BACKUP_RULES = Path("app/src/main/res/xml/data_extraction_rules.xml")
EXPECTED_FILES = {
    "EmojiCatalog.java",
    "EmojiPrivacyPolicy.java",
    "EmojiRecentCodec.java",
    "EmojiRecents.java",
    "EmojiRecentStore.java",
    "KeyboardEmojiPanel.java",
}
WRITER = re.compile(
    r"\.\s*(?:commitText|setComposingText|finishComposingText|"
    r"deleteSurroundingText(?:InCodePoints)?|sendKeyEvent|setSelection)\s*\("
)


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _read(path: Path, rule: str, violations: list[Violation]) -> str:
    if not path.is_file() or path.is_symlink():
        violations.append(Violation(rule, str(path)))
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError:
        violations.append(Violation(rule, f"invalid UTF-8: {path}"))
        return ""


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    emoji_root = root / EMOJI_ROOT
    observed = {
        path.name for path in emoji_root.iterdir()
        if path.is_file() and not path.is_symlink()
    } if emoji_root.is_dir() and not emoji_root.is_symlink() else set()
    if observed != EXPECTED_FILES:
        violations.append(Violation(
            "KBD010_SOURCE_SET",
            f"expected {sorted(EXPECTED_FILES)}, got {sorted(observed)}",
        ))

    sources = {
        name: _read(emoji_root / name, "KBD010_SOURCE", violations)
        for name in EXPECTED_FILES
    }
    catalog = sources.get("EmojiCatalog.java", "")
    privacy = sources.get("EmojiPrivacyPolicy.java", "")
    codec = sources.get("EmojiRecentCodec.java", "")
    recents = sources.get("EmojiRecents.java", "")
    store = sources.get("EmojiRecentStore.java", "")
    panel = sources.get("KeyboardEmojiPanel.java", "")
    service = _read(root / SERVICE, "KBD010_SERVICE", violations)
    host_test = _read(root / HOST_TEST, "KBD010_SYSTEM_TEST", violations)
    adr = _read((root / ADR).resolve(), "KBD010_ADR", violations)
    backup = _read(root / BACKUP_RULES, "KBD010_BACKUP", violations)
    unit_tests = "\n".join(
        _read(root / UNIT_ROOT / name, "KBD010_UNIT_TEST", violations)
        for name in (
            "EmojiCatalogTest.java",
            "EmojiPrivacyPolicyTest.java",
            "EmojiRecentCodecTest.java",
            "EmojiRecentsTest.java",
        )
    )
    android_tests = "\n".join(
        _read(root / ANDROID_TEST_ROOT / name, "KBD010_ANDROID_TEST", violations)
        for name in (
            "EmojiRecentStoreInstrumentedTest.java",
            "KeyboardEmojiPanelInstrumentedTest.java",
        )
    )

    pure_forbidden = (
        "import android.", "InputConnection", "Context", "SharedPreferences",
        "java.net.", "java.io.", "Bundle", "Intent", "Log.",
    )
    for name, source in {
        "catalog": catalog,
        "privacy": privacy,
        "codec": codec,
        "recents": recents,
    }.items():
        if any(token in source for token in pure_forbidden) or WRITER.search(source):
            violations.append(Violation(
                "KBD010_DOMAIN_CAPABILITY",
                f"{name} must remain pure and editor-free",
            ))

    catalog_compact = _compact(catalog)
    if (
        "privatestaticfinalList<Category>BROWSE_CATEGORIES=List.of(" not in catalog_compact
        or "publicstaticbooleancontains(Stringemoji)" not in catalog_compact
        or catalog.count("private static final List<String>") != 8
    ):
        violations.append(Violation(
            "KBD010_PINNED_CATALOG",
            "catalog must keep eight fixed local categories and membership validation",
        ))

    recents_compact = _compact(recents)
    if any(token not in recents_compact for token in (
        "publicstaticfinalintMAX_ENTRIES=21;",
        "if(!EmojiCatalog.contains(emoji))",
        'return"EmojiRecents{count="+entries.size()',
    )):
        violations.append(Violation(
            "KBD010_RECENTS_BOUND",
            "MRU must be catalog-only, 21-entry bounded and body-redacted",
        ))

    codec_compact = _compact(codec)
    if any(token not in codec_compact for token in (
        "publicstaticfinalintFORMAT_VERSION=1",
        "publicstaticfinalintMAX_PAYLOAD_UTF16_UNITS=4_096",
        "if(version!=FORMAT_VERSION||payload==null||payload.isEmpty())",
        "returnEmojiRecents.fromStored(decoded)",
    )):
        violations.append(Violation(
            "KBD010_VERSIONED_CODEC",
            "recent payload must be versioned, bounded and catalog-filtered",
        ))

    store_forbidden = (
        "InputConnection", "EditorInfo", "java.net.", "java.io.", "Log.",
        "getDefaultSharedPreferences", "commit()",
    )
    store_compact = _compact(store)
    if (
        any(token in store for token in store_forbidden)
        or any(token not in store_compact for token in (
            'STORE="opentypeless_emoji_recents_v1"',
            'VERSION="format_version"',
            'PAYLOAD="recent_codepoints"',
            "preferences.getInt(VERSION,-1)",
            ".putInt(VERSION,EmojiRecentCodec.FORMAT_VERSION)",
            ".putString(PAYLOAD,payload)",
            ".apply()",
        ))
    ):
        violations.append(Violation(
            "KBD010_PRIVATE_STORE",
            "store must use one private v1 asynchronous bounded payload",
        ))

    privacy_compact = _compact(privacy)
    if any(token not in privacy_compact for token in (
        "booleanrecentsAllowed=editorActive&&!safe.denies(PrivacyPolicyEngine.Capability.TEACH)",
        "returnnewState(editorActive,recentsAllowed,recentsAllowed)",
    )):
        violations.append(Violation(
            "KBD010_SENSITIVE_POLICY",
            "static Emoji must remain available while hard no-learning suppresses MRU",
        ))

    panel_forbidden = (
        "InputConnection", "EditorInfo", "SharedPreferences", "EmojiRecentStore",
        "com.opentypeless.android.editor", "java.net.", "java.io.", "Log.",
    )
    panel_compact = _compact(panel)
    if (
        any(token in panel for token in panel_forbidden)
        or WRITER.search(panel)
        or any(token not in panel_compact for token in (
            "publicstaticfinalintMINIMUM_TOUCH_TARGET_DP=48",
            "voidonEmojiSelected(Stringemoji)",
            "recent.setVisibility(allowRecents?View.VISIBLE:View.GONE)",
            "grid.removeAllViews()",
        ))
    ):
        violations.append(Violation(
            "KBD010_PANEL_CAPABILITY",
            "panel must remain a 48dp capability-free bounded renderer",
        ))

    service_compact = _compact(service)
    service_tokens = (
        "caseMENU_EMOJI->showEmojiPanel()",
        "emojiPrivacy=EmojiPrivacyPolicy.resolve(currentEditor!=null,hardSafety)",
        "visibleEmojiRecents=emojiPrivacy.recentsVisible()?emojiRecentStore.load():EmojiRecents.empty()",
        "lastKeyboardInsertApplied=false",
        "insertKeyboardText(emoji);if(!lastKeyboardInsertApplied||!emojiPrivacy.recentsWritable())return",
        "emojiRecentStore.save(visibleEmojiRecents)",
        "if(lease!=null&&!lease.isIdle())",
    )
    if any(token not in service_compact for token in service_tokens):
        violations.append(Violation(
            "KBD010_SERVICE_WIRING",
            "service must revalidate policy, use ETM facade and avoid sensitive MRU reads/writes",
        ))
    if service.count("hideEmojiPanel();") < 8:
        violations.append(Violation(
            "KBD010_LIFECYCLE_CLEAR",
            "panel memory must clear on mode, voice, editor, view, window and service transitions",
        ))

    if "## Status\n\nAccepted" not in adr or "format_version=1" not in adr:
        violations.append(Violation(
            "KBD010_ACCEPTED_ADR",
            "versioned recent format requires Accepted ADR-0013",
        ))
    shared_pref_excludes = backup.count('<exclude domain="sharedpref" path="." />')
    if shared_pref_excludes != 2:
        violations.append(Violation(
            "KBD010_BACKUP_EXCLUSION",
            "cloud backup and device transfer must both exclude preferences",
        ))

    required_tests = (
        (unit_tests, "everyBrowseCategoryIsBoundedNonEmptyAndGloballyUnique"),
        (unit_tests, "unknownVersionMalformedUnknownAndOversizedPayloadsFailClosed"),
        (unit_tests, "sensitiveAndNoLearningFieldsKeepStaticEmojiButSuppressRecents"),
        (android_tests, "v1StorePersistsOnlyBoundedCatalogCodePoints"),
        (android_tests, "sensitiveProjectionHidesRecentsButKeepsStaticCategoriesAndClose"),
        (host_test, "selectedImeEmojiInsertsAndSuppressesRecentsInSensitiveFieldWhenRequested"),
        (host_test, 'getString("imeEmojiPackage")'),
    )
    if any(token not in source for source, token in required_tests):
        violations.append(Violation(
            "KBD010_TEST_COVERAGE",
            "tests must cover inventory, format, store, privacy, View and selected IME",
        ))
    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--android-root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    args = parser.parse_args()
    violations = inspect_android(args.android_root)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}", file=sys.stderr)
        return 1
    print("KBD-010 emoji-panel source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
