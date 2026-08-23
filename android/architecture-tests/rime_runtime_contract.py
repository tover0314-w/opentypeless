#!/usr/bin/env python3
"""RIM-002 pinned, resource-free and editor-capability-free native runtime gate."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
from pathlib import Path
import sys
import zipfile


AAR = Path("app/libs/opentypeless-rime-runtime-1.17.0.aar")
BUILD = Path("../third_party/rime/runtime/build-rime-android.sh")
PACKAGE = Path("../third_party/rime/runtime/build-runtime-aar.sh")
PATCH = Path("../third_party/rime/runtime/opencc-android.patch")
NOTICE = Path("../third_party/rime/runtime/NOTICE.txt")
APP_NOTICE = Path("app/src/main/res/raw/native_engine_licenses.txt")
ADAPTER = Path(
    "../third_party/rime/runtime/java/com/opentypeless/ksp004/RimeAdapter.java"
)
JNI_CMAKE = Path("../third_party/rime/runtime/jni/CMakeLists.txt")
JNI = Path("../third_party/rime/runtime/jni/rime_jni.cc")
GRADLE = Path("app/build.gradle.kts")
MAIN = Path("app/src/main/java/com/opentypeless/android/MainActivity.java")
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
TEST = Path(
    "app/src/androidTest/java/com/opentypeless/android/rime/runtime/"
    "RimeNativeRuntimeInstrumentedTest.java"
)

AAR_BYTES = 8_856_597
AAR_SHA256 = "5fce6f0e5356d1f80cc080d8ca7f55e8177caa8cbc28538ebde7e69bd1665d2d"
SOURCE_DIGESTS = {
    BUILD: "aff731dcedca7f0d9dddde920bd3df07758ff6c0aa7a2ead92da07864f5969ec",
    PACKAGE: "5467fef2b188a431f0b2f05d605b188abdf2b23810b37686a63e4ce6a7d566ad",
    PATCH: "323111c408c97c51e88b401eca8902cacfd8f5293efd418472eb0938f15b7c5b",
    NOTICE: "48e9a2f2e0d6da72534275c0ea44605e4c25bcf5ff1349702f8f18807210a707",
    APP_NOTICE: "48e9a2f2e0d6da72534275c0ea44605e4c25bcf5ff1349702f8f18807210a707",
    ADAPTER: "662dadec1c1fb85552f38be7e272243eb4cd97a46ddd90a14dcbf313ceaed386",
    JNI_CMAKE: "85270a6bf8cbdae33ddd0e2994fdde146029b0ea790b6314af0ad08037062ce7",
    JNI: "505bf61b4595445c88b80548ad9c7bb30f7ffcfe98aeae59b8e968f21a1340ac",
}
ENTRIES = {
    "AndroidManifest.xml": (233, None),
    "R.txt": (0, None),
    "classes.jar": (
        8_575,
        "23cb4ebf9a141d196cb90e956a53709cb029b12c958ce0069d58bf0d8ab927e1",
    ),
    "jni/arm64-v8a/libopentypeless_rime.so": (
        39_736,
        "eb68314bbd07a10cdcdb6fcbb158beaec71d24d392f7a6d75c221ac4eed416a3",
    ),
    "jni/arm64-v8a/librime.so": (
        4_381_752,
        "1e94fd8ae942bc6b146ec5febe4c26913c9a5feefd761cd496defd55873e6394",
    ),
    "jni/x86_64/libopentypeless_rime.so": (
        38_744,
        "7718849a0ac5146f63ed4219ca71c82de8122c0a0fbd808490a3ff70b06ac3e2",
    ),
    "jni/x86_64/librime.so": (
        4_384_720,
        "e7252fb56d346a30aee76800b166987bb83e4c36b77d8a1afa44019774a9a7c8",
    ),
    "res/raw/native_engine_notices.txt": (
        6_011,
        "48e9a2f2e0d6da72534275c0ea44605e4c25bcf5ff1349702f8f18807210a707",
    ),
}


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _read(root: Path, relative: Path, violations: list[Violation]) -> bytes:
    path = root / relative
    if not path.is_file() or path.is_symlink():
        violations.append(Violation("RIM002_REQUIRED_FILE", str(relative)))
        return b""
    return path.read_bytes()


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []

    for relative, digest in SOURCE_DIGESTS.items():
        data = _read(root, relative, violations)
        if data and _sha(data) != digest:
            violations.append(Violation("RIM002_SOURCE_IDENTITY", str(relative)))

    aar = _read(root, AAR, violations)
    if aar and (len(aar) != AAR_BYTES or _sha(aar) != AAR_SHA256):
        violations.append(Violation("RIM002_AAR_IDENTITY", str(AAR)))
    if aar:
        try:
            from io import BytesIO
            with zipfile.ZipFile(BytesIO(aar)) as archive:
                names = archive.namelist()
                if set(names) != set(ENTRIES) or len(names) != len(set(names)):
                    violations.append(Violation(
                        "RIM002_AAR_CLOSED_SET", f"entries={sorted(names)}"
                    ))
                for name, (size, digest) in ENTRIES.items():
                    try:
                        data = archive.read(name)
                    except KeyError:
                        continue
                    if len(data) != size or (digest is not None and _sha(data) != digest):
                        violations.append(Violation("RIM002_ENTRY_IDENTITY", name))
                    if name.endswith(".so") and any(marker in data for marker in (
                        b"GNU GENERAL PUBLIC LICENSE", b"rime-lua", b"luaopen_", b"octagram",
                    )):
                        violations.append(Violation("RIM002_NATIVE_SCOPE", name))
                classes = archive.read("classes.jar")
                with zipfile.ZipFile(BytesIO(classes)) as jar:
                    expected_classes = {
                        "com/opentypeless/ksp004/RimeAdapter.class",
                        "com/opentypeless/ksp004/RimeAdapter$RuntimeInfo.class",
                        "com/opentypeless/ksp004/RimeAdapter$RuntimePaths.class",
                        "com/opentypeless/ksp004/RimeAdapter$Snapshot.class",
                    }
                    if set(jar.namelist()) != expected_classes:
                        violations.append(Violation(
                            "RIM002_CLASS_CLOSED_SET", str(sorted(jar.namelist()))
                        ))
                    class_bytes = b"".join(jar.read(name) for name in jar.namelist())
                    if any(token in class_bytes for token in (
                        b"InputConnection", b"EditorTransaction", b"EditorOperation",
                        b"android/content/Context", b"java/net/", b"okhttp",
                    )):
                        violations.append(Violation(
                            "RIM002_CAPABILITY_BOUNDARY", "classes.jar"
                        ))
        except (OSError, zipfile.BadZipFile, KeyError) as error:
            violations.append(Violation("RIM002_AAR_FORMAT", type(error).__name__))

    adapter = _read(root, ADAPTER, violations).decode("utf-8", errors="replace")
    required_adapter = (
        'EXPECTED_VERSION = "1.17.0"',
        'System.loadLibrary("rime")',
        'System.loadLibrary("opentypeless_rime")',
        "public static synchronized RuntimeInfo probe(File rootDirectory)",
        "public static synchronized RuntimeInfo dryDeploy(File rootDirectory)",
        "public static synchronized RimeAdapter open(File rootDirectory, String schemaId)",
        "public static synchronized RimeAdapter open(",
        "File sharedDirectory, File userDirectory, String schemaId",
        "public void synchronizeUserData()",
        "nativeSyncUserData()",
        "public Snapshot processAscii(String input)",
        "public String takePendingCommit()",
        "public void setOption(String optionName, boolean enabled)",
        "nativeSetOption(session, safeOption, enabled)",
        "nativeDeploy()",
        "nativeFinalizeEngine();",
    )
    if any(token not in adapter for token in required_adapter) or any(token in adapter for token in (
        "InputConnection", "EditorTransaction", "EditorOperation", "android.content.Context",
        "SharedPreferences", "java.net.", "okhttp",
    )):
        violations.append(Violation(
            "RIM002_ADAPTER_CONTRACT", "runtime probe gained data/editor/network authority"
        ))

    gradle = _read(root, GRADLE, violations).decode("utf-8", errors="replace")
    if (
        AAR_SHA256 not in gradle
        or 'implementation(files(rimeRuntime))' not in gradle
        or 'rimeRuntime.readBytes()' not in gradle
    ):
        violations.append(Violation("RIM002_GRADLE_PIN", str(GRADLE)))
    main = _read(root, MAIN, violations).decode("utf-8", errors="replace")
    if "readRawText(R.raw.native_engine_licenses)" not in main:
        violations.append(Violation("RIM002_NOTICE_SURFACE", str(MAIN)))
    service = _read(root, SERVICE, violations).decode("utf-8", errors="replace")
    if "RimeAdapter" in service:
        violations.append(Violation(
            "RIM002_NO_RUNTIME_ACTIVATION", "IME service must not activate Rime before RIM-003"
        ))
    test = _read(root, TEST, violations).decode("utf-8", errors="replace")
    for token in (
        "pinnedRuntimeLoadsInitializesReportsVersionAndFinalizes",
        "RimeAdapter.probe(root)",
        'assertEquals("1.17.0", runtime.version())',
        'new File(root, "shared/default.yaml")',
        'new File(root, "user/default.userdb")',
    ):
        if token not in test:
            violations.append(Violation("RIM002_NATIVE_LOAD_TEST", token))
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
    print("RIM-002 pinned native runtime contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
