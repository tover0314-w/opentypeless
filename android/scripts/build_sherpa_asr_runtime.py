#!/usr/bin/env python3
"""Build the minimal sherpa-onnx Android runtime used by OpenTypeless.

The upstream Android AAR enables every sherpa-onnx feature, including TTS and
speaker diarization.  OpenTypeless only ships speech recognition, so this
script rebuilds the pinned source with those features disabled and packages
only the two native libraries needed by the JNI binding.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile


SHERPA_COMMIT = "142807252687d81b40d6315f23470a1512a00de3"
SHERPA_VERSION = "1.13.4"
SHERPA_ARCHIVE_URL = (
    "https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/" + SHERPA_COMMIT
)
SHERPA_ARCHIVE_SHA256 = "f0dc7c9b41b8691313daee671e826eb23946fa1320559a8d37e84f8774af76b2"
UPSTREAM_AAR_URL = (
    "https://jitpack.io/com/github/k2-fsa/sherpa-onnx/"
    + SHERPA_COMMIT
    + "/sherpa-onnx-"
    + SHERPA_COMMIT
    + ".aar"
)
UPSTREAM_AAR_SHA256 = "03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780"
ORT_VERSION = "1.27.0"
ORT_ARCHIVE_URL = (
    "https://github.com/csukuangfj/onnxruntime-libs/releases/download/"
    f"v{ORT_VERSION}/onnxruntime-android-{ORT_VERSION}.zip"
)
ORT_ARCHIVE_SHA256 = "a78f303a26b5e75c84c8b2a97fa2ddb400b2d1b5e069bec19aa229ccd3597fdb"
NDK_REVISION = "27.3.13750724"
PACKAGED_AAR_SHA256 = "35af2790bfcb39a1bfe6d0d495193b7fadc367c5c6f07e5e95996ba210cb9196"
ABIS = ("arm64-v8a", "x86_64")
FIXED_DATE = (1980, 1, 1, 0, 0, 0)
BANNED_NATIVE_MARKERS = (
    b"Java_com_k2fsa_sherpa_onnx_OfflineTts_",
    b"Java_com_k2fsa_sherpa_onnx_OfflineSpeakerDiarization_",
    b"espeak-ng-data",
    b"piper-phonemize",
)
BANNED_HOST_PATH_MARKERS = (b"/private/tmp/", b"/tmp/", b"/Users/")
REQUIRED_NATIVE_MARKERS = (
    b"Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromFile",
    b"OfflineRecognizerSenseVoiceImpl",
)
IGNORED_BROKEN_SOURCE_LINKS = {
    "scripts/go/_internal/vad-spoken-language-identification/main.go":
        "/Users/fangjun/open-source/sherpa-onnx/go-api-examples/"
        "vad-spoken-language-identification/main.go",
    "scripts/go/_internal/vad-spoken-language-identification/run.sh":
        "/Users/fangjun/open-source/sherpa-onnx/go-api-examples/"
        "vad-spoken-language-identification/run.sh",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_hash(path: Path, expected: str, label: str) -> None:
    actual = sha256(path)
    if actual != expected:
        raise RuntimeError(f"{label} SHA-256 mismatch: expected {expected}, got {actual}")


def download(url: str, destination: Path, expected: str, label: str) -> Path:
    if destination.exists():
        require_hash(destination, expected, label)
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "OpenTypeless-build/1"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as sink:
            while chunk := response.read(1024 * 1024):
                sink.write(chunk)
        require_hash(partial, expected, label)
        partial.replace(destination)
    finally:
        if partial.exists():
            partial.unlink()
    return destination


def safe_tar_extract(archive: Path, destination: Path) -> Path:
    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        roots: set[str] = set()
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts:
                raise RuntimeError(f"unsafe source archive member: {member.name}")
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise RuntimeError(f"unsupported source archive member: {member.name}")
            if name.parts:
                roots.add(name.parts[0])
        if len(roots) != 1:
            raise RuntimeError(f"source archive must contain one root, found {sorted(roots)}")
        root = next(iter(roots))
        extractable = []
        for member in members:
            if not (member.issym() or member.islnk()):
                extractable.append(member)
                continue
            target = PurePosixPath(member.linkname)
            if target.is_absolute():
                relative = str(PurePosixPath(member.name).relative_to(root))
                if IGNORED_BROKEN_SOURCE_LINKS.get(relative) == member.linkname:
                    print(f"Ignoring pinned broken source link: {relative}")
                    continue
                raise RuntimeError(f"unsafe absolute link in source archive: {member.name}")
            base = PurePosixPath(member.name).parent if member.issym() else PurePosixPath()
            stack: list[str] = []
            for part in (base / target).parts:
                if part in ("", "."):
                    continue
                if part == "..":
                    if not stack:
                        raise RuntimeError(f"source archive link escapes root: {member.name}")
                    stack.pop()
                else:
                    stack.append(part)
            if not stack or stack[0] != root:
                raise RuntimeError(f"source archive link escapes root: {member.name}")
            extractable.append(member)
        source.extractall(destination, members=extractable)
    return destination / root


def safe_zip_extract(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(archive) as source:
        for info in source.infolist():
            name = PurePosixPath(info.filename)
            if name.is_absolute() or ".." in name.parts:
                raise RuntimeError(f"unsafe ONNX Runtime archive member: {info.filename}")
        source.extractall(destination)


def ndk_revision(ndk: Path) -> str:
    properties = ndk / "source.properties"
    if not properties.is_file():
        raise RuntimeError(f"Android NDK source.properties is missing under {ndk}")
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("Pkg.Revision"):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("Android NDK revision is missing")


def build_abi(source: Path, ort: Path, ndk: Path, work: Path, abi: str, jobs: int) -> Path:
    build = work / f"build-{abi}"
    install = work / f"install-{abi}"
    build.mkdir()
    env = os.environ.copy()
    env["SHERPA_ONNXRUNTIME_LIB_DIR"] = str(ort / "jni" / abi)
    env["SHERPA_ONNXRUNTIME_INCLUDE_DIR"] = str(ort / "headers")
    prefix_flags = " ".join(
        (
            f"-ffile-prefix-map={source}=/src/sherpa-onnx",
            f"-fmacro-prefix-map={source}=/src/sherpa-onnx",
            f"-ffile-prefix-map={build}=/build/sherpa-onnx",
            f"-fmacro-prefix-map={build}=/build/sherpa-onnx",
        )
    )
    # Android computes the linker build ID before install/strip, so it varies
    # with debug-only build paths even when the packaged ELF is otherwise
    # byte-identical.  Omit that note and use the packaged SHA-256 recorded in
    # the AAR provenance as the stable native-artifact identifier.
    deterministic_linker_flags = "-Wl,--build-id=none"
    configure = [
        "cmake", "-S", str(source), "-B", str(build),
        f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_C_FLAGS={prefix_flags}",
        f"-DCMAKE_CXX_FLAGS={prefix_flags}",
        f"-DCMAKE_SHARED_LINKER_FLAGS={deterministic_linker_flags}",
        f"-DCMAKE_INSTALL_PREFIX={install}",
        f"-DANDROID_ABI={abi}",
        "-DANDROID_PLATFORM=android-26",
        "-DBUILD_SHARED_LIBS=ON",
        "-DSHERPA_ONNX_ENABLE_TTS=OFF",
        "-DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF",
        "-DSHERPA_ONNX_ENABLE_BINARY=OFF",
        "-DSHERPA_ONNX_ENABLE_C_API=OFF",
        "-DSHERPA_ONNX_ENABLE_JNI=ON",
        "-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF",
        "-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF",
        "-DSHERPA_ONNX_ENABLE_PYTHON=OFF",
        "-DSHERPA_ONNX_ENABLE_TESTS=OFF",
        "-DSHERPA_ONNX_ENABLE_CHECK=OFF",
        "-DSHERPA_ONNX_ENABLE_QNN=OFF",
        "-DSHERPA_ONNX_ENABLE_RKNN=OFF",
    ]
    subprocess.run(configure, check=True, env=env)
    subprocess.run(
        ["cmake", "--build", str(build), "--parallel", str(jobs), "--target", "install/strip"],
        check=True,
        env=env,
    )
    return install


def verify_install(install: Path, abi: str) -> dict[str, dict[str, object]]:
    result: dict[str, dict[str, object]] = {}
    for name in ("libonnxruntime.so", "libsherpa-onnx-jni.so"):
        path = install / "lib" / name
        if not path.is_file():
            raise RuntimeError(f"{abi} is missing {name}")
        result[name] = {"bytes": path.stat().st_size, "sha256": sha256(path)}
    jni = (install / "lib" / "libsherpa-onnx-jni.so").read_bytes()
    for marker in BANNED_NATIVE_MARKERS:
        if marker in jni:
            raise RuntimeError(f"{abi} JNI unexpectedly contains disabled feature {marker!r}")
    for marker in BANNED_HOST_PATH_MARKERS:
        if marker in jni:
            raise RuntimeError(f"{abi} JNI leaks a non-reproducible host path {marker!r}")
    for marker in REQUIRED_NATIVE_MARKERS:
        if marker not in jni:
            raise RuntimeError(f"{abi} JNI is missing required ASR marker {marker!r}")
    return result


def add_bytes(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, FIXED_DATE)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def package_aar(upstream_aar: Path, installs: dict[str, Path], output: Path, ndk: str) -> str:
    native = {abi: verify_install(installs[abi], abi) for abi in ABIS}
    provenance = {
        "format": 1,
        "purpose": "OpenTypeless ASR-only Android runtime",
        "sherpa_onnx": {"version": SHERPA_VERSION, "commit": SHERPA_COMMIT},
        "onnxruntime_version": ORT_VERSION,
        "android_ndk_revision": ndk,
        "android_platform": 26,
        "abis": list(ABIS),
        "cmake_options": {
            "SHERPA_ONNX_ENABLE_TTS": False,
            "SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION": False,
            "SHERPA_ONNX_ENABLE_C_API": False,
            "SHERPA_ONNX_ENABLE_WEBSOCKET": False,
            "SHERPA_ONNX_ENABLE_JNI": True,
            "REPRODUCIBLE_FILE_PREFIX_MAP": True,
            "REPRODUCIBLE_BUILD_ID_DISABLED": True,
        },
        "inputs": {
            "sherpa_source_sha256": SHERPA_ARCHIVE_SHA256,
            "upstream_aar_sha256": UPSTREAM_AAR_SHA256,
            "onnxruntime_archive_sha256": ORT_ARCHIVE_SHA256,
        },
        "native": native,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".part")
    with zipfile.ZipFile(upstream_aar) as source, zipfile.ZipFile(temporary, "w") as target:
        base_names = [
            "AndroidManifest.xml",
            "R.txt",
            "classes.jar",
            "proguard.txt",
            "META-INF/com/android/build/gradle/aar-metadata.properties",
        ]
        for name in sorted(base_names):
            add_bytes(target, name, source.read(name))
        add_bytes(
            target,
            "META-INF/opentypeless/sherpa-asr-runtime.json",
            (json.dumps(provenance, ensure_ascii=True, indent=2, sort_keys=True) + "\n").encode(),
        )
        for abi in ABIS:
            for name in ("libonnxruntime.so", "libsherpa-onnx-jni.so"):
                add_bytes(target, f"jni/{abi}/{name}", (installs[abi] / "lib" / name).read_bytes())
    temporary.replace(output)
    return sha256(output)


def verify_packaged_aar(path: Path) -> None:
    require_hash(path, PACKAGED_AAR_SHA256, "packaged ASR runtime")
    expected_entries = {
        "AndroidManifest.xml",
        "R.txt",
        "classes.jar",
        "proguard.txt",
        "META-INF/com/android/build/gradle/aar-metadata.properties",
        "META-INF/opentypeless/sherpa-asr-runtime.json",
    }
    for abi in ABIS:
        expected_entries.add(f"jni/{abi}/libonnxruntime.so")
        expected_entries.add(f"jni/{abi}/libsherpa-onnx-jni.so")
    with zipfile.ZipFile(path) as archive:
        actual_entries = set(archive.namelist())
        if actual_entries != expected_entries:
            raise RuntimeError(
                "packaged AAR entries differ: "
                f"missing={sorted(expected_entries - actual_entries)}, "
                f"unexpected={sorted(actual_entries - expected_entries)}"
            )
        provenance = json.loads(
            archive.read("META-INF/opentypeless/sherpa-asr-runtime.json").decode("utf-8")
        )
        if provenance.get("sherpa_onnx", {}).get("commit") != SHERPA_COMMIT:
            raise RuntimeError("packaged AAR has the wrong sherpa-onnx commit")
        if provenance.get("android_ndk_revision") != NDK_REVISION:
            raise RuntimeError("packaged AAR has the wrong Android NDK revision")
        options = provenance.get("cmake_options", {})
        for disabled in (
            "SHERPA_ONNX_ENABLE_TTS",
            "SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION",
            "SHERPA_ONNX_ENABLE_C_API",
            "SHERPA_ONNX_ENABLE_WEBSOCKET",
        ):
            if options.get(disabled) is not False:
                raise RuntimeError(f"packaged AAR did not disable {disabled}")
        for reproducible in (
            "REPRODUCIBLE_FILE_PREFIX_MAP",
            "REPRODUCIBLE_BUILD_ID_DISABLED",
        ):
            if options.get(reproducible) is not True:
                raise RuntimeError(f"packaged AAR did not enable {reproducible}")
        for abi in ABIS:
            for name in ("libonnxruntime.so", "libsherpa-onnx-jni.so"):
                data = archive.read(f"jni/{abi}/{name}")
                declared = provenance["native"][abi][name]
                actual_digest = hashlib.sha256(data).hexdigest()
                if len(data) != declared["bytes"] or actual_digest != declared["sha256"]:
                    raise RuntimeError(f"packaged AAR provenance mismatch for {abi}/{name}")
            jni = archive.read(f"jni/{abi}/libsherpa-onnx-jni.so")
            for marker in BANNED_NATIVE_MARKERS:
                if marker in jni:
                    raise RuntimeError(f"packaged {abi} JNI contains {marker!r}")
            for marker in BANNED_HOST_PATH_MARKERS:
                if marker in jni:
                    raise RuntimeError(f"packaged {abi} JNI leaks host path {marker!r}")
            for marker in REQUIRED_NATIVE_MARKERS:
                if marker not in jni:
                    raise RuntimeError(f"packaged {abi} JNI is missing {marker!r}")


def parse_reused(values: list[str]) -> dict[str, Path]:
    parsed: dict[str, Path] = {}
    for value in values:
        abi, separator, directory = value.partition("=")
        if separator != "=" or abi not in ABIS or not directory:
            raise RuntimeError(f"--reuse-install must be ABI=PATH for {ABIS}: {value}")
        parsed[abi] = Path(directory).resolve()
    if parsed and set(parsed) != set(ABIS):
        raise RuntimeError(f"--reuse-install must provide both {ABIS}")
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", type=Path, help=f"Android NDK r27d ({NDK_REVISION})")
    parser.add_argument("--work-dir", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-aar", type=Path)
    parser.add_argument("--generic-aar", type=Path)
    parser.add_argument("--reuse-install", action="append", default=[], metavar="ABI=PATH")
    parser.add_argument("--jobs", type=int, default=max(1, min(8, os.cpu_count() or 1)))
    args = parser.parse_args()

    if args.verify_aar:
        if args.output or args.generic_aar or args.reuse_install or args.ndk or args.work_dir:
            parser.error("--verify-aar cannot be combined with build arguments")
        verify_packaged_aar(args.verify_aar.resolve())
        print(f"Verified ASR-only runtime: {args.verify_aar}")
        return 0
    if not args.output:
        parser.error("--output is required when building")

    reused = parse_reused(args.reuse_install)
    work = args.work_dir.resolve() if args.work_dir else Path(tempfile.mkdtemp(prefix="opentypeless-sherpa-asr-"))
    if args.work_dir:
        if work.exists() and any(work.iterdir()):
            raise RuntimeError(f"work directory must be empty: {work}")
        work.mkdir(parents=True, exist_ok=True)
    print(f"Build workspace: {work}")

    upstream_aar = args.generic_aar.resolve() if args.generic_aar else download(
        UPSTREAM_AAR_URL, work / "downloads" / "sherpa-upstream.aar", UPSTREAM_AAR_SHA256, "upstream AAR"
    )
    require_hash(upstream_aar, UPSTREAM_AAR_SHA256, "upstream AAR")

    if reused:
        installs = reused
        revision = NDK_REVISION
    else:
        if not args.ndk:
            parser.error("--ndk is required unless --reuse-install is provided")
        ndk = args.ndk.resolve()
        revision = ndk_revision(ndk)
        if revision != NDK_REVISION:
            raise RuntimeError(f"expected Android NDK {NDK_REVISION}, got {revision}")
        source_archive = download(
            SHERPA_ARCHIVE_URL,
            work / "downloads" / "sherpa-source.tar.gz",
            SHERPA_ARCHIVE_SHA256,
            "sherpa source",
        )
        ort_archive = download(
            ORT_ARCHIVE_URL,
            work / "downloads" / "onnxruntime-android.zip",
            ORT_ARCHIVE_SHA256,
            "ONNX Runtime archive",
        )
        source = safe_tar_extract(source_archive, work / "source")
        ort = work / "onnxruntime"
        safe_zip_extract(ort_archive, ort)
        installs = {abi: build_abi(source, ort, ndk, work, abi, args.jobs) for abi in ABIS}

    output = args.output.resolve()
    digest = package_aar(upstream_aar, installs, output, revision)
    print(f"Wrote {output}")
    print(f"SHA-256 {digest}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
