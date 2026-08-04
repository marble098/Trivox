#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

VERSION="v7.0"
REPO_URL="https://github.com/marble098/Trivox.git"
REPO_DIR=""
BRANCH="main"
DO_PUSH=1
RUN_GRADLE=1
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PATCH_SOURCE="$SCRIPT_DIR/apply-v7-import-wireguard.py"
INSTALLER_SOURCE="$SCRIPT_DIR/install-trivox-v7-termux.sh"
WORKFLOW_SOURCE="$SCRIPT_DIR/../.github/workflows/apply-v7-import-wireguard.yml"
STATUS_FILE=""
ZIP_FILE=""
STASHED=0
BASE_HEAD=""

usage() {
  cat <<'EOF'
Trivox v7 smart installer for Termux/Linux

Usage:
  bash install-trivox-v7-termux.sh [options]

Options:
  --repo DIR         Existing Trivox checkout (default: current repo or ./Trivox)
  --url URL          Repository URL used when cloning
  --branch NAME      Target branch (default: main)
  --no-push          Apply, test and commit without pushing
  --skip-gradle      Skip Gradle unit tests
  --status FILE      Status report path
  --zip FILE         Changed-files ZIP path
  -h, --help         Show this help

Authentication:
  Existing git credentials are used. You may export GITHUB_TOKEN; it is used only
  for the push command and is never persisted in .git/config or the status file.
EOF
}

say() { printf '\n[Trivox %s] %s\n' "$VERSION" "$*"; }
die() { printf '\n[Trivox %s] ERROR: %s\n' "$VERSION" "$*" >&2; exit 1; }

while (($#)); do
  case "$1" in
    --repo) REPO_DIR="${2:?missing directory}"; shift 2 ;;
    --url) REPO_URL="${2:?missing URL}"; shift 2 ;;
    --branch) BRANCH="${2:?missing branch}"; shift 2 ;;
    --no-push) DO_PUSH=0; shift ;;
    --skip-gradle) RUN_GRADLE=0; shift ;;
    --status) STATUS_FILE="${2:?missing file}"; shift 2 ;;
    --zip) ZIP_FILE="${2:?missing file}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

