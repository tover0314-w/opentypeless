#!/usr/bin/env bash
set -euo pipefail

linuxdeploy_arch="${1:-}"
case "$linuxdeploy_arch" in
  x86_64 | aarch64) ;;
  *)
    echo "Unsupported linuxdeploy architecture: ${linuxdeploy_arch:-<empty>}" >&2
    exit 2
    ;;
esac

cache_root="${XDG_CACHE_HOME:-${HOME:?HOME is required}/.cache}/tauri"
wrapper_path="$cache_root/linuxdeploy-${linuxdeploy_arch}.AppImage"
real_path="$cache_root/linuxdeploy-real-${linuxdeploy_arch}.AppImage"
appimage_plugin_path="$cache_root/linuxdeploy-plugin-appimage.AppImage"
real_appimage_plugin_path="$cache_root/.opentypeless-linuxdeploy-plugin-appimage-real"
repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
source_path="$repository_root/.github/scripts/linuxdeploy-exclude-wrapper.rs"
appimage_plugin_wrapper_source="$repository_root/.github/scripts/linuxdeploy-plugin-appimage-exclude-wrapper.sh"

mkdir -p "$cache_root"
curl --fail --location --retry 3 \
  --output "$real_path" \
  "https://github.com/tauri-apps/binary-releases/releases/download/linuxdeploy/linuxdeploy-${linuxdeploy_arch}.AppImage"
chmod 0755 "$real_path"

curl --fail --location --retry 3 \
  --output "$real_appimage_plugin_path" \
  "https://github.com/linuxdeploy/linuxdeploy-plugin-appimage/releases/download/continuous/linuxdeploy-plugin-appimage-${linuxdeploy_arch}.AppImage"
chmod 0755 "$real_appimage_plugin_path"
cp "$appimage_plugin_wrapper_source" "$appimage_plugin_path"
chmod 0755 "$appimage_plugin_path"

rustc --edition=2021 -C opt-level=s "$source_path" -o "$wrapper_path"
chmod 0755 "$wrapper_path"

if ! file "$wrapper_path" | grep -q 'ELF'; then
  echo "linuxdeploy exclusion wrapper is not an ELF executable" >&2
  exit 1
fi

echo "Prepared linuxdeploy wrappers for $linuxdeploy_arch with the Wayland client exclusion."
