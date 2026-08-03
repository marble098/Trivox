#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)
PINNED_VERSION="v26.7.28"
VERSION="$PINNED_VERSION"
ABIS="arm64-v8a,armeabi-v7a,x86_64"
LOCAL_CORE=""
VERSION_MODE="pinned"
PACKAGE_NAME="com.trivox.client"
REPO_OWNER=""
REPO_NAME="trivox"
BUILD=0
PUSH=0
CREATE_REPO=0
REPO_VISIBILITY="private"
NON_INTERACTIVE=0
CLEAN=0
VERBOSE=0
ORIGINAL_ARGC=$#

usage() {
  printf '%s\n' "Trivox wizard" \
    "  --core-version VERSION | --latest-stable | --latest-prerelease" \
    "  --local-core PATH       Official libXray AAR/ZIP; standalone Xray ZIPs are rejected" \
    "  --abis LIST             arm64-v8a,armeabi-v7a,x86_64" \
    "  --package NAME          Android applicationId" \
    "  --prepare | --prepare-only | --build" \
    "  --repo-owner USER --repo-name NAME --create-repo [--private-repo|--public-repo] --push" \
    "  --non-interactive --clean --verbose --help"
}

while (($#)); do
  case "$1" in
    --core-version) VERSION=${2:?Missing version}; VERSION_MODE="explicit"; shift 2 ;;
    --latest-stable) VERSION_MODE="stable"; shift ;;
    --latest-prerelease) VERSION_MODE="prerelease"; shift ;;
    --local-core) LOCAL_CORE=${2:?Missing path}; shift 2 ;;
    --abis) ABIS=${2:?Missing ABI list}; shift 2 ;;
    --package) PACKAGE_NAME=${2:?Missing package}; shift 2 ;;
    --repo-owner) REPO_OWNER=${2:?Missing owner}; shift 2 ;;
    --repo-name) REPO_NAME=${2:?Missing repository name}; shift 2 ;;
    --prepare|--prepare-only) shift ;;
    --build) BUILD=1; shift ;;
    --push) PUSH=1; shift ;;
    --create-repo) CREATE_REPO=1; shift ;;
    --private-repo) REPO_VISIBILITY="private"; shift ;;
    --public-repo) REPO_VISIBILITY="public"; shift ;;
    --non-interactive) NON_INTERACTIVE=1; shift ;;
    --clean) CLEAN=1; shift ;;
    --verbose) VERBOSE=1; shift ;;
    --skip-tun-helper) printf '%s\n' "No external TUN helper is used: Xray v26.7.28 has an Android TUN inbound."; shift ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'ERROR: Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

if ((NON_INTERACTIVE == 0 && ORIGINAL_ARGC == 0)) && [[ -t 0 ]]; then
  printf '%s\n' "Trivox interactive setup" "1) Pinned $PINNED_VERSION" "2) Latest stable" "3) Latest prerelease" "4) Local official AAR/ZIP"
  read -r -p "Core source [1]: " choice
  case "${choice:-1}" in
    1) ;;
    2) VERSION_MODE="stable" ;;
    3) VERSION_MODE="prerelease" ;;
    4) read -r -p "Local artifact path: " LOCAL_CORE; [[ -n "$LOCAL_CORE" ]] || { printf 'ERROR: A path is required.\n' >&2; exit 2; } ;;
    *) printf 'ERROR: Invalid selection.\n' >&2; exit 2 ;;
  esac
  read -r -p "ABIs [$ABIS]: " selected_abis
  ABIS=${selected_abis:-$ABIS}
  read -r -p "Build after preparation? [y/N]: " build_answer
  case "$build_answer" in y|Y|yes|YES|Yes) BUILD=1 ;; esac
fi

for command in curl python3 unzip sha256sum; do
  command -v "$command" >/dev/null 2>&1 || { printf 'ERROR: Required command not found: %s\n' "$command" >&2; exit 2; }
