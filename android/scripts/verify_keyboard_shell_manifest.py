#!/usr/bin/env python3
"""Fail-closed KBD-001 source/merged-manifest and backup boundary gate."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "com.opentypeless.android"
PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
}
COMPONENTS = {
    "activity": {
        f"{PACKAGE}.HomeActivity": True,
        f"{PACKAGE}.SettingsHomeActivity": False,
        f"{PACKAGE}.KeyboardFeedbackActivity": False,
        f"{PACKAGE}.MainActivity": False,
        f"{PACKAGE}.DictionaryActivity": False,
        f"{PACKAGE}.HistoryActivity": False,
        f"{PACKAGE}.AppProfileActivity": False,
        f"{PACKAGE}.VoiceLabActivity": False,
        f"{PACKAGE}.RimeResourceActivity": False,
        f"{PACKAGE}.recognition.OpenTypelessRecognizerActivity": True,
    },
    "service": {
        f"{PACKAGE}.recognition.OpenTypelessRecognitionService": True,
        f"{PACKAGE}.offline.LocalStreamingRecognitionService": False,
        f"{PACKAGE}.offline.LocalOfflineRecognitionService": False,
        f"{PACKAGE}.offline.LocalPunctuationRecognitionService": False,
        f"{PACKAGE}.ime.OpenTypelessImeService": True,
    },
}
EXPORTED = {
    f"{PACKAGE}.HomeActivity",
    f"{PACKAGE}.recognition.OpenTypelessRecognizerActivity",
    f"{PACKAGE}.recognition.OpenTypelessRecognitionService",
    f"{PACKAGE}.ime.OpenTypelessImeService",
}
FORBIDDEN_ACTIONS = {
    "android.intent.action.SEND",
    "android.intent.action.SEND_MULTIPLE",
    "android.intent.action.VIEW",
}
BACKUP_DOMAINS = {
    "root",
    "file",
    "database",
    "sharedpref",
    "external",
    "device_root",
    "device_file",
    "device_database",
    "device_sharedpref",
}


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _attr(node: ET.Element, name: str) -> str | None:
    return node.get(ANDROID + name)


def _bool(value: str | None) -> bool | None:
    if value is None:
        return None
    if value == "true":
        return True
    if value == "false":
        return False
    return None


def _component_name(root: ET.Element, value: str | None) -> str:
    if not value:
        return ""
    package = root.get("package") or PACKAGE
    if value.startswith("."):
        return package + value
    if "." not in value:
        return package + "." + value
    return value


def inspect_manifest(path: Path, variant: str) -> tuple[Violation, ...]:
    violations: list[Violation] = []
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        return (Violation("KBD001_MANIFEST_XML", type(error).__name__),)
    if root.tag != "manifest":
        return (Violation("KBD001_MANIFEST_ROOT", root.tag),)

    permissions = {
        _attr(node, "name") or "" for node in root.findall("uses-permission")
    }
    if permissions != PERMISSIONS:
        violations.append(Violation(
            "KBD001_PERMISSION_SET",
            f"expected {sorted(PERMISSIONS)}, got {sorted(permissions)}",
        ))

    queries = root.findall("queries")
    query_actions = {
        _attr(action, "name") or ""
        for query in queries
        for action in query.findall("./intent/action")
    }
    if len(queries) != 1 or query_actions != {"android.speech.RecognitionService"}:
        violations.append(Violation("KBD001_QUERY_SET", str(sorted(query_actions))))

    applications = root.findall("application")
    if len(applications) != 1:
        return tuple(violations + [Violation("KBD001_APPLICATION_COUNT", str(len(applications)))])
    application = applications[0]
    if _bool(_attr(application, "allowBackup")) is not False:
        violations.append(Violation("KBD001_ALLOW_BACKUP", "must be false"))
    if _attr(application, "fullBackupContent") != "false":
        violations.append(Violation("KBD001_FULL_BACKUP", "must be false"))
    if _attr(application, "dataExtractionRules") != "@xml/data_extraction_rules":
        violations.append(Violation("KBD001_DATA_RULES_REF", "unexpected resource"))
    if application.find("profileable") is not None:
        violations.append(Violation("KBD001_PROFILEABLE", "profileable is forbidden"))
    debuggable = _bool(_attr(application, "debuggable"))
    if variant == "debug" and debuggable is not True:
        violations.append(Violation("KBD001_DEBUG_VARIANT", "debuggable=true required"))
    if variant in {"source", "release"} and debuggable not in {None, False}:
        violations.append(Violation("KBD001_RELEASE_DEBUGGABLE", "release/source may not be debuggable"))

    for forbidden in ("activity-alias", "provider", "receiver"):
        if application.findall(forbidden):
            violations.append(Violation("KBD001_COMPONENT_KIND", forbidden))

    observed: dict[str, dict[str, bool]] = {"activity": {}, "service": {}}
    actions: set[str] = set()
    categories: set[str] = set()
    for kind in observed:
        for node in application.findall(kind):
            name = _component_name(root, _attr(node, "name"))
            exported = _bool(_attr(node, "exported"))
            if exported is None:
                violations.append(Violation("KBD001_EXPORTED_EXPLICIT", name))
                continue
            observed[kind][name] = exported
            actions.update(
                _attr(action, "name") or ""
                for action in node.findall("./intent-filter/action")
            )
            categories.update(
                _attr(category, "name") or ""
                for category in node.findall("./intent-filter/category")
            )
            lowered = name.lower()
            if any(token in lowered for token in ("floris", "spellchecker", "clipboard")):
                violations.append(Violation("KBD001_UPSTREAM_SURFACE", name))

    if observed != COMPONENTS:
        violations.append(Violation("KBD001_COMPONENT_SET", str(observed)))
    observed_exported = {
        name for values in observed.values() for name, exported in values.items() if exported
    }
    if observed_exported != EXPORTED:
        violations.append(Violation("KBD001_EXPORTED_SET", str(sorted(observed_exported))))
    if actions & FORBIDDEN_ACTIONS or "android.intent.category.BROWSABLE" in categories:
        violations.append(Violation("KBD001_IMPORT_SHARE_SURFACE", "forbidden intent filter"))

    ime = next(
        (node for node in application.findall("service")
         if _component_name(root, _attr(node, "name")) == f"{PACKAGE}.ime.OpenTypelessImeService"),
        None,
    )
    if ime is None or _attr(ime, "permission") != "android.permission.BIND_INPUT_METHOD":
        violations.append(Violation("KBD001_IME_PERMISSION", "missing exact binding permission"))
    else:
        ime_actions = {
            _attr(action, "name") or ""
            for action in ime.findall("./intent-filter/action")
        }
        metadata = {
            (_attr(node, "name") or "", _attr(node, "resource") or "")
            for node in ime.findall("meta-data")
        }
        if ime_actions != {"android.view.InputMethod"} or metadata != {
            ("android.view.im", "@xml/method")
        }:
            violations.append(Violation("KBD001_IME_SHAPE", "unexpected action or metadata"))
    return tuple(violations)


def inspect_rules(path: Path) -> tuple[Violation, ...]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        return (Violation("KBD001_RULES_XML", type(error).__name__),)
    violations: list[Violation] = []
    if root.tag != "data-extraction-rules" or root.findall(".//include"):
        violations.append(Violation("KBD001_RULES_ROOT", "closed-world excludes required"))
    for section in ("cloud-backup", "device-transfer"):
        nodes = root.findall(section)
        entries = {
            (node.get("domain") or "", node.get("path") or "")
            for parent in nodes
            for node in parent.findall("exclude")
        }
        expected = {(domain, ".") for domain in BACKUP_DOMAINS}
        if len(nodes) != 1 or entries != expected:
            violations.append(Violation("KBD001_RULES_DOMAINS", f"{section}: {sorted(entries)}"))
    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--rules", type=Path, required=True)
    parser.add_argument("--variant", choices=("source", "debug", "release"), required=True)
    args = parser.parse_args()
    violations = inspect_manifest(args.manifest, args.variant) + inspect_rules(args.rules)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}", file=sys.stderr)
        return 1
    print(f"KBD-001 manifest boundary passed: {args.variant}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