install_requirements() {
  local missing=()
  for cmd in git python3 zip; do
    command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
  done
  ((${#missing[@]} == 0)) && return

  if command -v pkg >/dev/null 2>&1; then
    say "Installing required Termux packages"
    pkg update -y
    pkg install -y git python zip
  elif command -v apt-get >/dev/null 2>&1; then
    say "Installing required Linux packages"
    sudo apt-get update
    sudo apt-get install -y git python3 zip
  else
    die "Install git, python3 and zip, then run the installer again"
  fi
}

resolve_repo() {
  if [[ -n "$REPO_DIR" ]]; then
    REPO_DIR="$(cd "$REPO_DIR" && pwd)"
  elif git rev-parse --show-toplevel >/dev/null 2>&1; then
    REPO_DIR="$(git rev-parse --show-toplevel)"
  elif [[ -d "$PWD/Trivox/.git" ]]; then
    REPO_DIR="$PWD/Trivox"
  else
    say "Cloning $REPO_URL"
    git clone --branch "$BRANCH" --single-branch "$REPO_URL" Trivox
    REPO_DIR="$PWD/Trivox"
  fi

  [[ -d "$REPO_DIR/.git" ]] || die "Not a git repository: $REPO_DIR"
  git -C "$REPO_DIR" remote get-url origin >/dev/null 2>&1 || die "origin remote is missing"
}

restore_on_error() {
  local code=$?
  if ((code != 0)); then
    say "Failure detected; restoring the pre-run worktree"
    if [[ -n "$BASE_HEAD" ]] && git -C "$REPO_DIR" cat-file -e "$BASE_HEAD^{commit}" 2>/dev/null; then
      git -C "$REPO_DIR" reset --hard "$BASE_HEAD" >/dev/null 2>&1 || true
    fi
    if ((STASHED)); then
      git -C "$REPO_DIR" stash pop >/dev/null 2>&1 || true
    fi
  fi
  exit "$code"
}
trap restore_on_error ERR

install_requirements
[[ -f "$PATCH_SOURCE" ]] || die "Patch file not found beside installer: $PATCH_SOURCE"
resolve_repo

STATUS_FILE="${STATUS_FILE:-$REPO_DIR/trivox-v7-status.txt}"
ZIP_FILE="${ZIP_FILE:-$REPO_DIR/trivox-v7-changed-files.zip}"

say "Fetching the latest $BRANCH"
git -C "$REPO_DIR" fetch origin "$BRANCH"
git -C "$REPO_DIR" checkout "$BRANCH"

if [[ -n "$(git -C "$REPO_DIR" status --porcelain)" ]]; then
  say "Stashing local changes safely"
  git -C "$REPO_DIR" stash push -u -m "trivox-v7-installer-$(date +%s)"
  STASHED=1
fi

git -C "$REPO_DIR" pull --ff-only origin "$BRANCH"
BASE_HEAD="$(git -C "$REPO_DIR" rev-parse HEAD)"

say "Installing v7 maintenance files"
install -m 0755 "$PATCH_SOURCE" "$REPO_DIR/tools/apply-v7-import-wireguard.py"
install -m 0755 "$INSTALLER_SOURCE" "$REPO_DIR/tools/install-trivox-v7-termux.sh"
if [[ -f "$WORKFLOW_SOURCE" ]]; then
  mkdir -p "$REPO_DIR/.github/workflows"
  install -m 0644 "$WORKFLOW_SOURCE" "$REPO_DIR/.github/workflows/apply-v7-import-wireguard.yml"
fi

say "Applying destination-aware import and WireGuard fixes"
python3 "$REPO_DIR/tools/apply-v7-import-wireguard.py"
python3 "$REPO_DIR/tools/apply-v7-import-wireguard.py" --check
python3 -m py_compile "$REPO_DIR/tools/apply-v7-import-wireguard.py"

say "Validating Android XML"
python3 - "$REPO_DIR" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
root = Path(sys.argv[1])
files = sorted((root / "app/src/main/res").rglob("*.xml"))
for path in files:
    ET.parse(path)
print(f"Validated {len(files)} XML resources")
PY

say "Running repository audit"
(cd "$REPO_DIR" && python3 tools/audit-trivox.py --ci)

git -C "$REPO_DIR" diff --check

if ((RUN_GRADLE)); then
  say "Running Android unit tests"
  (cd "$REPO_DIR" && ./gradlew --no-daemon testDebugUnitTest)
fi

CHANGED=(
  README.md
  app/src/main/java/com/trivox/client/config/ConfigParser.kt
  app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt
  app/src/main/java/com/trivox/client/core/CoreManager.kt
  app/src/main/java/com/trivox/client/data/Repositories.kt
  app/src/main/java/com/trivox/client/ui/MainActivity.kt
  app/src/main/java/com/trivox/client/ui/SettingsActivity.kt
  app/src/main/java/com/trivox/client/ui/ThemedActivity.kt
  app/src/main/res/values/strings_v7.xml
  app/src/main/res/values-fa/strings_v7.xml
  app/src/test/java/com/trivox/client/config/ConfigParserWireGuardQuickV7Test.kt
  app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardV7Test.kt
  tools/apply-v7-import-wireguard.py
  tools/install-trivox-v7-termux.sh
  .github/workflows/apply-v7-import-wireguard.yml
)

say "Creating changed-files ZIP"
rm -f "$ZIP_FILE"
(
  cd "$REPO_DIR"
  zip -q "$ZIP_FILE" "${CHANGED[@]}"
)

say "Creating commit"
git -C "$REPO_DIR" add "${CHANGED[@]}"
if git -C "$REPO_DIR" diff --cached --quiet; then
  say "No source change was needed; v7 is already applied"
else
  git -C "$REPO_DIR" commit -m "Fix selected-sub imports and harden WireGuard compatibility"
fi

if ((DO_PUSH)); then
  say "Pushing verified commit"
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    remote="$(git -C "$REPO_DIR" remote get-url origin)"
    case "$remote" in
      https://github.com/*)
        clean="${remote#https://github.com/}"
        git -C "$REPO_DIR" push "https://x-access-token:${GITHUB_TOKEN}@github.com/${clean}" "HEAD:$BRANCH"
        ;;
      *) git -C "$REPO_DIR" push origin "HEAD:$BRANCH" ;;
    esac
  else
    git -C "$REPO_DIR" push origin "HEAD:$BRANCH"
  fi
fi

{
  echo "Trivox v7 installation status"
  echo "Generated: $(date -Iseconds)"
  echo "Repository: $REPO_DIR"
  echo "Branch: $(git -C "$REPO_DIR" branch --show-current)"
  echo "Commit: $(git -C "$REPO_DIR" rev-parse HEAD)"
  echo "Commit subject: $(git -C "$REPO_DIR" log -1 --pretty=%s)"
  echo "Push requested: $DO_PUSH"
  echo "Patch verification: passed"
  echo "Repository audit: passed"
  echo "Gradle tests: $([[ $RUN_GRADLE -eq 1 ]] && echo passed || echo skipped)"
  echo "ZIP: $ZIP_FILE"
  echo
  echo "Git status:"
  git -C "$REPO_DIR" status --short --branch
  echo
  echo "Latest commit:"
  git -C "$REPO_DIR" show --stat --oneline --decorate -1
} > "$STATUS_FILE"

if ((STASHED)); then
  say "Restoring the user's previous local changes"
  git -C "$REPO_DIR" stash pop || say "Stash kept because automatic restore conflicted"
fi

trap - ERR
say "Done"
say "Status report: $STATUS_FILE"
say "Changed-files ZIP: $ZIP_FILE"
