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

gpg --batch --yes --pinentry-mode loopback \
  --passphrase "$LINUX_GPG_PASSPHRASE" \
  --local-user "$LINUX_GPG_KEY_ID" \
  --armor --detach-sign \
  --output "${sha_file}.asc" \
  "$sha_file"

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
  done
fi

if compgen -G "$bundle_dir/rpm/*.rpm" >/dev/null; then
  if sudo rpm --import "$public_key_path"; then
    rpm --checksig -v "$bundle_dir"/rpm/*.rpm
  else
    echo "::warning::rpm could not import the exported public key; uploading detached GPG verification artifacts without rpm database verification."
  fi
fi

gh release upload "$TAG_NAME" "$verification_dir"/* \
  --repo tover0314-w/opentypeless \
  --clobber
