#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
export LANG=C

PACKAGE="com.opentypeless.android"
ADB_BIN="${ADB:-adb}"
SERIAL=""
APK_PATH=""
RUN_SMOKE=false
OUTPUT_DIR="xiaomi15-acceptance-$(date +%Y%m%d-%H%M%S)"

usage() {
  printf '%s\n' \
    "Usage: $0 --serial exact-adb-serial [--adb path/to/adb] [--apk app.apk]" \
    "          [--smoke] [--output directory]" \
    "" \
    "The default run is read-only. --apk installs or updates OpenTypeless; --smoke launches" \
    "its settings Activity. An exact serial is always required; devices are never auto-selected." \
    "No logcat, screenshot, transcript, clipboard, account, or other app data is collected."
}

while (($#)); do
  case "$1" in
    --adb)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      ADB_BIN="$2"
      shift 2
      ;;
    --serial)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      SERIAL="$2"
      shift 2
      ;;
    --apk)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      APK_PATH="$2"
      shift 2
      ;;
    --smoke)
      RUN_SMOKE=true
      shift
      ;;
    --output)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -n "$SERIAL" ]] || { printf 'An exact --serial is required.\n' >&2; usage >&2; exit 2; }
if [[ "$ADB_BIN" == */* ]]; then
  [[ -x "$ADB_BIN" ]] || { printf 'adb is not executable: %s\n' "$ADB_BIN" >&2; exit 1; }
else
  command -v "$ADB_BIN" >/dev/null || { printf 'adb is required.\n' >&2; exit 1; }
fi
if command -v sha256sum >/dev/null; then
  SHA256=(sha256sum)
elif command -v shasum >/dev/null; then
  SHA256=(shasum -a 256)
else
  printf 'sha256sum or shasum is required.\n' >&2
  exit 1
fi
[[ ! -e "$OUTPUT_DIR" ]] || {
  printf 'Refusing to overwrite existing output: %s\n' "$OUTPUT_DIR" >&2
  exit 1
}
[[ ! -e "$OUTPUT_DIR.tar.gz" ]] || {
  printf 'Refusing to overwrite existing archive: %s.tar.gz\n' "$OUTPUT_DIR" >&2
  exit 1
}

[[ "$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null)" == "device" ]] || {
  printf '%s\n' \
    "--serial does not identify an authorized online device." \
    "No fallback device was selected."
  exit 1
}
ADB_CMD=("$ADB_BIN" -s "$SERIAL")
mkdir -p "$OUTPUT_DIR"

device_prop() {
  local label="$1"
  local property="$2"
  local value
  value="$("${ADB_CMD[@]}" shell getprop "$property" | tr -d '\r')"
  printf '%s=%s\n' "$label" "$value" >> "$OUTPUT_DIR/device.properties"
}

SERIAL_SHA256="$(printf '%s' "$SERIAL" | "${SHA256[@]}" | awk '{print $1}')"
printf 'adb_identifier_sha256=%s\n' "$SERIAL_SHA256" > "$OUTPUT_DIR/device.properties"
device_prop manufacturer ro.product.manufacturer
device_prop brand ro.product.brand
device_prop market_name ro.product.marketname
device_prop model ro.product.model
device_prop device ro.product.device
device_prop android_release ro.build.version.release
device_prop android_sdk ro.build.version.sdk
device_prop security_patch ro.build.version.security_patch
device_prop build_incremental ro.build.version.incremental
device_prop build_fingerprint ro.build.fingerprint
device_prop hyperos_version ro.mi.os.version.name

IDENTITY="$(tr '\n' ' ' < "$OUTPUT_DIR/device.properties" | tr '[:upper:]' '[:lower:]')"
if [[ "$IDENTITY" != *xiaomi* \
    || ! "$IDENTITY" =~ xiaomi[[:space:]_-]*15([[:space:]]|$) \
    || "$IDENTITY" =~ xiaomi[[:space:]_-]*15[[:space:]_-]*(pro|ultra|t) ]]; then
  if [[ "${XIAOMI15_CONFIRMED:-0}" != "1" ]]; then
    printf '%s\n' \
      "The attached device does not expose a clear Xiaomi 15 marketing name." \
      "Inspect $OUTPUT_DIR/device.properties. If it is physically the intended Xiaomi 15," \
      "rerun with XIAOMI15_CONFIRMED=1; that override will be recorded." >&2
    exit 1
  fi
  printf 'xiaomi15_identity=manually_confirmed\n' >> "$OUTPUT_DIR/device.properties"
else
  printf 'xiaomi15_identity=property_confirmed\n' >> "$OUTPUT_DIR/device.properties"
fi

if [[ -n "$APK_PATH" ]]; then
  [[ -f "$APK_PATH" ]] || { printf 'APK not found: %s\n' "$APK_PATH" >&2; exit 1; }
  APK_SHA256="$("${SHA256[@]}" "$APK_PATH" | awk '{print $1}')"
  printf '%s  %s\n' "$APK_SHA256" "$(basename "$APK_PATH")" > "$OUTPUT_DIR/apk.sha256"
  "${ADB_CMD[@]}" install -r "$APK_PATH" > "$OUTPUT_DIR/install.txt"
fi

"${ADB_CMD[@]}" shell pm path "$PACKAGE" | tr -d '\r' > "$OUTPUT_DIR/package-path.txt"
grep -q '^package:' "$OUTPUT_DIR/package-path.txt" || {
  printf '%s is not installed on the attached phone.\n' "$PACKAGE" >&2
  exit 1
}

"${ADB_CMD[@]}" shell dumpsys package "$PACKAGE" \
  | tr -d '\r' \
  | grep -E 'versionCode=|versionName=|targetSdk=|firstInstallTime=|lastUpdateTime=|android.permission.RECORD_AUDIO' \
  > "$OUTPUT_DIR/package.txt" || true
"${ADB_CMD[@]}" shell cmd appops get "$PACKAGE" RECORD_AUDIO \
  | tr -d '\r' > "$OUTPUT_DIR/microphone-appop.txt" || true

DEFAULT_IME="$("${ADB_CMD[@]}" shell settings get secure default_input_method | tr -d '\r')"
ENABLED_IMES="$("${ADB_CMD[@]}" shell settings get secure enabled_input_methods | tr -d '\r')"
AVAILABLE_IMES="$("${ADB_CMD[@]}" shell ime list -s | tr -d '\r')"
{
  [[ "$DEFAULT_IME" == "$PACKAGE/"* ]] \
    && printf 'opentypeless_is_default=true\n' \
    || printf 'opentypeless_is_default=false\n'
  [[ "$ENABLED_IMES" == *"$PACKAGE/"* ]] \
    && printf 'opentypeless_is_enabled=true\n' \
    || printf 'opentypeless_is_enabled=false\n'
  [[ "$AVAILABLE_IMES" == *"$PACKAGE/"* ]] \
    && printf 'opentypeless_is_registered=true\n' \
    || printf 'opentypeless_is_registered=false\n'
} > "$OUTPUT_DIR/ime.txt"

VOICE_COMPONENT="$("${ADB_CMD[@]}" shell settings get secure voice_recognition_service | tr -d '\r')"
printf 'voice_recognition_service=%s\n' "$VOICE_COMPONENT" > "$OUTPUT_DIR/speech-route.txt"
VOICE_PACKAGE="${VOICE_COMPONENT%%/*}"
if [[ -n "$VOICE_PACKAGE" && "$VOICE_PACKAGE" != "null" ]]; then
  "${ADB_CMD[@]}" shell dumpsys package "$VOICE_PACKAGE" \
    | tr -d '\r' \
    | grep -E 'versionCode=|versionName=|targetSdk=' \
    >> "$OUTPUT_DIR/speech-route.txt" || true
fi

{
  printf 'low_power='
  "${ADB_CMD[@]}" shell settings get global low_power | tr -d '\r'
  printf 'process_id='
  "${ADB_CMD[@]}" shell pidof "$PACKAGE" | tr -d '\r' || true
} > "$OUTPUT_DIR/runtime.txt"
"${ADB_CMD[@]}" shell dumpsys meminfo "$PACKAGE" \
  | tr -d '\r' \
  | grep -E 'TOTAL PSS:|TOTAL RSS:|App Summary|Java Heap:|Native Heap:|Code:|Stack:|Graphics:|Private Other:|System:' \
  > "$OUTPUT_DIR/memory.txt" || true

if [[ "$RUN_SMOKE" == true ]]; then
  "${ADB_CMD[@]}" shell am start -W -n "$PACKAGE/.MainActivity" \
    | tr -d '\r' > "$OUTPUT_DIR/activity-smoke.txt"
fi

printf 'id\tstatus\tevidence\tnotes\n' > "$OUTPUT_DIR/manual-results.tsv"
for id in \
  XM-P0-01 XM-P0-02 XM-P0-03 XM-P0-04 XM-P0-05 XM-P0-06 XM-P0-07 \
  XM-P0-08 XM-P0-09 XM-P0-10 XM-P0-11 XM-P0-12 XM-P0-13 XM-P0-14 \
  XM-P0-15 XM-P0-16 XM-P0-17 XM-P0-18 XM-P0-19; do
  printf '%s\tPENDING\t\t\n' "$id" >> "$OUTPUT_DIR/manual-results.tsv"
done

{
  printf '%s\n' \
    "OpenTypeless Xiaomi 15 acceptance evidence" \
    "" \
    "This bundle intentionally excludes logcat, screenshots, transcripts, clipboard contents," \
    "accounts, and data from other apps. Complete manual-results.tsv using the scenarios in" \
    "docs/2026-08-11-xiaomi15-p0-acceptance.md. Use only dedicated test text." \
    "Run the checked-in benchmarks/mobile_voice corpus and Voice Lab. Record 20 short" \
    "utterances per shipping route in a copy of android/tools/latency-template.csv, then run" \
    "android/tools/summarize-latency.py on that copy."
} > "$OUTPUT_DIR/README.txt"

(
  cd "$OUTPUT_DIR"
  while IFS= read -r -d '' evidence; do
    "${SHA256[@]}" "$evidence"
  done < <(find . -maxdepth 1 -type f ! -name checksums.sha256 -print0)
) > "$OUTPUT_DIR/checksums.sha256"
tar -czf "$OUTPUT_DIR.tar.gz" \
  -C "$(dirname "$OUTPUT_DIR")" \
  -- "$(basename "$OUTPUT_DIR")"

printf 'Acceptance evidence created:\n  %s\n  %s.tar.gz\n' "$OUTPUT_DIR" "$OUTPUT_DIR"
printf 'Physical scenarios remain PENDING until manual-results.tsv and latency evidence are completed.\n'
