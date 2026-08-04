#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

VERSION="v6.2"
REPO_URL="https://github.com/marble098/Trivox.git"
REPO_DIR=""
BRANCH="main"
DO_PUSH=1
RUN_GRADLE=1
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PATCH_SOURCE="$SCRIPT_DIR/apply-v6-reliability.py"
STATUS_FILE=""
STASHED=0
ORIGINAL_REMOTE=""
BASE_HEAD=""
PATCH_VALIDATED=0

usage() {
  cat <<'EOF'
Trivox v6 smart installer for Termux/Linux

Usage:
  bash install-trivox-v6-termux.sh [options]

Options:
  --repo DIR         Existing Trivox checkout (default: ./Trivox or current repo)
  --url URL          Repository URL used when cloning
  --branch NAME      Target branch (default: main)
  --no-push          Apply, verify and commit without pushing
  --skip-gradle      Skip Gradle unit tests
  --status FILE      Custom status-report path
  -h, --help         Show this help

Authentication:
  Existing git credentials are used. Alternatively export GITHUB_TOKEN before
  running; the token is used only for the push URL and is never written to disk.
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
    say "Installing required Termux packages: git python zip"
    pkg update -y
    pkg install -y git python zip
  elif command -v apt-get >/dev/null 2>&1; then
    say "Installing required Linux packages"
    sudo apt-get update
    sudo apt-get install -y git python3 zip
  else
    die "Missing commands: ${missing[*]}. Install them and rerun."
  fi
}

locate_repo() {
  if [[ -z "$REPO_DIR" ]]; then
    if git rev-parse --show-toplevel >/dev/null 2>&1; then
      REPO_DIR="$(git rev-parse --show-toplevel)"
    elif [[ -d "$PWD/Trivox/.git" ]]; then
      REPO_DIR="$PWD/Trivox"
    else
      REPO_DIR="$PWD/Trivox"
      say "Cloning $REPO_URL"
      git clone --branch "$BRANCH" --single-branch "$REPO_URL" "$REPO_DIR"
    fi
  fi
  REPO_DIR="$(cd "$REPO_DIR" && pwd)"
  [[ -d "$REPO_DIR/.git" ]] || die "$REPO_DIR is not a git repository"
  [[ -f "$REPO_DIR/app/src/main/AndroidManifest.xml" ]] || die "$REPO_DIR does not look like Trivox"
}

finish_install() {
  local status=$?
  trap - EXIT

  if ((status != 0)) && [[ -n "$BASE_HEAD" ]] && ((PATCH_VALIDATED == 0)); then
    say "Rolling back incomplete installer changes"
    git -C "$REPO_DIR" reset --hard "$BASE_HEAD" || true
    rm -f \
      "$REPO_DIR/tools/apply-v6-reliability.py" \
      "$REPO_DIR/tools/install-trivox-v6-termux.sh" \
      "$REPO_DIR/app/src/main/res/values/strings_v6.xml" \
      "$REPO_DIR/app/src/main/res/values-fa/strings_v6.xml" \
      "$REPO_DIR/app/src/test/java/com/trivox/client/data/AppSettingsLivePingTest.kt" \
      "$REPO_DIR/app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardV6Test.kt" \
      "$REPO_DIR/.github/workflows/apply-v6-reliability.yml"
  fi

  if ((STASHED)); then
    say "Restoring unrelated local work"
    git -C "$REPO_DIR" stash pop || {
      say "Automatic stash restore needs manual conflict resolution; the stash is preserved."
    }
  fi

  exit "$status"
}
trap finish_install EXIT

install_requirements
locate_repo

STATUS_FILE="${STATUS_FILE:-$REPO_DIR/trivox-v6-status.txt}"
ORIGINAL_REMOTE="$(git -C "$REPO_DIR" remote get-url origin 2>/dev/null || true)"

say "Repository: $REPO_DIR"
say "Branch: $BRANCH"

git -C "$REPO_DIR" fetch origin "$BRANCH" --prune
git -C "$REPO_DIR" checkout "$BRANCH"

if [[ -n "$(git -C "$REPO_DIR" status --porcelain)" ]]; then
  say "Stashing unrelated tracked and untracked changes before installation"
  git -C "$REPO_DIR" stash push -u -m "trivox-v6.1-installer-$(date +%s)"
  STASHED=1
fi

git -C "$REPO_DIR" pull --ff-only origin "$BRANCH"
BASE_HEAD="$(git -C "$REPO_DIR" rev-parse HEAD)"
mkdir -p "$REPO_DIR/tools" "$REPO_DIR/.github/workflows"

