#!/usr/bin/env bash
set -euo pipefail

for name in TAG_NAME LINUX_ARCH LINUX_GPG_KEY_ID LINUX_GPG_PASSPHRASE; do
  if [[ -z "${!name:-}" ]]; then
    echo "::error::$name is required to upload Linux verification artifacts."
    exit 1
  fi
done

case "$LINUX_ARCH" in
  x86_64)
    bundle_dir="src-tauri/target/release/bundle"
    ;;
  aarch64)
    bundle_dir="src-tauri/target/aarch64-unknown-linux-gnu/release/bundle"
    ;;
  *)
    echo "::error::Unsupported Linux release architecture: $LINUX_ARCH"
    exit 1
    ;;
esac

verification_dir="release-verification/linux-${LINUX_ARCH}"
mkdir -p "$verification_dir"
repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
contents_file="$verification_dir/APPIMAGE-CONTENTS-linux-${LINUX_ARCH}.txt"
: > "$contents_file"

mapfile -d '' artifacts < <(
  find "$bundle_dir" -type f \
    \( -name '*.AppImage' -o -name '*.deb' -o -name '*.rpm' \) \
    -print0 | sort -z
)

if (( ${#artifacts[@]} == 0 )); then
  echo "::error::No Linux release artifacts were found."
  exit 1
fi

sha_file="$verification_dir/SHA256SUMS-linux-${LINUX_ARCH}.txt"
: > "$sha_file"

for artifact in "${artifacts[@]}"; do
  sha256sum "$artifact" | sed "s#  .*#  $(basename "$artifact")#" >> "$sha_file"

  signature_path="$verification_dir/$(basename "$artifact").asc"
  gpg --batch --yes --pinentry-mode loopback \
    --passphrase "$LINUX_GPG_PASSPHRASE" \
    --local-user "$LINUX_GPG_KEY_ID" \
    --armor --detach-sign \
    --output "$signature_path" \
    "$artifact"
done

public_key_path="$verification_dir/OpenTypeless-Linux-${LINUX_ARCH}-GPG-KEY.asc"
gpg --armor --export "$LINUX_GPG_KEY_ID" > "$public_key_path"

if compgen -G "$bundle_dir/appimage/*.AppImage" >/dev/null; then
  for appimage in "$bundle_dir"/appimage/*.AppImage; do
    chmod +x "$appimage"
    if ! ./.github/scripts/verify-appimage-runtime-libraries.sh "$appimage"; then
      echo "::error::Linux AppImage runtime-library verification failed."
      exit 1
    fi
    "$appimage" --appimage-signature >/dev/null

    extract_root="$(mktemp -d)"
    if ! (
      cd "$extract_root"
      "$repository_root/$appimage" --appimage-extract >/dev/null

      printf 'AppImage\t%s\n' "$(basename "$appimage")"
      while IFS= read -r -d '' bundled_path; do
        relative_path="${bundled_path#squashfs-root/}"
        if [[ -L "$bundled_path" ]]; then
          printf 'SYMLINK\t%s\t%s\n' "$relative_path" "$(readlink "$bundled_path")"
        else
          digest="$(sha256sum "$bundled_path" | cut -d ' ' -f 1)"
          printf 'SHA256\t%s\t%s\n' "$digest" "$relative_path"
        fi
      done < <(
        find squashfs-root \( -type f -o -type l \) -print0 | sort -z
      )

      for legal_file in \
        LICENSE \
        THIRD_PARTY_NOTICES.md \
        THIRD_PARTY_INVENTORY.md \
        THIRD_PARTY_LICENSES.txt; do
        mapfile -d '' matches < <(
          find squashfs-root -type f -name "$legal_file" -print0
        )
        if (( ${#matches[@]} != 1 )); then
          echo "::error::Expected exactly one $legal_file in $(basename "$appimage"), found ${#matches[@]}." >&2
          exit 1
        fi
        if ! cmp "$repository_root/$legal_file" "${matches[0]}"; then
          echo "::error::$legal_file in $(basename "$appimage") differs from the committed file." >&2
          exit 1
        fi
      done
    ) >> "$contents_file"; then
      rm -rf -- "$extract_root"
      exit 1
    fi
    rm -rf -- "$extract_root"
  done
else
  echo "::error::No AppImage was found for the Linux release inventory." >&2
  exit 1
fi

sha256sum "$contents_file" |
  sed "s#  .*#  $(basename "$contents_file")#" >> "$sha_file"

if compgen -G "$bundle_dir/rpm/*.rpm" >/dev/null; then
  if sudo rpm --import "$public_key_path"; then
    rpm --checksig -v "$bundle_dir"/rpm/*.rpm
  else
    echo "::warning::rpm could not import the exported public key; uploading detached GPG verification artifacts without rpm database verification."
  fi
fi

gpg --batch --yes --pinentry-mode loopback \
  --passphrase "$LINUX_GPG_PASSPHRASE" \
  --local-user "$LINUX_GPG_KEY_ID" \
  --armor --detach-sign \
  --output "${sha_file}.asc" \
  "$sha_file"

gh release upload "$TAG_NAME" "$verification_dir"/* \
  --repo dengxuezhao/opentypeless \
  --clobber