done
[[ "$PACKAGE_NAME" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || { printf 'ERROR: Invalid Android package name.\n' >&2; exit 2; }
[[ "$ABIS" =~ ^(arm64-v8a|armeabi-v7a|x86_64)(,(arm64-v8a|armeabi-v7a|x86_64))*$ ]] || { printf 'ERROR: Invalid ABI list.\n' >&2; exit 2; }

if ((CLEAN)); then
  rm -f -- "$PROJECT_DIR/app/libs/libXray.aar"
  printf '%s\n' '{"name":"Xray-core","adapter":"libXray","version":"v26.7.28","artifact":"libXray.aar","sha256":"","sourceUrl":"https://github.com/XTLS/libXray/releases/tag/v26.7.28","buildDate":"","abis":[],"capabilities":["proxy","android-tun"],"prepared":false}' > "$PROJECT_DIR/app/src/main/assets/core-manifest.json"
fi

api_headers=(--header "Accept: application/vnd.github+json" --header "X-GitHub-Api-Version: 2022-11-28")
if [[ -n "${GITHUB_TOKEN:-}" ]]; then api_headers+=(--header "Authorization: Bearer ${GITHUB_TOKEN}"); fi
curl_json() { curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 45 "${api_headers[@]}" "$1"; }

if [[ "$VERSION_MODE" == "stable" ]]; then
  VERSION=$(curl_json "https://api.github.com/repos/XTLS/libXray/releases/latest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')
elif [[ "$VERSION_MODE" == "prerelease" ]]; then
  VERSION=$(curl_json "https://api.github.com/repos/XTLS/Xray-core/releases?per_page=30" | python3 -c 'import json,sys; data=json.load(sys.stdin); print(next((x["tag_name"] for x in data if x.get("prerelease") and not x.get("draft")), ""))')
  [[ -n "$VERSION" ]] || { printf 'ERROR: No Xray prerelease was found.\n' >&2; exit 2; }
fi

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/trivox-wizard.XXXXXX")
cleanup() { rm -rf -- "$TEMP_DIR"; }
trap cleanup EXIT INT TERM

ARTIFACT="$LOCAL_CORE"
SOURCE_URL=""
EXPECTED_SHA=""
if [[ -z "$ARTIFACT" ]]; then
  RELEASE_JSON=$(curl_json "https://api.github.com/repos/XTLS/libXray/releases/tags/$VERSION") || {
    printf 'ERROR: libXray has no Android release matching Xray %s. Use --local-core with an official compatible AAR.\n' "$VERSION" >&2; exit 2;
  }
  SOURCE_URL=$(python3 -c 'import json,sys; r=json.load(sys.stdin); a=next((x for x in r["assets"] if x["name"]=="libxray-android.zip"),None); assert a,"Android asset missing"; print(a["browser_download_url"])' <<< "$RELEASE_JSON")
  EXPECTED_SHA=$(python3 -c 'import json,sys; r=json.load(sys.stdin); a=next((x for x in r["assets"] if x["name"]=="libxray-android.zip"),None); assert a,"Android asset missing"; d=a.get("digest") or ""; print(d[7:] if d.startswith("sha256:") else d)' <<< "$RELEASE_JSON")
  ARTIFACT="$TEMP_DIR/libxray-android.zip"
  ((VERBOSE)) && printf 'Downloading %s\n' "$SOURCE_URL"
  curl --fail --location --retry 2 --connect-timeout 15 --max-time 300 --output "$ARTIFACT" "$SOURCE_URL"
  [[ -s "$ARTIFACT" ]] || { printf 'ERROR: Downloaded core archive is empty.\n' >&2; exit 2; }
  case "$(head -c 16 "$ARTIFACT" || true)" in *html*|*HTML*|*'<!DOCTYPE'*) printf 'ERROR: Download returned HTML instead of an archive.\n' >&2; exit 2 ;; esac
else
  [[ -f "$ARTIFACT" ]] || { printf 'ERROR: Local core file does not exist: %s\n' "$ARTIFACT" >&2; exit 2; }
  ARTIFACT=$(CDPATH= cd -- "$(dirname -- "$ARTIFACT")" && printf '%s/%s' "$PWD" "$(basename -- "$ARTIFACT")")
  SOURCE_URL="local:$(basename -- "$ARTIFACT")"
fi

python3 "$SCRIPT_DIR/generate-core-manifest.py" --artifact "$ARTIFACT" \
  --aar-output "$PROJECT_DIR/app/libs/libXray.aar" \
  --manifest-output "$PROJECT_DIR/app/src/main/assets/core-manifest.json" \
  --version "$VERSION" --source-url "$SOURCE_URL" --abis "$ABIS" --expected-sha256 "$EXPECTED_SHA"

python3 - "$PROJECT_DIR/gradle.properties" "$PACKAGE_NAME" "$ABIS" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1]); values = {"trivoxPackage": sys.argv[2], "trivoxAbis": sys.argv[3]}
lines = [line for line in path.read_text().splitlines() if line.split("=", 1)[0] not in values]
lines.extend(f"{key}={value}" for key, value in values.items())
path.write_text("\n".join(lines) + "\n")
PY

if ((BUILD)); then (cd "$PROJECT_DIR" && ./build.sh all); fi

if ((CREATE_REPO || PUSH)); then
  command -v git >/dev/null || { printf 'ERROR: Git is required.\n' >&2; exit 2; }
  command -v gh >/dev/null || { printf 'ERROR: GitHub CLI is required. Install gh, then run gh auth login.\n' >&2; exit 2; }
  gh auth status >/dev/null 2>&1 || { printf 'ERROR: GitHub CLI is not authenticated. Run: gh auth login\n' >&2; exit 2; }
  [[ -n "$REPO_OWNER" ]] || REPO_OWNER=$(gh api user --jq .login)
  cd "$PROJECT_DIR"
  [[ -d .git ]] || git init
  git add .
  git diff --cached --quiet || git commit -m "Initialize Trivox Android client"
  if ((CREATE_REPO)); then gh repo view "$REPO_OWNER/$REPO_NAME" >/dev/null 2>&1 || gh repo create "$REPO_OWNER/$REPO_NAME" "--$REPO_VISIBILITY" --source . --remote origin; fi
  if ((PUSH)); then git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/$REPO_OWNER/$REPO_NAME.git"; git push -u origin HEAD; fi
fi

printf '%s\n' "Trivox preparation complete." "Core: $VERSION" "ABIs: $ABIS" "Build: ./build.sh all"
