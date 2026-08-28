#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT

case "$(uname -m)" in
  x86_64) linuxdeploy_arch="x86_64" ;;
  aarch64 | arm64) linuxdeploy_arch="aarch64" ;;
  *)
    echo "Unsupported test architecture: $(uname -m)" >&2
    exit 2
    ;;
esac

wrapper_source="$repository_root/.github/scripts/linuxdeploy-exclude-wrapper.rs"
wrapper_path="$test_root/linuxdeploy-${linuxdeploy_arch}.AppImage"
real_path="$test_root/linuxdeploy-real-${linuxdeploy_arch}.AppImage"
unit_test_path="$test_root/linuxdeploy-wrapper-tests"
forwarded_arguments="$test_root/forwarded-arguments.txt"
appimage_plugin_wrapper_source="$repository_root/.github/scripts/linuxdeploy-plugin-appimage-exclude-wrapper.sh"
appimage_plugin_wrapper="$test_root/linuxdeploy-plugin-appimage.AppImage"
real_appimage_plugin="$test_root/.opentypeless-linuxdeploy-plugin-appimage-real"
appimage_plugin_arguments="$test_root/appimage-plugin-arguments.txt"

rustc --edition=2021 --test "$wrapper_source" -o "$unit_test_path"
"$unit_test_path"
rustc --edition=2021 "$wrapper_source" -o "$wrapper_path"

# Tauri clears three bytes in the AppImage identification area. They are
# padding bytes in the ELF wrapper, so simulate that mutation before execution.
if [[ "$(uname -s)" == "Linux" ]]; then
  dd if=/dev/zero bs=1 count=3 seek=8 conv=notrunc of="$wrapper_path" status=none
fi

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf '\''%s\n'\'' "$@" > "$OPENTYPELESS_LINUXDEPLOY_TEST_OUTPUT"' \
  > "$real_path"
chmod 0755 "$real_path" "$wrapper_path"

OPENTYPELESS_LINUXDEPLOY_TEST_OUTPUT="$forwarded_arguments" \
  "$wrapper_path" --appdir OpenTypeless.AppDir --plugin gtk

expected_arguments=$'--appdir\nOpenTypeless.AppDir\n--plugin\ngtk\n--exclude-library\nlibwayland-client.so*'
if [[ "$(cat "$forwarded_arguments")" != "$expected_arguments" ]]; then
  echo "linuxdeploy wrapper did not preserve arguments and append the exclusion." >&2
  exit 1
fi

cp "$appimage_plugin_wrapper_source" "$appimage_plugin_wrapper"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf '\''%s\n'\'' "$@" > "$OPENTYPELESS_APPIMAGE_PLUGIN_TEST_OUTPUT"' \
  > "$real_appimage_plugin"
chmod 0755 "$appimage_plugin_wrapper" "$real_appimage_plugin"

fake_appdir="$test_root/OpenTypeless.AppDir"
mkdir -p "$fake_appdir/usr/lib"
touch "$fake_appdir/usr/lib/libwayland-client.so.0"
touch "$fake_appdir/usr/lib/libunrelated.so.1"
OPENTYPELESS_APPIMAGE_PLUGIN_TEST_OUTPUT="$appimage_plugin_arguments" \
  "$appimage_plugin_wrapper" --appdir "$fake_appdir"

if find "$fake_appdir" \( -type f -o -type l \) -name 'libwayland-client.so*' -print -quit | grep -q .; then
  echo "AppImage output wrapper left a bundled Wayland client in the AppDir." >&2
  exit 1
fi
if [[ ! -f "$fake_appdir/usr/lib/libunrelated.so.1" ]]; then
  echo "AppImage output wrapper removed an unrelated library." >&2
  exit 1
fi
expected_plugin_arguments=$'--appdir\n'"$fake_appdir"
if [[ "$(cat "$appimage_plugin_arguments")" != "$expected_plugin_arguments" ]]; then
  echo "AppImage output wrapper did not preserve plugin arguments." >&2
  exit 1
fi

fake_appimage="$test_root/OpenTypeless.AppImage"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'mkdir -p squashfs-root/usr/lib' \
  'if [[ "${FAKE_BUNDLED_WAYLAND:-0}" == "1" ]]; then' \
  '  touch squashfs-root/usr/lib/libwayland-client.so.0' \
  'fi' \
  > "$fake_appimage"
chmod 0755 "$fake_appimage"

"$repository_root/.github/scripts/verify-appimage-runtime-libraries.sh" "$fake_appimage"
if FAKE_BUNDLED_WAYLAND=1 \
  "$repository_root/.github/scripts/verify-appimage-runtime-libraries.sh" "$fake_appimage" \
  >/dev/null 2>&1; then
  echo "AppImage verification accepted a bundled Wayland client." >&2
  exit 1
fi
