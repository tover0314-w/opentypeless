#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C
export LANG=C

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"
POLICY_TMP="$(mktemp -d "${TMPDIR:-/tmp}/opentypeless-android-policy.XXXXXX")"
STAGE="${1:-all}"

if [[ "$#" -gt 1 ]]; then
  printf '%s\n' 'Usage: scripts/verify_android.sh [all|preflight|unit|lint|assemble|metrics|instrumentation]' >&2
  exit 2
fi

cleanup() {
  rm -f -- \
    "$POLICY_TMP/collector-policy.txt" \
    "$POLICY_TMP/collector-must-not-run"
  rmdir -- "$POLICY_TMP" 2>/dev/null || true
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Required command is unavailable: %s\n' "$1" >&2
    exit 1
  }
}

require_command bash
require_command python3

run_rime_working_tree_gate() {
  python3 "$REPO_ROOT/scripts/verify_rime_resource_policy.py" verify \
    --repo-root "$REPO_ROOT"
}

run_rime_working_tree_gate

require_command java

java -version >/dev/null 2>&1 || {
  printf '%s\n' 'A working Java runtime is required. Set JAVA_HOME to JDK 17.' >&2
  exit 1
}

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f "$ANDROID_DIR/local.properties" ]]; then
  printf '%s\n' \
    'Android SDK location is required. Set ANDROID_HOME or ANDROID_SDK_ROOT,' \
    "or create $ANDROID_DIR/local.properties with sdk.dir." >&2
  exit 1
fi

[[ -x "$ANDROID_DIR/gradlew" ]] || {
  printf 'Gradle wrapper is missing or not executable: %s\n' "$ANDROID_DIR/gradlew" >&2
  exit 1
}

