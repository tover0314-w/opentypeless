#!/usr/bin/env python3
"""RIM-003 local-only, fail-closed Schema staging/deploy architecture gate."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


IMPORT_ROOT = Path("app/src/main/java/com/opentypeless/android/rime/importer")
ACTIVITY = Path("app/src/main/java/com/opentypeless/android/RimeResourceActivity.java")
MANIFEST = Path("app/src/main/AndroidManifest.xml")
SETTINGS = Path("app/src/main/java/com/opentypeless/android/SettingsHomeActivity.java")
ADAPTER = Path("../third_party/rime/runtime/java/com/opentypeless/ksp004/RimeAdapter.java")
UNIT_ROOT = Path("app/src/test/java/com/opentypeless/android/rime/importer")
DEVICE_TEST = Path(
    "app/src/androidTest/java/com/opentypeless/android/rime/importer/"
    "RimeSchemaImportInstrumentedTest.java"
)

EXPECTED_IMPORT_SOURCES = {
    "RimeImportException.java",
    "RimeResourceArchive.java",
    "RimeResourceManifest.java",
    "RimeResourceStore.java",
    "RimeRuntimePreferences.java",
    "StrictBoundedJson.java",
}
EXPECTED_UNIT_SOURCES = {
    "RimeImportTestPackages.java",
    "RimeResourceManifestTest.java",
    "RimeResourceStoreTest.java",
}


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _read(root: Path, relative: Path, violations: list[Violation]) -> str:
    path = root / relative
    if not path.is_file() or path.is_symlink():
        violations.append(Violation("RIM003_REQUIRED_FILE", str(relative)))
        return ""
    return path.read_text(encoding="utf-8")


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    import_root = root / IMPORT_ROOT
    actual = {path.name for path in import_root.glob("*.java") if path.is_file()}
    if actual != EXPECTED_IMPORT_SOURCES:
        violations.append(Violation("RIM003_SOURCE_CLOSED_SET", str(sorted(actual))))
    unit_root = root / UNIT_ROOT
    unit_actual = {path.name for path in unit_root.glob("*.java") if path.is_file()}
    if unit_actual != EXPECTED_UNIT_SOURCES:
        violations.append(Violation("RIM003_TEST_CLOSED_SET", str(sorted(unit_actual))))

    sources = {
        name: _read(root, IMPORT_ROOT / name, violations)
        for name in sorted(EXPECTED_IMPORT_SOURCES)
    }
    # RimeRuntimePreferences belongs to the later RIM-006 configuration seam and
    # is audited by rime_engine_contract.py. Keep the RIM-003 import authority
    # scan scoped to archive/manifest/store code so its SharedPreferences ban
    # remains exact instead of weakening it for the importer.
    combined = "\n".join(
        source for name, source in sources.items()
        if name != "RimeRuntimePreferences.java"
    )
    forbidden = (
        "InputConnection", "EditorTransaction", "EditorOperation", "commitText(",
        "setComposingText(", "finishComposingText(", "deleteSurroundingText(",
        "sendKeyEvent(", "java.net.URL", "OkHttp", "HttpURLConnection", "Log.",
        "android.util.Log", "getFilesDir(", "getExternalFilesDir(", "SharedPreferences",
        "takePersistableUriPermission", "ACTION_VIEW", "ACTION_SEND", "GlobalScope",
    )
    for token in forbidden:
        if token in combined:
            violations.append(Violation("RIM003_AUTHORITY_BOUNDARY", token))

    exception = sources.get("RimeImportException.java", "")
    for code in (
        "BUSY", "SOURCE_UNREADABLE", "ARCHIVE_INVALID", "ARCHIVE_LIMIT",
        "PATH_INVALID", "MANIFEST_INVALID", "FILE_SET_MISMATCH", "HASH_MISMATCH",
        "RESOURCE_UNSAFE", "RUNTIME_INCOMPATIBLE", "DEPLOY_FAILED", "STORAGE_FAILED",
    ):
        if code not in exception:
            violations.append(Violation("RIM003_FAILURE_CLOSED_SET", code))

    manifest_source = sources.get("RimeResourceManifest.java", "")
    required_manifest = (
        'ARCHIVE_MANIFEST = "opentypeless-rime-manifest.json"',
        'TRUST_STATE = "USER_PROVIDED_UNVERIFIED"',
        'DISTRIBUTION_SCOPE = "LOCAL_ONLY"',
        "MAXIMUM_FILES = 512", "MAXIMUM_FILE_BYTES = 67_108_864L",
        "MAXIMUM_TOTAL_BYTES = 268_435_456L", "requireKeys(root, ROOT_KEYS)",
        "RimeAdapter.EXPECTED_VERSION", 'rootByPath.get(licenseTextPath)',
        'schema + ".schema.yaml"', "Normalizer.isNormalized", "pathIdentity(path)",
    )
    for token in required_manifest:
        if token not in manifest_source:
            violations.append(Violation("RIM003_MANIFEST_CONTRACT", token))

    archive = sources.get("RimeResourceArchive.java", "")
    required_archive = (
        "readCentralDirectory(archive)", "rejectZip64Extra(extra)",
        "MAXIMUM_COMPRESSION_RATIO = 200L", "requireZipMatchesCentral(zip, central)",
        "copyAndVerify", "validateResource", "YAML_REFERENCE", "hasForbiddenMagic",
        'lower.contains("http://")', 'lower.contains(".lua")',
        "externalAttributes", "unixType != 0x8000", "getFD().sync()",
    )
    for token in required_archive:
        if token not in archive:
            violations.append(Violation("RIM003_ARCHIVE_GATE", token))

    store = sources.get("RimeResourceStore.java", "")
    required_store = (
        "getNoBackupFilesDir()", "tryLock()", "recoverInterruptedCommit()",
        "deployer.deploy(staged.root)", "current.renameTo(rollback)",
        "staged.root.renameTo(current)", "rollback.renameTo(current)",
        "deleteTreeRequired", "RimeAdapter.dryDeploy(staging)",
    )
    for token in required_store:
        if token not in store:
            violations.append(Violation("RIM003_ATOMIC_STORE", token))

    activity = _read(root, ACTIVITY, violations)
    required_activity = (
        "Intent.ACTION_OPEN_DOCUMENT", "Intent.CATEGORY_OPENABLE",
        "Intent.FLAG_GRANT_READ_URI_PERMISSION", "store.stage(input)",
        "showPreview(staged.preview())", "store.commit(staged)", "store.clear()",
        "WindowManager.LayoutParams.FLAG_SECURE",
    )
    for token in required_activity:
        if token not in activity:
            violations.append(Violation("RIM003_EXPLICIT_UI", token))

    android_manifest = _read(root, MANIFEST, violations)
    activity_match = re.search(
        r'<activity\s+android:name="\.RimeResourceActivity"(?P<body>.*?)/>',
        android_manifest,
        re.DOTALL,
    )
    if activity_match is None or 'android:exported="false"' not in activity_match.group("body"):
        violations.append(Violation("RIM003_PRIVATE_COMPONENT", str(MANIFEST)))
    settings = _read(root, SETTINGS, violations)
    if "RimeResourceActivity.class" not in settings:
        violations.append(Violation("RIM003_SETTINGS_ENTRY", str(SETTINGS)))

    adapter = _read(root, ADAPTER, violations)
    for token in (
        "public static synchronized RuntimeInfo dryDeploy(File rootDirectory)",
        "nativeDeploy()", "nativeFinalizeEngine();",
    ):
        if token not in adapter:
            violations.append(Violation("RIM003_NATIVE_DRY_DEPLOY", token))

    tests = "\n".join(
        _read(root, UNIT_ROOT / name, violations) for name in sorted(EXPECTED_UNIT_SOURCES)
    )
    for token in (
        "failedDryDeployKeepsPreviouslyInstalledPackage",
        "extraMissingAndTamperedMembersFailBeforeDeploy",
        "networkAliasAndSymlinkPayloadsFailClosed",
        "highCompressionRatioFailsBeforeExtraction",
        "duplicateAndUnknownRootKeysFailClosed",
        "trustAndNetworkConstantsCannotBeElevated",
    ):
        if token not in tests:
            violations.append(Violation("RIM003_NEGATIVE_TEST", token))
    device = _read(root, DEVICE_TEST, violations)
    for token in (
        "nativeDryDeployRunsInsideNoBackupStorage",
        "importActivityIsPrivateAndAddsNoPermission",
        "explicitlyPreloadedPackageStagesDeploysAndClearsAtomically",
        "rimeImportSha256",
    ):
        if token not in device:
            violations.append(Violation("RIM003_DEVICE_TEST", token))
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
    print("RIM-003 local Schema import contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
