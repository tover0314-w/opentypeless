#!/usr/bin/env python3
"""RIM-007 private UserDB lifecycle, recovery and no-backup boundary gate."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


STORE_ROOT = Path("app/src/main/java/com/opentypeless/android/rime/userdata")
STORE = STORE_ROOT / "RimeUserDataStore.java"
ERROR = STORE_ROOT / "RimeUserDataException.java"
ENGINE = Path(
    "app/src/main/java/com/opentypeless/android/keyboard/rime/NativeRimeInputEngine.java"
)
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
ACTIVITY = Path("app/src/main/java/com/opentypeless/android/RimeResourceActivity.java")
ADAPTER = Path(
    "../third_party/rime/runtime/java/com/opentypeless/ksp004/RimeAdapter.java"
)
JNI = Path("../third_party/rime/runtime/jni/rime_jni.cc")
STORE_TEST = Path(
    "app/src/test/java/com/opentypeless/android/rime/userdata/RimeUserDataStoreTest.java"
)
ENGINE_TEST = Path(
    "app/src/test/java/com/opentypeless/android/keyboard/rime/NativeRimeInputEngineTest.java"
)
SEED_TEST = Path(
    "app/src/androidTest/java/com/opentypeless/android/rime/runtime/"
    "RimeUserDataSeedInstrumentedTest.java"
)
RESTART_TEST = Path(
    "app/src/androidTest/java/com/opentypeless/android/rime/runtime/"
    "RimeUserDataRestartInstrumentedTest.java"
)
MANIFEST = Path("app/src/main/AndroidManifest.xml")
BACKUP_RULES = Path("app/src/main/res/xml/data_extraction_rules.xml")


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _read(root: Path, relative: Path, rule: str, violations: list[Violation]) -> str:
    path = root / relative
    if not path.is_file() or path.is_symlink():
        violations.append(Violation(rule, str(relative)))
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError:
        violations.append(Violation(rule, f"invalid UTF-8: {relative}"))
        return ""


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    actual = (
        {path.name for path in (root / STORE_ROOT).glob("*.java")}
        if (root / STORE_ROOT).is_dir() and not (root / STORE_ROOT).is_symlink()
        else set()
    )
    if actual != {"RimeUserDataStore.java", "RimeUserDataException.java"}:
        violations.append(Violation("RIM007_SOURCE_SET", str(sorted(actual))))

    store = _read(root, STORE, "RIM007_STORE_SOURCE", violations)
    error = _read(root, ERROR, "RIM007_ERROR_SOURCE", violations)
    engine = _read(root, ENGINE, "RIM007_ENGINE_SOURCE", violations)
    service = _read(root, SERVICE, "RIM007_SERVICE_SOURCE", violations)
    activity = _read(root, ACTIVITY, "RIM007_ACTIVITY_SOURCE", violations)
    adapter = _read(root, ADAPTER, "RIM007_ADAPTER_SOURCE", violations)
    jni = _read(root, JNI, "RIM007_JNI_SOURCE", violations)
    store_test = _read(root, STORE_TEST, "RIM007_STORE_TEST", violations)
    engine_test = _read(root, ENGINE_TEST, "RIM007_ENGINE_TEST", violations)
    seed_test = _read(root, SEED_TEST, "RIM007_SEED_TEST", violations)
    restart_test = _read(root, RESTART_TEST, "RIM007_RESTART_TEST", violations)
    manifest = _read(root, MANIFEST, "RIM007_MANIFEST", violations)
    backup = _read(root, BACKUP_RULES, "RIM007_BACKUP_RULES", violations)

    store_compact = _compact(store)
    required_store = (
        'ROOT_NAME="rime_user_data_v1"',
        "getNoBackupFilesDir(),ROOT_NAME",
        'CURRENT_NAME="current"',
        'CHECKPOINT_NAME="checkpoint"',
        'Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,126}\\\\.userdb")',
        "MAXIMUM_FILES=2_048",
        "MAXIMUM_FILE_BYTES=16L*1024L*1024L",
        "MAXIMUM_TOTAL_BYTES=64L*1024L*1024L",
        "MAXIMUM_DEPTH=16",
        "newSemaphore(1,true)",
        "if(!LIFECYCLE_LEASE.tryAcquire())",
        "copyUserData(current,next)",
        "if(checkpoint.exists()&&!checkpoint.renameTo(old))",
        "if(!next.renameTo(checkpoint))",
        "copyUserData(checkpoint,next)",
        "if(current.exists()&&!current.renameTo(old))",
        "if(!next.renameTo(current))",
        "Files.isSymbolicLink(node.toPath())",
        "output.getFD().sync()",
        "deleteTreeRequired(child(root,CURRENT_NAME))",
        "deleteTreeRequired(child(root,CHECKPOINT_NAME))",
        "requireDirectory(child(root,CURRENT_NAME))",
    )
    if any(token not in store_compact for token in required_store):
        violations.append(Violation(
            "RIM007_STORE_CONTRACT",
            "no-backup, bounded, exclusive, atomic UserDB store drifted",
        ))
    if store_compact.count(
        "if(!USERDB_NAME.matcher(child.getName()).matches())"
    ) != 2:
        violations.append(Violation(
            "RIM007_STORE_CONTRACT",
            "checkpoint and status scans must both restrict roots to *.userdb",
        ))
    forbidden_store = (
        "getFilesDir", "getExternal", "getCacheDir", "SharedPreferences", "SQLite",
        "java.net", "okhttp", "android.util.Log", "ContentResolver", "Intent",
        "RimeResourceStore", "InputConnection", "commitText", "export", "upload",
    )
    if any(token in store for token in forbidden_store):
        violations.append(Violation(
            "RIM007_STORAGE_BOUNDARY",
            "UserDB store gained resource/editor/network/export/backup authority",
        ))
    if "enum Code {\n        BUSY,\n        STORAGE_FAILED,\n        LIMIT_EXCEEDED,\n        NO_CHECKPOINT" not in error:
        violations.append(Violation("RIM007_ERROR_CONTRACT", "stable error vocabulary drifted"))

    engine_compact = _compact(engine)
    required_engine = (
        "RimeUserDataStoreuserDataStore",
        "persistentUserData(userDataStore)",
        "userDataLease=userDataLeaseFactory.open()",
        "if(!userDataLease.restoreLatestCheckpoint())throwfirstFailure",
        "session=openSession(userDataLease)",
        "owned.synchronizeUserData()",
        'SessioncommittedSession=Objects.requireNonNull(session,"activeRimesession")',
        'UserDataLeasecommittedUserData=Objects.requireNonNull(userDataLease,'
        '"activeRimeUserDBlease")',
        "committedSession.close();session=null",
        "committedUserData.checkpoint();userDataLease=null;committedUserData.close()",
        "ownedUserData.close()",
    )
    if any(token not in engine_compact for token in required_engine):
        violations.append(Violation(
            "RIM007_ENGINE_LIFECYCLE",
            "engine must restore once, terminate native sync, checkpoint, and release",
        ))
    close_at = engine_compact.find("committedSession.close()")
    checkpoint_at = engine_compact.find("committedUserData.checkpoint()", close_at)
    commit_at = engine_compact.find("returnnewCommitReady", checkpoint_at)
    if min(close_at, checkpoint_at, commit_at) < 0 or not (
        close_at < checkpoint_at < commit_at
    ):
        violations.append(Violation(
            "RIM007_CONSISTENCY_ORDER",
            "native commit must follow session close -> checkpoint -> delivery",
        ))
    if engine_compact.count("userDataLease.restoreLatestCheckpoint()") != 1:
        violations.append(Violation(
            "RIM007_RESTORE_ONCE", "activation may attempt exactly one local restore"
        ))

    service_compact = _compact(service)
    if (
        "rimeUserDataStore=newRimeUserDataStore(this)" not in service_compact
        or "newNativeRimeInputEngine(runtime.root(),config,rimeUserDataStore,"
        "runtime.deploymentId())"
        not in service_compact
    ):
        violations.append(Violation(
            "RIM007_PRODUCT_WIRING", "IME must use the persistent no-backup UserDB store"
        ))

    activity_compact = _compact(activity)
    for token in (
        "userDataStore.restoreLatestCheckpoint()",
        "userDataStore.clear()",
        "R.string.rime_userdata_error_busy",
        "R.string.rime_userdata_error_no_checkpoint",
        "restoreUserData.setEnabled(!busy&&userDataCheckpoint)",
        "clearUserData.setEnabled(!busy&&(userDataAvailable||userDataCheckpoint))",
    ):
        if token not in activity_compact:
            violations.append(Violation("RIM007_SETTINGS_WIRING", token))
    resource_clear = activity_compact[
        activity_compact.find("privatevoidclearResources()"):
        activity_compact.find("privatevoidrefreshStatus()")
    ]
    if "userDataStore" in resource_clear:
        violations.append(Violation(
            "RIM007_RESOURCE_SEPARATION",
            "clearing an imported Schema package must not silently clear UserDB",
        ))

    adapter_compact = _compact(adapter)
    for token in (
        "publicvoidsynchronizeUserData()",
        "if(!nativeSyncUserData())",
        "session=0L",
        "closed=true",
        "nativeFinalizeEngine()",
    ):
        if token not in adapter_compact:
            violations.append(Violation("RIM007_NATIVE_SYNC", token))
    sync_start = adapter_compact.find("publicvoidsynchronizeUserData()")
    sync_end = adapter_compact.find("@Overridepublicvoidclose()", sync_start)
    sync_block = adapter_compact[sync_start:sync_end] if sync_start >= 0 and sync_end >= 0 else ""
    if (
        not sync_block
        or sync_block.count("nativeFinalizeEngine()") != 1
        or "session=0L" not in sync_block
        or "closed=true" not in sync_block
    ):
        violations.append(Violation(
            "RIM007_NATIVE_SYNC", "terminal sync must join maintenance exactly once"
        ))
    if "g_api->sync_user_data()?JNI_TRUE:JNI_FALSE" not in _compact(jni):
        violations.append(Violation("RIM007_JNI_SYNC", "pinned API sync edge missing"))

    for token in (
        "checkpointCopiesOnlyUserDbAndRestoreIsAtomic",
        "interruptedCheckpointRecoversLastCompleteCopy",
        "symlinkAndBusyManagementFailClosed",
    ):
        if token not in store_test:
            violations.append(Violation("RIM007_STORE_TEST", token))
    for token in (
        "exactCandidateClosesSessionBeforeCreatingRecoveryPoint",
        "fixedLengthNativeAutoCommitReturnsCommitAndCreatesRecoveryPoint",
        "failedOpenRestoresOneCheckpointThenRetriesExactlyOnce",
        "missingRecoveryPointNeverLoopsAfterFailedOpen",
        "checkpointFailureNeverReturnsACommit",
    ):
        if token not in engine_test:
            violations.append(Violation("RIM007_ENGINE_TEST", token))
    for source, tokens in (
        (seed_test, (
            "selectingSecondCandidateCreatesBoundedRecoveryPoint",
            'assertEquals("乙"', "status.hasCheckpoint()",
        )),
        (restart_test, (
            "learnedRankingSurvivesRestartAndClearReturnsToStaticOrder",
            'assertEquals("乙"', "userData.restoreLatestCheckpoint()",
            "userData.clear()", 'assertEquals("甲"',
        )),
    ):
        if any(token not in source for token in tokens):
            violations.append(Violation("RIM007_DEVICE_MATRIX", "fresh-process matrix drifted"))

    if 'android:allowBackup="false"' not in manifest or 'android:fullBackupContent="false"' not in manifest:
        violations.append(Violation("RIM007_NO_BACKUP", "application backup must remain disabled"))
    domains = (
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )
    for domain in domains:
        if backup.count(f'<exclude domain="{domain}" path="." />') != 2:
            violations.append(Violation("RIM007_NO_BACKUP", f"missing deny-all {domain}"))
    if "<include " in backup:
        violations.append(Violation("RIM007_NO_BACKUP", "backup include is forbidden"))
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
    print("RIM-007 private UserDB lifecycle boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
