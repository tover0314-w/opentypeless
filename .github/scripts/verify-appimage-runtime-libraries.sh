#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "Usage: $0 <AppImage path>" >&2
  exit 2
fi

appimage_directory="$(cd "$(dirname "$1")" && pwd)"
appimage_path="$appimage_directory/$(basename "$1")"
extract_root="$(mktemp -d)"
trap 'rm -rf -- "$extract_root"' EXIT
cd "$extract_root"

"$appimage_path" --appimage-extract >/dev/null
bundled_wayland_client="$(
  find squashfs-root \( -type f -o -type l \) \
    -name 'libwayland-client.so*' -print -quit
)"
if [[ -n "$bundled_wayland_client" ]]; then
  echo "AppImage bundles $bundled_wayland_client; this conflicts with host EGL Wayland drivers." >&2
  exit 1
fi