run_preflight() {
  printf '%s\n' 'Running Android Python tests and static checks...'
  python3 "$REPO_ROOT/scripts/verify_github_actions_pinning.py" \
    --repo-root "$REPO_ROOT"
  python3 "$REPO_ROOT/scripts/verify_android_ci_reporting.py" \
    --repo-root "$REPO_ROOT"
  python3 "$REPO_ROOT/scripts/verify_github_branch_protection.py" \
    --repo-root "$REPO_ROOT"
  python3 "$REPO_ROOT/scripts/verify_agents.py" \
    --repo-root "$REPO_ROOT"
  python3 "$REPO_ROOT/scripts/verify_compatibility.py" \
    --repo-root "$REPO_ROOT"
  python3 -m unittest discover -s "$REPO_ROOT/scripts" -p 'test_*.py' -v
  python3 "$ANDROID_DIR/scripts/verify_android_sdk_pinning.py" \
    --repo-root "$REPO_ROOT"
  python3 "$ANDROID_DIR/architecture-tests/editor_write_ci_gate.py" \
    --repo-root "$REPO_ROOT"
  python3 -m unittest discover -s "$ANDROID_DIR/scripts" -p 'test_*.py' -v
  python3 -m unittest discover -s "$ANDROID_DIR/architecture-tests" -p 'test_*.py' -v
  python3 "$ANDROID_DIR/architecture-tests/architecture_contracts.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/keyboard_shell_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/latin_keyboard_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/symbol_keyboard_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/field_keyboard_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/keyboard_toolbar_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/candidate_bar_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/keyboard_switching_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/rime_engine_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/rime_runtime_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/rime_import_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/rime_userdata_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/rime_voice_conflict_contract.py" \
    "$ANDROID_DIR/app/src"
  python3 "$ANDROID_DIR/architecture-tests/editor_race_matrix_contract.py" \
    "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/sensitive_field_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/sensitive_toolbar_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/clipboard_panel_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/emoji_panel_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/architecture-tests/privacy_policy_contract.py" \
    --android-root "$ANDROID_DIR"
  python3 "$ANDROID_DIR/scripts/verify_keyboard_shell_manifest.py" \
    --manifest "$ANDROID_DIR/app/src/main/AndroidManifest.xml" \
    --rules "$ANDROID_DIR/app/src/main/res/xml/data_extraction_rules.xml" \
    --variant source
  python3 -m unittest discover -s "$REPO_ROOT/benchmarks/mobile_voice" -p 'test_*.py' -v
  python3 -m py_compile "$REPO_ROOT"/benchmarks/mobile_voice/*.py
  bash -n "$ANDROID_DIR/tools/collect-xiaomi15-acceptance.sh"

  set +e
  "$ANDROID_DIR/tools/collect-xiaomi15-acceptance.sh" \
    --output "$POLICY_TMP/collector-must-not-run" \
    >"$POLICY_TMP/collector-policy.txt" 2>&1
  collector_status=$?
  set -e
  [[ "$collector_status" -eq 2 ]] || {
    printf 'Collector policy check returned %s instead of 2.\n' "$collector_status" >&2
    exit 1
  }
  [[ ! -e "$POLICY_TMP/collector-must-not-run" ]] || {
    printf '%s\n' 'Collector ran without an exact device serial.' >&2
    exit 1
  }
  grep -q 'exact --serial is required' "$POLICY_TMP/collector-policy.txt" || {
    printf '%s\n' 'Collector did not report the required exact-serial policy.' >&2
    exit 1
  }

  printf '%s\n' 'Verifying the pinned Sherpa ASR runtime...'
  python3 "$ANDROID_DIR/scripts/build_sherpa_asr_runtime.py" \
    --verify-aar "$ANDROID_DIR/app/libs/sherpa-onnx-asr-1.13.4.aar"
}

run_gradle() {
  (
  cd "$ANDROID_DIR"
  ./gradlew \
    --no-daemon \
    --dependency-verification=strict \
    "$@"
  )
}

run_metrics() {
  python3 "$REPO_ROOT/scripts/collect_engineering_metrics.py" \
    --repo-root "$REPO_ROOT" \
    --output "$ANDROID_DIR/build/reports/engineering-metrics/engineering-metrics.json"
}

run_rime_built_apk_gate() {
  python3 "$REPO_ROOT/scripts/verify_rime_resource_policy.py" scan-apk \
    --repo-root "$REPO_ROOT" \
    --profile product \
    --apk "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" \
    --apk "$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" \
    --apk "$ANDROID_DIR/test-host/build/outputs/apk/debug/test-host-debug.apk"
  python3 "$REPO_ROOT/scripts/verify_rime_resource_policy.py" scan-apk \
    --repo-root "$REPO_ROOT" \
    --profile test \
    --apk "$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
    --apk "$ANDROID_DIR/test-host/build/outputs/apk/androidTest/debug/test-host-debug-androidTest.apk"
}

case "$STAGE" in
  all)
    run_preflight
    printf '%s\n' \
      'Running clean Android tests, compiled architecture checks, lint, and assemblies with strict dependency verification...'
    run_gradle \
      clean \
      :architecture-gate:check \
      testDebugUnitTest \
      lintRelease \
      assembleDebug \
      assembleRelease \
      assembleDebugAndroidTest
    run_rime_built_apk_gate
    run_metrics
    ;;
  preflight)
    run_preflight
    ;;
  unit)
    printf '%s\n' 'Running clean Android JVM and architecture tests...'
    run_gradle clean :architecture-gate:check testDebugUnitTest
    ;;
  lint)
    printf '%s\n' 'Running Android release lint...'
    run_gradle lintRelease
    ;;
  assemble)
    printf '%s\n' 'Assembling Android debug, release, and instrumentation APKs...'
    run_gradle assembleDebug assembleRelease assembleDebugAndroidTest
    run_rime_built_apk_gate
    ;;
  metrics)
    printf '%s\n' 'Generating advisory Android engineering metrics...'
    run_metrics
    ;;
  instrumentation)
    printf '%s\n' 'Running Android connected instrumentation tests...'
    run_gradle connectedDebugAndroidTest
    ;;
  *)
    printf 'Unknown Android verification stage: %s\n' "$STAGE" >&2
    printf '%s\n' 'Usage: scripts/verify_android.sh [all|preflight|unit|lint|assemble|metrics|instrumentation]' >&2
    exit 2
    ;;
esac
