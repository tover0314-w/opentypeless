#!/bin/sh
# SPDX-License-Identifier: MIT
# Copyright (c) 2025 OpenTypeless Contributors

set -eu

NATIVE_ROOT=${NATIVE_ROOT:?NATIVE_ROOT must contain artifacts/<ABI>}
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}
OUTPUT_AAR=${OUTPUT_AAR:?OUTPUT_AAR is required}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORK_DIR=${WORK_DIR:?WORK_DIR must be an empty task-specific directory}
ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"

test -f "$ANDROID_JAR"
test ! -e "$WORK_DIR"
mkdir -p "$WORK_DIR/classes" "$WORK_DIR/aar/jni/arm64-v8a" \
  "$WORK_DIR/aar/jni/x86_64" "$WORK_DIR/aar/res/raw"

require_file() {
  path=$1
  bytes=$2
  digest=$3
  test "$(wc -c < "$path" | tr -d ' ')" = "$bytes"
  test "$(shasum -a 256 "$path" | awk '{print $1}')" = "$digest"
}

require_file "$NATIVE_ROOT/artifacts/arm64-v8a/librime.so" 4381752 \
  1e94fd8ae942bc6b146ec5febe4c26913c9a5feefd761cd496defd55873e6394
require_file "$NATIVE_ROOT/artifacts/arm64-v8a/libopentypeless_rime.so" 39736 \
  eb68314bbd07a10cdcdb6fcbb158beaec71d24d392f7a6d75c221ac4eed416a3
require_file "$NATIVE_ROOT/artifacts/x86_64/librime.so" 4384720 \
  e7252fb56d346a30aee76800b166987bb83e4c36b77d8a1afa44019774a9a7c8
require_file "$NATIVE_ROOT/artifacts/x86_64/libopentypeless_rime.so" 38744 \
  7718849a0ac5146f63ed4219ca71c82de8122c0a0fbd808490a3ff70b06ac3e2

javac --release 17 -cp "$ANDROID_JAR" -d "$WORK_DIR/classes" \
  "$SCRIPT_DIR/java/com/opentypeless/ksp004/RimeAdapter.java"

cp "$NATIVE_ROOT/artifacts/arm64-v8a/librime.so" "$WORK_DIR/aar/jni/arm64-v8a/"
cp "$NATIVE_ROOT/artifacts/arm64-v8a/libopentypeless_rime.so" "$WORK_DIR/aar/jni/arm64-v8a/"
cp "$NATIVE_ROOT/artifacts/x86_64/librime.so" "$WORK_DIR/aar/jni/x86_64/"
cp "$NATIVE_ROOT/artifacts/x86_64/libopentypeless_rime.so" "$WORK_DIR/aar/jni/x86_64/"
cp "$SCRIPT_DIR/NOTICE.txt" "$WORK_DIR/aar/res/raw/native_engine_notices.txt"

printf '%s\n' '<?xml version="1.0" encoding="utf-8"?>' \
  '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.opentypeless.rime.runtime">' \
  '  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />' \
  '</manifest>' \
  > "$WORK_DIR/aar/AndroidManifest.xml"
: > "$WORK_DIR/aar/R.txt"

python3 - "$WORK_DIR/classes" "$WORK_DIR/aar/classes.jar" <<'PY'
from pathlib import Path
import sys, zipfile
root, output = Path(sys.argv[1]), Path(sys.argv[2])
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(root.rglob('*')):
        if path.is_file():
            info = zipfile.ZipInfo(path.relative_to(root).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes(), compresslevel=9)
PY

mkdir -p "$(dirname -- "$OUTPUT_AAR")"
python3 - "$WORK_DIR/aar" "$OUTPUT_AAR" <<'PY'
from pathlib import Path
import sys, zipfile
root, output = Path(sys.argv[1]), Path(sys.argv[2])
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(root.rglob('*')):
        if path.is_file():
            info = zipfile.ZipInfo(path.relative_to(root).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_STORED if path.suffix == '.so' else zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes(), compresslevel=9)
PY

unzip -t "$OUTPUT_AAR" >/dev/null
shasum -a 256 "$OUTPUT_AAR"
