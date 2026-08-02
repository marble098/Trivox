#!/usr/bin/env bash
set -Eeuo pipefail
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
MODE=${1:-debug}
case "$MODE" in debug|release|arm64-v8a|armeabi-v7a|x86_64|universal|all) ;; *) printf 'Usage: %s {debug|release|arm64-v8a|armeabi-v7a|x86_64|universal|all}\n' "$0" >&2; exit 2 ;; esac
cd "$PROJECT_DIR"
python3 tools/generate-core-manifest.py --validate-only --abis "${TRIVOX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}" >/dev/null
mkdir -p dist
rm -f -- dist/trivox-*.apk dist/SHA256SUMS.txt

run_gradle() {
  local abis=$1; shift
  ./gradlew --no-daemon "-PtrivoxAbis=$abis" "$@"
}

case "$MODE" in
  debug) run_gradle "${TRIVOX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}" assembleDebug ;;
  release|universal) run_gradle "${TRIVOX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}" assembleRelease ;;
  arm64-v8a|armeabi-v7a|x86_64) run_gradle "$MODE" assembleRelease ;;
  all) run_gradle "${TRIVOX_ABIS:-arm64-v8a,armeabi-v7a,x86_64}" test lint assembleDebug assembleRelease ;;
esac

copy_outputs() {
  local build_type=$1
  find "app/build/outputs/apk/$build_type" -type f -name '*.apk' -print0 | while IFS= read -r -d '' apk; do
    local base abi name
    base=$(basename -- "$apk")
    case "$base" in
      *arm64-v8a*) abi="arm64-v8a" ;;
      *armeabi-v7a*) abi="armeabi-v7a" ;;
      *x86_64*) abi="x86_64" ;;
      *) abi="universal" ;;
    esac
    name="trivox-${abi}-${build_type}.apk"
    cp -f -- "$apk" "dist/$name"
  done
}

if [[ "$MODE" == "debug" || "$MODE" == "all" ]]; then copy_outputs debug; fi
if [[ "$MODE" != "debug" ]]; then copy_outputs release; fi
(cd dist && sha256sum trivox-*.apk | sort > SHA256SUMS.txt)
printf 'Build outputs:\n'
while IFS= read -r line; do printf '  %s\n' "$line"; done < dist/SHA256SUMS.txt
