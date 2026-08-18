#!/usr/bin/env python3
"""Fail-closed validation for the changelog and compatibility authority matrix."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


MATRIX_PATH = Path("docs/COMPATIBILITY.md")
CHANGELOG_PATH = Path("CHANGELOG.md")
README_PATHS = (Path("README.md"), Path("README_zh.md"))
MATRIX_BEGIN = "<!-- BEGIN COMPATIBILITY MATRIX -->"
MATRIX_END = "<!-- END COMPATIBILITY MATRIX -->"
BASELINE_CHANGE_ID = "COMPAT-BASELINE-2026-08-14"
STR001_CHANGE_ID = "STR-001-STREAMING-PROTOCOL-V1-2026-08-15"
KBD001_CHANGE_ID = "KBD-001-ROUTE-A-SHELL-2026-08-16"
KBD005_CHANGE_ID = "KBD-005"
RIM003_CHANGE_ID = "RIM-003-RIME-RESOURCE-MANIFEST-V1-2026-08-16"
PLACEHOLDER_PATTERN = re.compile(r"\b(?:TODO|TBD|FIXME)\b|以后补充|待定", re.I)


@dataclass(frozen=True)
class MatrixExpectation:
    kind: str
    current: str
    authorities: tuple[str, ...]
    change_id: str = BASELINE_CHANGE_ID


EXPECTED_ROWS: dict[str, MatrixExpectation] = {
    "android-app": MatrixExpectation(
        "runtime", "0.3.0+3", ("android/app/build.gradle.kts",)
    ),
    "android-platform": MatrixExpectation(
        "runtime",
        "min26,compile35,target35",
        ("android/app/build.gradle.kts", ".github/workflows/ci.yml"),
    ),
    "desktop-app": MatrixExpectation(
        "runtime",
        "1.2.0",
        (
            "package.json",
            "src-tauri/Cargo.toml",
            "src-tauri/tauri.conf.json",
            "src/lib/constants.ts",
        ),
    ),
    "desktop-platform": MatrixExpectation(
        "runtime",
        "macOS>=10.15,Windows,Linux",
        ("src-tauri/tauri.conf.json", "src-tauri/Cargo.toml"),
    ),
    "android-global-config": MatrixExpectation(
        "config",
        "1",
        (
            "android/app/src/main/java/com/opentypeless/android/config/GlobalConfig.java",
            "android/app/src/main/java/com/opentypeless/android/settings/LegacyAppSettingsMigration.java",
        ),
    ),
    "android-override-value": MatrixExpectation(
        "config",
        "1",
        ("android/app/src/main/java/com/opentypeless/android/config/OverrideValueCodec.java",),
    ),
    "android-app-rule-migration": MatrixExpectation(
        "config",
        "0.2->1,migration1",
        (
            "android/app/src/main/java/com/opentypeless/android/settings/LegacyAppProfileMigration.java",
        ),
    ),
    "android-secret-store": MatrixExpectation(
        "config",
        "format1,migration1",
        ("android/app/src/main/java/com/opentypeless/android/security/SecretStore.java",),
    ),
    "android-personalization-db": MatrixExpectation(
        "schema",
        "4",
        ("android/app/src/main/java/com/opentypeless/android/data/PersonalizationStore.java",),
    ),
    "cross-platform-dictionary": MatrixExpectation(
        "format",
        "opentypeless_dictionary:1",
        (
            "android/app/src/main/java/com/opentypeless/android/data/PersonalizationStore.java",
            "src-tauri/src/dictionary_io.rs",
        ),
    ),
    "android-voice-recovery-journal": MatrixExpectation(
        "format",
        "1",
        (
            "android/app/src/main/java/com/opentypeless/android/security/VoiceRecoveryJournal.java",
        ),
    ),
    "android-voice-draft-journal": MatrixExpectation(
        "format",
        "1",
        (
            "android/app/src/main/java/com/opentypeless/android/speech/journal/VoiceDraftJournal.java",
        ),
    ),
    "android-engine-trace": MatrixExpectation(
        "schema",
        "1",
        ("android/app/src/main/java/com/opentypeless/android/speech/engine/EngineTrace.java",),
    ),
    "android-editor-fingerprint-frame": MatrixExpectation(
        "schema",
        "1",
        (
            "android/app/src/main/java/com/opentypeless/android/editor/Sha256EditorTextHasher.java",
        ),
    ),
    "android-keyboard-shell-route": MatrixExpectation(
        "config",
        "route-a-default,legacy-alias-migration1",
        (
            "android/app/src/main/java/com/opentypeless/android/keyboard/shell/KeyboardShellConfig.java",
        ),
        KBD001_CHANGE_ID,
    ),
    "android-keyboard-feedback": MatrixExpectation(
        "config",
        "1",
        (
            "android/app/src/main/java/com/opentypeless/android/keyboard/feedback/KeyboardFeedbackPreferences.java",
        ),
        KBD005_CHANGE_ID,
    ),
    "android-rime-resource-manifest": MatrixExpectation(
        "format",
        "opentypeless.rime-resource-manifest:1",
        (
            "android/app/src/main/java/com/opentypeless/android/rime/importer/RimeResourceManifest.java",
            "protocol/opentypeless-rime-import-manifest-v1.schema.json",
        ),
        RIM003_CHANGE_ID,
    ),
    "desktop-config-store": MatrixExpectation(
        "config", "legacy-unversioned", ("src-tauri/src/storage/mod.rs",)
    ),
    "desktop-history-db": MatrixExpectation(
        "schema", "legacy-unversioned", ("src-tauri/src/storage/mod.rs",)
    ),
    "desktop-scene-export": MatrixExpectation(
        "format", "1", ("src/lib/scenes/sceneImportExport.ts",)
    ),
    "desktop-app-mapping-store": MatrixExpectation(
        "format", "1", ("src-tauri/src/app_detector/user_mappings.rs",)
    ),
    "desktop-credential-payload": MatrixExpectation(
        "format", "1", ("src-tauri/src/credentials.rs",)
    ),
    "desktop-stt-capability-registry": MatrixExpectation(
        "schema", "1", ("src-tauri/src/stt/capabilities.rs",)
    ),
    "desktop-context-prompt": MatrixExpectation(
        "protocol", "context-v1", ("src-tauri/src/llm/prompt.rs",)
    ),
    "action-protocol": MatrixExpectation(
        "protocol",
        "opentypeless.action.v1-spec-only",
        ("docs/opentypeless_specs/04_ACTION_PROTOCOL_V1.md",),
    ),
    "android-paraformer-protocol": MatrixExpectation(
        "protocol",
        "external-unversioned",
        (
            "android/app/src/main/java/com/opentypeless/android/net/streaming/ParaformerProtocol.java",
        ),
    ),
    "android-streaming-recognition-protocol": MatrixExpectation(
        "protocol",
        "opentypeless.streaming.v1",
        (
            "android/app/src/main/java/com/opentypeless/android/net/streaming/StreamingRecognitionWireEvent.java",
            "android/app/src/main/resources/schemas/opentypeless-streaming-recognition-event-v1.schema.json",
        ),
        STR001_CHANGE_ID,
    ),
}


EXPECTED_VERSION_CONSTANTS: dict[tuple[str, str], str] = {
    (
        "android/app/src/main/java/com/opentypeless/android/config/GlobalConfig.java",
        "FORMAT_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/config/OverrideValueCodec.java",
        "FORMAT_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/data/PersonalizationStore.java",
        "VERSION",
    ): "4",
    (
        "android/app/src/main/java/com/opentypeless/android/editor/Sha256EditorTextHasher.java",
        "FRAME_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/keyboard/feedback/KeyboardFeedbackPreferences.java",
        "CURRENT_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/keyboard/feedback/KeyboardFeedbackPreferences.java",
        "VERSION",
    ): "formatversion",
    (
        "android/app/src/main/java/com/opentypeless/android/security/SecretStore.java",
        "FORMAT_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/security/SecretStore.java",
        "MIGRATION_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/security/VoiceRecoveryJournal.java",
        "VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/settings/LegacyAppProfileMigration.java",
        "MIGRATION_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/settings/LegacyAppProfileMigration.java",
        "TARGET_FORMAT_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/settings/LegacyAppProfileMigration.java",
        "SOURCE_VERSION",
    ): "0.2",
    (
        "android/app/src/main/java/com/opentypeless/android/settings/LegacyAppSettingsMigration.java",
        "MIGRATION_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/settings/LegacyAppSettingsMigration.java",
        "SOURCE_VERSION",
    ): "0.2",
    (
        "android/app/src/main/java/com/opentypeless/android/speech/engine/EngineTrace.java",
        "CURRENT_SCHEMA_VERSION",
    ): "1",
    (
        "android/app/src/main/java/com/opentypeless/android/speech/journal/VoiceDraftJournal.java",
        "VERSION",
    ): "1",
    ("src-tauri/src/app_detector/user_mappings.rs", "MAPPING_STORE_VERSION"): "1",
    ("src-tauri/src/credentials.rs", "STORED_CREDENTIAL_VERSION"): "1",
    ("src-tauri/src/llm/prompt.rs", "CONTEXT_PROMPT_VERSION"): "context-v1",
    ("src-tauri/src/stt/capabilities.rs", "CAPABILITY_REGISTRY_VERSION"): "1",
}


JAVA_VERSION_PATTERN = re.compile(
    r"(?m)^\s*(?:(?:public|private|protected|static|final)\s+)*"
    r"(?:int|long|String)\s+([A-Z][A-Z0-9_]*VERSION[A-Z0-9_]*|VERSION)\s*=\s*([^;]+);"
)
RUST_VERSION_PATTERN = re.compile(
    r"(?m)^\s*(?:pub\s+)?const\s+([A-Z][A-Z0-9_]*VERSION[A-Z0-9_]*)\s*:\s*[^=]+\s*=\s*([^;]+);"
)


def _read_regular_utf8(path: Path, root: Path, errors: list[str]) -> str | None:
    if not path.is_file() or path.is_symlink():
        errors.append(f"missing or non-regular compatibility authority: {path.relative_to(root)}")
        return None
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError as error:
        errors.append(f"{path.relative_to(root)}: invalid UTF-8: {error}")
        return None


def _unquote_literal(value: str) -> str:
    value = value.strip().replace("_", "")
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
        return value[1:-1]
    return value


def _code_cell(value: str) -> str:
    value = value.strip()
    return value[1:-1] if len(value) >= 2 and value.startswith("`") and value.endswith("`") else value


def _parse_matrix(text: str, errors: list[str]) -> dict[str, tuple[str, ...]]:
    if text.count(MATRIX_BEGIN) != 1 or text.count(MATRIX_END) != 1:
        errors.append(f"{MATRIX_PATH}: compatibility matrix markers must appear exactly once")
        return {}
    section = text.split(MATRIX_BEGIN, 1)[1].split(MATRIX_END, 1)[0]
    lines = [line for line in section.splitlines() if line.startswith("|")]
    expected_header = (
        "ID",
        "Kind",
        "Current",
        "Read / upgrade",
        "Write",
        "Authority",
        "Change ID",
    )
    rows: dict[str, tuple[str, ...]] = {}
    for index, line in enumerate(lines):
        cells = tuple(cell.strip() for cell in line.strip().strip("|").split("|"))
        if index == 0:
            if cells != expected_header:
                errors.append(f"{MATRIX_PATH}: compatibility table header drift")
            continue
        if index == 1 and all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
            continue
        if len(cells) != len(expected_header):
            errors.append(f"{MATRIX_PATH}: malformed compatibility row: {line}")
            continue
        identifier = _code_cell(cells[0])
        if identifier in rows:
            errors.append(f"{MATRIX_PATH}: duplicate compatibility id: {identifier}")
        rows[identifier] = cells
    return rows


def _collect_version_constants(root: Path, errors: list[str]) -> dict[tuple[str, str], str]:
    found: dict[tuple[str, str], str] = {}
    source_roots = (
        (Path("android/app/src/main/java"), ".java", JAVA_VERSION_PATTERN),
        (Path("src-tauri/src"), ".rs", RUST_VERSION_PATTERN),
    )
    for relative_root, suffix, pattern in source_roots:
        absolute_root = root / relative_root
        if not absolute_root.is_dir():
            errors.append(f"missing compatibility source root: {relative_root}")
            continue
        for path in sorted(absolute_root.rglob(f"*{suffix}")):
            if path.is_symlink() or not path.is_file():
                errors.append(f"non-regular compatibility source: {path.relative_to(root)}")
                continue
            text = path.read_text(encoding="utf-8")
            for name, literal in pattern.findall(text):
                if name.startswith("KEY_"):
                    continue
                key = (path.relative_to(root).as_posix(), name)
                found[key] = _unquote_literal(literal)
    return found


def _require_regex(text: str | None, pattern: str, label: str, errors: list[str]) -> None:
    if text is not None and re.search(pattern, text, re.M | re.S) is None:
        errors.append(f"compatibility authority drift: {label}")


def validate_repository(repo_root: Path) -> list[str]:
    root = repo_root.resolve()
    errors: list[str] = []
    matrix_text = _read_regular_utf8(root / MATRIX_PATH, root, errors)
    changelog = _read_regular_utf8(root / CHANGELOG_PATH, root, errors)

    rows = _parse_matrix(matrix_text, errors) if matrix_text is not None else {}
    if set(rows) != set(EXPECTED_ROWS):
        missing = sorted(set(EXPECTED_ROWS) - set(rows))
        extra = sorted(set(rows) - set(EXPECTED_ROWS))
        errors.append(f"{MATRIX_PATH}: row set drift; missing={missing}, extra={extra}")
    for identifier, expectation in EXPECTED_ROWS.items():
        cells = rows.get(identifier)
        if cells is None:
            continue
        kind = _code_cell(cells[1])
        current = _code_cell(cells[2])
        authorities = tuple(re.findall(r"`([^`]+)`", cells[5]))
        change_id = _code_cell(cells[6])
        if kind != expectation.kind or current != expectation.current:
            errors.append(
                f"{MATRIX_PATH}: {identifier} expected {expectation.kind}/{expectation.current}, "
                f"found {kind}/{current}"
            )
        if authorities != expectation.authorities:
            errors.append(f"{MATRIX_PATH}: {identifier} authority list drift")
        if not cells[3] or not cells[4] or PLACEHOLDER_PATTERN.search(cells[3] + cells[4]):
            errors.append(f"{MATRIX_PATH}: {identifier} has an empty or placeholder compatibility policy")
        if change_id != expectation.change_id:
            errors.append(f"{MATRIX_PATH}: {identifier} has an untracked change id: {change_id}")

    if changelog is not None:
        required_changelog_tokens = (
            "## [Unreleased]",
            "### Compatibility",
            BASELINE_CHANGE_ID,
            STR001_CHANGE_ID,
            "not a release tag",
            "Every future version change",
        )
        for token in required_changelog_tokens:
            if token not in changelog:
                errors.append(f"{CHANGELOG_PATH}: missing compatibility history token: {token}")
        if PLACEHOLDER_PATTERN.search(changelog):
            errors.append(f"{CHANGELOG_PATH}: changelog contains a placeholder")

    for readme_path in README_PATHS:
        readme = _read_regular_utf8(root / readme_path, root, errors)
        if readme is None:
            continue
        for token in ("CHANGELOG.md", "docs/COMPATIBILITY.md"):
            if token not in readme:
                errors.append(f"{readme_path}: missing DOC-003 discovery link: {token}")

    actual_constants = _collect_version_constants(root, errors)
    if actual_constants != EXPECTED_VERSION_CONSTANTS:
        missing = sorted(set(EXPECTED_VERSION_CONSTANTS) - set(actual_constants))
        extra = sorted(set(actual_constants) - set(EXPECTED_VERSION_CONSTANTS))
        changed = sorted(
            key
            for key in set(actual_constants) & set(EXPECTED_VERSION_CONSTANTS)
            if actual_constants[key] != EXPECTED_VERSION_CONSTANTS[key]
        )
        errors.append(
            "version authority inventory drift; "
            f"missing={missing}, extra={extra}, changed={[(key, actual_constants[key]) for key in changed]}"
        )

    android_gradle = _read_regular_utf8(root / "android/app/build.gradle.kts", root, errors)
    for pattern, label in (
        (r"\bcompileSdk\s*=\s*35\b", "Android compileSdk 35"),
        (r"\bminSdk\s*=\s*26\b", "Android minSdk 26"),
        (r"\btargetSdk\s*=\s*35\b", "Android targetSdk 35"),
        (r"\bversionCode\s*=\s*3\b", "Android versionCode 3"),
        (r'\bversionName\s*=\s*"0\.3\.0"', "Android versionName 0.3.0"),
    ):
        _require_regex(android_gradle, pattern, label, errors)

    workflow = _read_regular_utf8(root / ".github/workflows/ci.yml", root, errors)
    _require_regex(workflow, r"api_level:\s*\[26,\s*33,\s*35,\s*36\]", "Android API matrix", errors)

    package_path = root / "package.json"
    tauri_path = root / "src-tauri/tauri.conf.json"
    cargo_path = root / "src-tauri/Cargo.toml"
    try:
        package = json.loads(package_path.read_text(encoding="utf-8"))
        tauri = json.loads(tauri_path.read_text(encoding="utf-8"))
        cargo_text = cargo_path.read_text(encoding="utf-8")
        package_section = cargo_text.split("[package]", 1)[1].split("\n[", 1)[0]
        cargo_version_match = re.search(r'(?m)^version\s*=\s*"([^"]+)"\s*$', package_section)
        cargo_version = cargo_version_match.group(1) if cargo_version_match else None
        desktop_versions = (package.get("version"), tauri.get("version"), cargo_version)
        if desktop_versions != ("1.2.0", "1.2.0", "1.2.0"):
            errors.append(f"desktop application version authority drift: {desktop_versions}")
        if tauri.get("bundle", {}).get("macOS", {}).get("minimumSystemVersion") != "10.15":
            errors.append("desktop macOS minimum authority drift")
        if "target_os = \"windows\"" not in cargo_text or "target_os = \"linux\"" not in cargo_text:
            errors.append("desktop Windows/Linux target authority drift")
    except (OSError, UnicodeError, json.JSONDecodeError, IndexError) as error:
        errors.append(f"desktop version authority could not be parsed: {error}")

    constants = _read_regular_utf8(root / "src/lib/constants.ts", root, errors)
    _require_regex(constants, r"VITE_APP_VERSION\s*\?\?\s*'v1\.2\.0'", "desktop UI version fallback", errors)

    personalization = _read_regular_utf8(
        root / "android/app/src/main/java/com/opentypeless/android/data/PersonalizationStore.java",
        root,
        errors,
    )
    for pattern, label in (
        (r'put\("format",\s*"opentypeless_dictionary"\)', "Android dictionary format"),
        (r'put\("version",\s*1\)', "Android dictionary version"),
        (r"oldVersion\s*<\s*2", "Android DB migration 1 to 2"),
        (r"oldVersion\s*<\s*3", "Android DB migration 2 to 3"),
        (r"oldVersion\s*<\s*4", "Android DB migration 3 to 4"),
    ):
        _require_regex(personalization, pattern, label, errors)

    dictionary = _read_regular_utf8(root / "src-tauri/src/dictionary_io.rs", root, errors)
    for pattern, label in (
        (r'"format"\s*:\s*"opentypeless_dictionary"', "desktop dictionary format"),
        (r'"version"\s*:\s*1', "desktop dictionary version"),
        (r'write_record\(\[CSV_MARKER,\s*"1"\]\)', "desktop dictionary CSV version"),
    ):
        _require_regex(dictionary, pattern, label, errors)

    scene = _read_regular_utf8(root / "src/lib/scenes/sceneImportExport.ts", root, errors)
    for pattern, label in (
        (r"interface\s+SceneExportPayload\s*\{[^}]*version:\s*1", "scene export v1 shape"),
        (r"const\s+payload:\s*SceneExportPayload\s*=\s*\{\s*version:\s*1", "scene export v1 writer"),
        (r"Array\.isArray\(parsed\)", "scene legacy array reader"),
    ):
        _require_regex(scene, pattern, label, errors)

    storage = _read_regular_utf8(root / "src-tauri/src/storage/mod.rs", root, errors)
    if storage is not None:
        config_match = re.search(r"pub struct AppConfig\s*\{(.*?)\n\}", storage, re.S)
        if config_match is None or re.search(r"\b(?:format_)?version\s*:", config_match.group(1)):
            errors.append("desktop config legacy-unversioned boundary drift")
        if "PRAGMA user_version" in storage:
            errors.append("desktop history DB is no longer legacy-unversioned; update DOC-003")
        for token in ("#[serde(default)]", "ensure_history_optional_columns"):
            if token not in storage:
                errors.append(f"desktop unversioned compatibility behavior drift: {token}")

    action_token = "opentypeless.action.v1"
    action_spec = _read_regular_utf8(
        root / "docs/opentypeless_specs/04_ACTION_PROTOCOL_V1.md", root, errors
    )
    if action_spec is not None and action_token not in action_spec:
        errors.append("Action Protocol v1 specification authority drift")
    for production_root in (root / "android/app/src/main", root / "src", root / "src-tauri/src"):
        if not production_root.is_dir():
            continue
        for path in production_root.rglob("*"):
            if path.is_file() and not path.is_symlink() and action_token in path.read_text(errors="ignore"):
                errors.append(f"Action Protocol is no longer spec-only: {path.relative_to(root)}")

    paraformer = _read_regular_utf8(
        root / "android/app/src/main/java/com/opentypeless/android/net/streaming/ParaformerProtocol.java",
        root,
        errors,
    )
    _require_regex(paraformer, r"DashScope Paraformer realtime WebSocket API", "Paraformer vendor authority", errors)
    if paraformer is not None and "PROTOCOL_VERSION" in paraformer:
        errors.append("Paraformer gained a local protocol version; update DOC-003")

    streaming_wire = _read_regular_utf8(
        root
        / "android/app/src/main/java/com/opentypeless/android/net/streaming/StreamingRecognitionWireEvent.java",
        root,
        errors,
    )
    _require_regex(
        streaming_wire,
        r'PROTOCOL\s*=\s*"opentypeless\.streaming\.v1"',
        "streaming RecognitionEvent protocol v1",
        errors,
    )
    streaming_schema = _read_regular_utf8(
        root
        / "android/app/src/main/resources/schemas/opentypeless-streaming-recognition-event-v1.schema.json",
        root,
        errors,
    )
    if streaming_schema is not None:
        try:
            schema = json.loads(streaming_schema)
            if (
                schema.get("$id")
                != "https://opentypeless.local/schema/streaming-recognition-event-v1.json"
                or schema.get("$defs", {}).get("protocol", {}).get("const")
                != "opentypeless.streaming.v1"
                or len(schema.get("oneOf", ())) != 8
            ):
                errors.append("streaming RecognitionEvent JSON Schema authority drift")
        except json.JSONDecodeError:
            errors.append("streaming RecognitionEvent JSON Schema is malformed")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = validate_repository(args.repo_root)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(
        f"compatibility validation passed: {len(EXPECTED_ROWS)} matrix rows, "
        f"{len(EXPECTED_VERSION_CONSTANTS)} version authorities"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