if [[ -f "$PATCH_SOURCE" ]]; then
  cp "$PATCH_SOURCE" "$REPO_DIR/tools/apply-v6-reliability.py"
elif [[ -f "$REPO_DIR/tools/apply-v6-reliability.py" ]]; then
  :
else
  command -v curl >/dev/null 2>&1 || {
    command -v pkg >/dev/null 2>&1 && pkg install -y curl
  }
  say "Downloading the versioned patcher"
  curl -fsSL \
    "https://raw.githubusercontent.com/marble098/Trivox/$BRANCH/tools/apply-v6-reliability.py" \
    -o "$REPO_DIR/tools/apply-v6-reliability.py"
fi
chmod +x "$REPO_DIR/tools/apply-v6-reliability.py"

if [[ -f "$SCRIPT_DIR/install-trivox-v6-termux.sh" ]]; then
  cp "$SCRIPT_DIR/install-trivox-v6-termux.sh" \
    "$REPO_DIR/tools/install-trivox-v6-termux.sh"
  chmod +x "$REPO_DIR/tools/install-trivox-v6-termux.sh"
fi
if [[ -f "$SCRIPT_DIR/.github/workflows/apply-v6-reliability.yml" ]]; then
  cp "$SCRIPT_DIR/.github/workflows/apply-v6-reliability.yml" \
    "$REPO_DIR/.github/workflows/apply-v6-reliability.yml"
fi

say "Applying connection, subscription, live-ping, motion and WireGuard fixes"
python3 "$REPO_DIR/tools/apply-v6-reliability.py"
python3 "$REPO_DIR/tools/apply-v6-reliability.py" --check

say "Checking XML resources"
python3 - "$REPO_DIR" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
root = Path(sys.argv[1])
files = sorted((root / "app/src/main/res").rglob("*.xml"))
for path in files:
    ET.parse(path)
print(f"XML OK: {len(files)} files")
PY

say "Running repository audit"
(cd "$REPO_DIR" && python3 tools/audit-trivox.py --ci)

git -C "$REPO_DIR" diff --check

if ((RUN_GRADLE)) && [[ -x "$REPO_DIR/gradlew" ]]; then
  if [[ -f "$REPO_DIR/app/libs/libxray.aar" ]] || find "$REPO_DIR/app/libs" -maxdepth 1 -name '*.aar' -print -quit 2>/dev/null | grep -q .; then
    say "Running focused unit tests"
    (cd "$REPO_DIR" && ./gradlew --no-daemon testDebugUnitTest)
  else
    say "Core AAR is absent; Gradle tests skipped. Audit and XML checks remain enforced."
  fi
fi

PATCH_VALIDATED=1

say "Creating commit when needed"
git -C "$REPO_DIR" add \
  README.md \
  app/src/main \
  app/src/test \
  tools/apply-v6-reliability.py \
  tools/install-trivox-v6-termux.sh \
  .github/workflows/apply-v6-reliability.yml 2>/dev/null || true

if git -C "$REPO_DIR" diff --cached --quiet; then
  say "No new diff: v6 is already installed"
else
  git -C "$REPO_DIR" commit -m "Fix connection cancellation and subscription-scoped latency"
fi

if ((DO_PUSH)); then
  say "Pushing $BRANCH"
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    repo_path="${REPO_URL#https://github.com/}"
    repo_path="${repo_path%.git}"
    git -C "$REPO_DIR" push \
      "https://x-access-token:${GITHUB_TOKEN}@github.com/${repo_path}.git" \
      "HEAD:$BRANCH"
  else
    git -C "$REPO_DIR" push origin "$BRANCH"
  fi
fi

{
  echo "Trivox v6 installation status"
  echo "Generated: $(date -Iseconds)"
  echo "Repository: $REPO_DIR"
  echo "Remote: ${ORIGINAL_REMOTE:-unknown}"
  echo "Branch: $(git -C "$REPO_DIR" branch --show-current)"
  echo "Commit: $(git -C "$REPO_DIR" rev-parse HEAD)"
  echo "Commit subject: $(git -C "$REPO_DIR" log -1 --pretty=%s)"
  echo "Push requested: $DO_PUSH"
  echo
  echo "Git status:"
  git -C "$REPO_DIR" status --short --branch
  echo
  echo "Latest commit files:"
  git -C "$REPO_DIR" show --stat --oneline --decorate -1
} > "$STATUS_FILE"

say "Done. Status report: $STATUS_FILE"
