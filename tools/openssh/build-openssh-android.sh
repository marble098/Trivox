#!/usr/bin/env bash
set -euo pipefail

# Build the OpenSSH client suite and all runtime dependencies through Termux's
# Android package build system, using Trivox's own package name/data prefix.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${RUNNER_TEMP:-$ROOT/.openssh-build}"
TERMUX_DIR="$WORK/termux-packages"
PACKAGE_NAME="${TRIVOX_PACKAGE_NAME:-com.trivox.client}"
OUTPUT="$ROOT/app/src/main/assets/openssh"
VERSION_FILE="$OUTPUT/version.txt"

for command in git python3 docker dpkg-deb install find; do
  command -v "$command" >/dev/null || {
    echo "Missing host command: $command" >&2
    exit 1
  }
done

rm -rf "$TERMUX_DIR"
git clone --depth 1 https://github.com/termux/termux-packages.git "$TERMUX_DIR"
python3 "$ROOT/tools/openssh/prepare-termux-properties.py" \
  "$TERMUX_DIR/properties.sh" "$PACKAGE_NAME"

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"
OPENSSH_VERSION=""

for arch in aarch64 arm x86_64; do
  case "$arch" in
    aarch64) abi=arm64-v8a ;;
    arm) abi=armeabi-v7a ;;
    x86_64) abi=x86_64 ;;
  esac

  rm -rf "$TERMUX_DIR/output" "$TERMUX_DIR/.termux-build"
  (
    cd "$TERMUX_DIR"
    TERMUX_PKG_MAKE_PROCESSES="${TERMUX_PKG_MAKE_PROCESSES:-2}" \
      ./scripts/run-docker.sh ./build-package.sh -a "$arch" openssh
  )

  openssh_deb="$(find "$TERMUX_DIR/output" -maxdepth 1 -type f \
    -name 'openssh_*.deb' -print -quit)"
  [[ -n "$openssh_deb" ]] || {
    echo "OpenSSH package was not produced for $arch" >&2
    exit 1
  }
  if [[ -z "$OPENSSH_VERSION" ]]; then
    OPENSSH_VERSION="$(dpkg-deb -f "$openssh_deb" Version)"
  fi

  extract="$WORK/extract-$arch"
  rm -rf "$extract"
  mkdir -p "$extract"

  # build-package builds dependency packages into output. Extracting all of
  # them and copying only the runtime prefix avoids a brittle hand-written
  # dependency list while keeping build-time files out of the APK.
  while IFS= read -r -d '' deb; do
    dpkg-deb -x "$deb" "$extract"
  done < <(find "$TERMUX_DIR/output" -maxdepth 1 -type f -name '*.deb' -print0)

  prefix="$extract/data/data/$PACKAGE_NAME/files/usr"
  [[ -x "$prefix/bin/ssh" ]] || {
    echo "Built SSH binary is missing for $arch" >&2
    exit 1
  }

  dest="$OUTPUT/$abi"
  mkdir -p "$dest/bin" "$dest/lib" "$dest/libexec" "$dest/etc"

  for tool in ssh scp sftp ssh-add ssh-agent ssh-keygen ssh-keyscan; do
    install -m 0755 "$prefix/bin/$tool" "$dest/bin/$tool"
  done

  if [[ -d "$prefix/libexec" ]]; then
    find "$prefix/libexec" -maxdepth 1 -type f \
      \( -name 'ssh-*' -o -name 'sftp-server' \) \
      -exec install -m 0755 -t "$dest/libexec" {} +
  fi

  if [[ -d "$prefix/lib" ]]; then
    find "$prefix/lib" -maxdepth 1 -type f \
      \( -name '*.so' -o -name '*.so.*' \) \
      -exec cp -a -t "$dest/lib" {} +
  fi

  # Preserve runtime configuration/data that the custom prefix expects.
  for relative in etc/ssh etc/tls etc/termux; do
    if [[ -d "$prefix/$relative" ]]; then
      mkdir -p "$dest/$(dirname "$relative")"
      cp -a "$prefix/$relative" "$dest/$relative"
    fi
  done

done

printf '%s\n' "$OPENSSH_VERSION" > "$VERSION_FILE"
python3 "$ROOT/tools/openssh/write-manifest.py" \
  "$OUTPUT" "$OPENSSH_VERSION" "$PACKAGE_NAME"

license="$(find "$TERMUX_DIR/.termux-build/openssh" -type f \
  \( -iname 'LICENCE' -o -iname 'LICENSE' \) -print -quit 2>/dev/null || true)"
if [[ -n "$license" ]]; then
  cp "$license" "$OUTPUT/LICENSE.OPENSSH"
fi

echo "OpenSSH $OPENSSH_VERSION assets written to $OUTPUT"
