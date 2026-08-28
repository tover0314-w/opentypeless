#!/usr/bin/env bash
set -euo pipefail

wrapper_dir="$(cd "$(dirname "$0")" && pwd)"
real_plugin="$wrapper_dir/.opentypeless-linuxdeploy-plugin-appimage-real"
appdir=""

if [[ ! -x "$real_plugin" ]]; then
  echo "Real linuxdeploy AppImage output plugin is missing: $real_plugin" >&2
  exit 127
fi

arguments=("$@")
for ((index = 0; index < ${#arguments[@]}; index++)); do
  case "${arguments[$index]}" in
    --appdir)
      if ((index + 1 >= ${#arguments[@]})); then
        echo "--appdir requires a value" >&2
        exit 2
      fi
      appdir="${arguments[$((index + 1))]}"
      ;;
    --appdir=*) appdir="${arguments[$index]#--appdir=}" ;;
  esac
done

if [[ -n "$appdir" ]]; then
  if [[ ! -d "$appdir" ]]; then
    echo "linuxdeploy AppDir does not exist: $appdir" >&2
    exit 2
  fi

  find "$appdir" \( -type f -o -type l \) -name 'libwayland-client.so*' -delete
  if find "$appdir" \( -type f -o -type l \) -name 'libwayland-client.so*' -print -quit | grep -q .; then
    echo "Failed to remove the bundled Wayland client from $appdir" >&2
    exit 1
  fi
  echo "Removed bundled Wayland client libraries before AppImage creation."
fi

exec "$real_plugin" "$@"
