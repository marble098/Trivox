#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_DIR=$(
  CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &&
  pwd -P
)

MODE=${1:-debug}

case "$MODE" in
  debug|release|arm64-v8a|armeabi-v7a|x86_64|universal|all)
    ;;
  *)
    printf \
      'Usage: %s {debug|release|arm64-v8a|armeabi-v7a|x86_64|universal|all}\n' \
      "$0" >&2
    exit 2
    ;;
esac

cd "$PROJECT_DIR"

# Calculate version metadata before Gradle configuration.
if [[ -z "${TRIVOX_VERSION_CODE:-}" ||
      -z "${TRIVOX_VERSION_NAME:-}" ||
      -z "${TRIVOX_GIT_SHA:-}" ]]; then
  if command -v git >/dev/null 2>&1 &&
     git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    commit_count="$(git rev-list --count HEAD 2>/dev/null || printf '1')"
    short_sha="$(git rev-parse --short=8 HEAD 2>/dev/null || printf 'source')"
  else
    commit_count=1
    short_sha=source
  fi

  [[ "$commit_count" =~ ^[0-9]+$ ]] || commit_count=1

  export TRIVOX_VERSION_CODE="${TRIVOX_VERSION_CODE:-$((1000 + commit_count))}"
  export TRIVOX_VERSION_NAME="${TRIVOX_VERSION_NAME:-0.2.${commit_count}}"
  export TRIVOX_GIT_SHA="${TRIVOX_GIT_SHA:-$short_sha}"
fi

printf 'Trivox version: %s (%s), commit %s\n' \
  "$TRIVOX_VERSION_NAME" \
  "$TRIVOX_VERSION_CODE" \
  "$TRIVOX_GIT_SHA"

case "$MODE" in
  release|universal|arm64-v8a|armeabi-v7a|x86_64|all)
    if [[ ! -s signing.properties ]]; then
      printf '%s\n' \
        'ERROR: A permanent signing.properties file is required for installable release APKs.' \
        'The build was stopped instead of silently changing the Android signing identity.' >&2
      exit 1
    fi
    ;;
esac

python3 tools/generate-core-manifest.py \
  --validate-only \
  --abis "${TRIVOX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}" \
  > /dev/null

mkdir -p dist

find dist \
  -maxdepth 1 \
  -type f \
  -name 'trivox-*.apk' \
  -delete

rm -f -- dist/SHA256SUMS.txt

run_gradle() {
  local abis=$1
  shift

  ./gradlew \
    --no-daemon \
    "-PtrivoxAbis=$abis" \
    "$@"
}

case "$MODE" in
  debug)
    run_gradle \
      "${TRIVOX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}" \
      assembleDebug
    ;;

  release|universal)
    run_gradle \
      "${TRIVOX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}" \
      assembleRelease
    ;;

  arm64-v8a|armeabi-v7a|x86_64)
    run_gradle "$MODE" assembleRelease
    ;;

  all)
    run_gradle \
      "${TRIVOX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}" \
      test \
      lint \
      assembleDebug \
      assembleRelease
    ;;
esac

copy_outputs() {
  local build_type=$1
  local wanted_abi=${2:-all}
  local output_dir="app/build/outputs/apk/$build_type"

  local apk
  local base
  local abi
  local name

  local -a outputs=()

  [[ -d "$output_dir" ]] || {
    printf \
      'ERROR: APK output directory is missing: %s\n' \
      "$output_dir" >&2
    exit 1
  }

  mapfile -d '' outputs < <(
    find "$output_dir" \
      -type f \
      -name '*.apk' \
      -print0 |
      sort -z
  )

  ((${#outputs[@]} > 0)) || {
    printf \
      'ERROR: No %s APK was generated.\n' \
      "$build_type" >&2
    exit 1
  }

  for apk in "${outputs[@]}"; do
    base=$(basename -- "$apk")

    case "$base" in
      *arm64-v8a*)
        abi="arm64-v8a"
        ;;
      *armeabi-v7a*)
        abi="armeabi-v7a"
        ;;
      *x86_64*)
        abi="x86_64"
        ;;
      *)
        abi="universal"
        ;;
    esac

    if [[ "$wanted_abi" != "all" && "$abi" != "$wanted_abi" ]]; then
      continue
    fi

    name="trivox-${abi}-${build_type}.apk"

    cp -f -- "$apk" "dist/$name"
  done
}

case "$MODE" in
  debug)
    copy_outputs debug
    ;;

  release)
    copy_outputs release
    ;;

  all)
    copy_outputs debug
    copy_outputs release
    ;;

  universal)
    copy_outputs release universal
    ;;

  arm64-v8a|armeabi-v7a|x86_64)
    copy_outputs release "$MODE"
    ;;
esac

mapfile -t built_apks < <(
  find dist \
    -maxdepth 1 \
    -type f \
    -name 'trivox-*.apk' \
    -print |
    sort
)

((${#built_apks[@]} > 0)) || {
  printf 'ERROR: No APK was copied to dist.\n' >&2
  exit 1
}

(
  cd dist
  sha256sum ./*.apk | sort > SHA256SUMS.txt
)

printf 'Build outputs:\n'

while IFS= read -r line; do
  printf '  %s\n' "$line"
done < dist/SHA256SUMS.txt
