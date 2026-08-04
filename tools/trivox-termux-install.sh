#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REPO_URL="https://github.com/marble098/Trivox.git"
REPO_DIR="${TRIVOX_REPO_DIR:-$HOME/Trivox}"
ZIP_PATH="${1:-}"
BRANCH="${TRIVOX_BRANCH:-main}"
COMMIT_MESSAGE="${TRIVOX_COMMIT_MESSAGE:-Improve dual latency reliability and subscription tools}"

log() { printf '\033[1;36m[Trivox]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[Trivox]\033[0m %s\n' "$*" >&2; exit 1; }

pkg update -y >/dev/null
pkg install -y git unzip python openssh >/dev/null

if [[ -z "$ZIP_PATH" ]]; then
  ZIP_PATH="$(find "$HOME/storage/downloads" /sdcard/Download "$HOME" -maxdepth 2 -type f -name 'Trivox-latency-upgrade*.zip' 2>/dev/null | head -n1 || true)"
fi
[[ -n "$ZIP_PATH" && -f "$ZIP_PATH" ]] || die "Upgrade ZIP not found. Pass its path as the first argument."

if [[ -d "$REPO_DIR/.git" ]]; then
  log "Using existing repository: $REPO_DIR"
  git -C "$REPO_DIR" fetch origin "$BRANCH" --prune
  git -C "$REPO_DIR" checkout "$BRANCH"
  git -C "$REPO_DIR" reset --hard "origin/$BRANCH"
else
  log "Cloning repository"
  rm -rf "$REPO_DIR"
  git clone --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$ZIP_PATH" -d "$TMP"
PACKAGE_ROOT="$(find "$TMP" -type d -name 'Trivox-latency-upgrade' -print -quit)"
[[ -n "$PACKAGE_ROOT" ]] || die "Invalid upgrade ZIP structure."

log "Backing up modified files"
BACKUP="$REPO_DIR/.trivox-backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP"
while IFS= read -r -d '' file; do
  rel="${file#$PACKAGE_ROOT/}"
  [[ "$rel" == "termux-install.sh" ]] && continue
  if [[ -f "$REPO_DIR/$rel" ]]; then
    mkdir -p "$BACKUP/$(dirname "$rel")"
    cp -f "$REPO_DIR/$rel" "$BACKUP/$rel"
  fi
  mkdir -p "$REPO_DIR/$(dirname "$rel")"
  cp -f "$file" "$REPO_DIR/$rel"
done < <(find "$PACKAGE_ROOT" -type f -print0)

cd "$REPO_DIR"
chmod +x gradlew 2>/dev/null || true
python3 tools/audit-trivox.py --ci

find_android_sdk() {
  local candidate="" sdk_prop=""
  local candidates=(
    "${ANDROID_HOME:-}"
    "${ANDROID_SDK_ROOT:-}"
    "$HOME/Android/Sdk"
    "$HOME/android-sdk"
    "$PREFIX/share/android-sdk"
  )
  if [[ -f "$REPO_DIR/local.properties" ]]; then
    sdk_prop="$(sed -n 's/^sdk\.dir=//p' "$REPO_DIR/local.properties" | tail -n1 | sed 's/\\\\/\\/g' || true)"
    candidates=("$sdk_prop" "${candidates[@]}")
  fi
  for candidate in "${candidates[@]}"; do
    [[ -n "$candidate" && -d "$candidate" ]] || continue
    if find "$candidate/platforms" -maxdepth 2 -type f -name android.jar -print -quit 2>/dev/null | grep -q .; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

LOCAL_PROPERTIES_BACKUP="$TMP/local.properties.backup"
LOCAL_PROPERTIES_EXISTED=0
if [[ -f local.properties ]]; then
  cp -f local.properties "$LOCAL_PROPERTIES_BACKUP"
  LOCAL_PROPERTIES_EXISTED=1
fi
restore_local_properties() {
  if (( LOCAL_PROPERTIES_EXISTED == 1 )); then
    cp -f "$LOCAL_PROPERTIES_BACKUP" local.properties
  else
    rm -f local.properties
  fi
}

if SDK_DIR="$(find_android_sdk)"; then
  log "Android SDK detected at $SDK_DIR; running local tests and lint"
  if ! (
    export ANDROID_HOME="$SDK_DIR"
    export ANDROID_SDK_ROOT="$SDK_DIR"
    printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
    ./gradlew testDebugUnitTest lintDebug --no-daemon
  ); then
    restore_local_properties
    die "Local Android tests or lint failed; no commit was pushed."
  fi
  restore_local_properties
else
  log "Android SDK is unavailable in Termux; local Gradle validation is skipped."
  log "GitHub Actions will run the Android tests and lint after push."
fi

git add -- \
  app/src/main/java/com/trivox/client/data/Models.kt \
  app/src/main/java/com/trivox/client/data/Repositories.kt \
  app/src/main/java/com/trivox/client/network/PingManager.kt \
  app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt \
  app/src/main/java/com/trivox/client/ui/MainActivity.kt \
  app/src/main/java/com/trivox/client/ui/ProfileAdapter.kt \
  app/src/main/res/layout/activity_main.xml \
  app/src/main/res/layout/row_profile.xml \
  app/src/main/res/layout/row_profile_grid.xml \
  app/src/main/res/drawable/row_background_favorite.xml \
  app/src/main/res/drawable/metric_chip_background.xml \
  app/src/main/res/drawable/quick_action_primary.xml \
  app/src/main/res/drawable/quick_action_secondary.xml \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-fa/strings.xml \
  app/src/test/java/com/trivox/client/data/ConfigProfileLatencyMigrationTest.kt \
  app/src/test/java/com/trivox/client/network/TunnelHealthVerifierTest.kt \
  tools/audit-trivox.py \
  .github/workflows/main.yml

if git diff --cached --quiet; then
  log "Repository already contains this upgrade."
  exit 0
fi

git config user.name "Trivox Termux Updater"
git config user.email "trivox-updater@users.noreply.github.com"
git commit -m "$COMMIT_MESSAGE"

TOKEN="${GITHUB_TOKEN:-}"
if [[ -z "$TOKEN" ]] && command -v gh >/dev/null 2>&1; then
  TOKEN="$(gh auth token 2>/dev/null || true)"
fi

if [[ -n "$TOKEN" ]]; then
  OLD_REMOTE="$(git remote get-url origin)"
  trap 'git remote set-url origin "$OLD_REMOTE" >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
  git remote set-url origin "https://x-access-token:${TOKEN}@github.com/marble098/Trivox.git"
  GIT_TERMINAL_PROMPT=0 git push origin "$BRANCH"
  git remote set-url origin "$OLD_REMOTE"
else
  GIT_TERMINAL_PROMPT=0 git push origin "$BRANCH" || die "Files and commit are ready, but no non-interactive GitHub credential was available. Export GITHUB_TOKEN and run again."
fi

log "Push completed successfully"
git status --short --branch
