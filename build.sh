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
