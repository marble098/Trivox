#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

VERSION="7.0-gh-direct-partial-clone"
SCRIPT_NAME="$(basename "$0")"

log()  { printf '\033[1;36m[Trivox]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[OK]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[WARNING]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<USAGE
Usage:
  bash "$SCRIPT_NAME" <SOURCE.zip> <GitHub repo URL|owner/repo> [branch]

Downloads example:
  bash /sdcard/Download/$SCRIPT_NAME \\
    /sdcard/Download/Trivox-Compose-Material3.zip \\
    https://github.com/marble098/Trivox

This version does NOT ask for a separate PAT.
It uses the GitHub account already authenticated in gh.
It performs a shallow partial clone with --filter=blob:none and --no-checkout,
so old repository file contents are not downloaded to the phone.
USAGE
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

normalize_repo() {
  local v="$1"
  v="${v%/}"
  v="${v%.git}"
  v="${v#https://github.com/}"
  v="${v#http://github.com/}"
  v="${v#git@github.com:}"
  [[ "$v" =~ ^[^/]+/[^/]+$ ]] || die "Invalid GitHub repository: $1"
  printf '%s' "$v"
}

resolve_zip_path() {
  local requested="$1" name candidate
  if [[ -f "$requested" ]]; then
    printf '%s' "$requested"
    return 0
  fi
  name="$(basename "$requested")"
  for candidate in \
      "/sdcard/Download/$name" \
      "/storage/emulated/0/Download/$name" \
      "${HOME:-}/storage/downloads/$name"; do
    [[ -f "$candidate" ]] && { printf '%s' "$candidate"; return 0; }
  done
  return 1
}

extract_and_validate_zip() {
  local zip="$1" dest="$2"
  python3 - "$zip" "$dest" <<'PY'
import os
import pathlib
import shutil
import stat
import sys
import zipfile

zip_path = pathlib.Path(sys.argv[1])
dest = pathlib.Path(sys.argv[2]).resolve()
dest.mkdir(parents=True, exist_ok=True)

with zipfile.ZipFile(zip_path) as zf:
    bad = zf.testzip()
    if bad:
        raise SystemExit(f"Corrupted ZIP member: {bad}")

    for info in zf.infolist():
        raw = info.filename.replace('\\', '/')
        if not raw:
            continue
        rel = pathlib.PurePosixPath(raw)
        if rel.is_absolute() or '..' in rel.parts:
            raise SystemExit(f"Unsafe ZIP path: {raw}")

        target = dest.joinpath(*rel.parts)
        parent = target.parent.resolve()
        if dest != parent and dest not in parent.parents:
            raise SystemExit(f"ZIP member escapes destination: {raw}")

        mode = (info.external_attr >> 16) & 0xFFFF
        if stat.S_ISLNK(mode):
            raise SystemExit(f"Symlink ZIP entries are not accepted: {raw}")

        if info.is_dir() or raw.endswith('/'):
            target.mkdir(parents=True, exist_ok=True)
            if mode:
                os.chmod(target, stat.S_IMODE(mode))
            continue

        target.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(info, 'r') as src, open(target, 'wb') as out:
            shutil.copyfileobj(src, out)
        if mode:
            os.chmod(target, stat.S_IMODE(mode))

entries = [p for p in dest.iterdir() if p.name != '__MACOSX']
if not entries:
    raise SystemExit('ZIP extracted to an empty directory')

def is_project(root: pathlib.Path) -> bool:
    return ((root / 'settings.gradle.kts').is_file() or (root / 'settings.gradle').is_file()) and (root / 'app').is_dir()

source = dest
if len(entries) == 1 and entries[0].is_dir() and is_project(entries[0]):
    source = entries[0]
elif not is_project(dest):
    candidates = [p for p in entries if p.is_dir() and is_project(p)]
    if len(candidates) != 1:
        raise SystemExit('Could not uniquely locate Android project root in ZIP')
    source = candidates[0]

gradlew = source / 'gradlew'
if not gradlew.is_file():
    raise SystemExit('gradlew is missing from ZIP project root')
if not os.access(gradlew, os.X_OK):
    raise SystemExit('gradlew is not executable inside ZIP; refusing to publish broken permissions')

pathlib.Path(dest / '.trivox-source-root').write_text(str(source.resolve()), encoding='utf-8')
print(source.resolve())
PY
}

run_validators() {
  local src="$1"
  log "Running offline source validators"
  (
    cd "$src"
    [[ -f tools/validate-android-resources.py ]] && python3 tools/validate-android-resources.py
    [[ -f tools/validate-api-guards.py ]] && python3 tools/validate-api-guards.py
    [[ -f tools/validate-string-formats.py ]] && python3 tools/validate-string-formats.py
    [[ -f tools/audit-trivox.py ]] && python3 tools/audit-trivox.py --ci
    [[ -f tools/verify-compose-migration.sh ]] && bash tools/verify-compose-migration.sh
    git diff --no-index --check /dev/null . >/dev/null 2>&1 || {
      # git diff --no-index is not reliable for directories on every Git build;
      # the real staged whitespace check is executed after the replacement tree is indexed.
      true
    }
  )
  ok "Offline validators passed"
}

make_askpass() {
  local path="$1"
  cat > "$path" <<'EOF'
#!/usr/bin/env sh
case "$1" in
  *Username*) printf '%s\n' 'x-access-token' ;;
  *Password*) printf '%s\n' "${TRIVOX_GH_TOKEN:?}" ;;
  *) printf '%s\n' "${TRIVOX_GH_TOKEN:?}" ;;
esac
EOF
  chmod 700 "$path"
}

[[ $# -ge 2 && $# -le 3 ]] || { usage; exit 2; }

ZIP_INPUT="$1"
REPO_INPUT="$2"
BRANCH="${3:-}"

need_cmd gh
need_cmd git
need_cmd python3
need_cmd cp
need_cmd find
need_cmd mktemp

ZIP_PATH="$(resolve_zip_path "$ZIP_INPUT" || true)"
[[ -n "$ZIP_PATH" && -f "$ZIP_PATH" ]] || die "ZIP not found: $ZIP_INPUT"
case "$ZIP_PATH" in *.zip|*.ZIP) ;; *) die "Source must be a ZIP file" ;; esac
ZIP_PATH="$(cd "$(dirname "$ZIP_PATH")" && pwd -P)/$(basename "$ZIP_PATH")"
REPO="$(normalize_repo "$REPO_INPUT")"

log "Trivox GitHub Direct Replacer v$VERSION"
log "Repository: $REPO"
log "Source ZIP: $ZIP_PATH"

gh auth status -h github.com >/dev/null 2>&1 || die "GitHub CLI is not authenticated. Run: gh auth login"
LOGIN="$(gh api user --jq '.login' 2>/dev/null || true)"
[[ -n "$LOGIN" ]] || die "Could not verify the current gh login"
ok "Using existing gh login: $LOGIN"

CAN_PUSH="$(gh api "repos/$REPO" --jq '.permissions.push // false' 2>/dev/null || true)"
[[ "$CAN_PUSH" == "true" ]] || die "The current gh account cannot push to $REPO"
ok "Push access verified"

DEFAULT_BRANCH="$(gh api "repos/$REPO" --jq '.default_branch' 2>/dev/null || true)"
[[ -n "$DEFAULT_BRANCH" ]] || die "Could not determine repository default branch"
[[ -n "$BRANCH" ]] || BRANCH="$DEFAULT_BRANCH"
log "Target branch: $BRANCH"

ORIGINAL_SHA="$(gh api "repos/$REPO/git/ref/heads/$BRANCH" --jq '.object.sha' 2>/dev/null || true)"
[[ -n "$ORIGINAL_SHA" ]] || die "Could not read remote branch $BRANCH"
ok "Remote HEAD: ${ORIGINAL_SHA:0:12}"

TMP_BASE="${TMPDIR:-${PREFIX:-/tmp}/tmp}"
mkdir -p "$TMP_BASE"
WORK="$(mktemp -d "$TMP_BASE/trivox-direct.XXXXXXXX")"
SRC_EXTRACT="$WORK/source"
REPO_DIR="$WORK/repo"
ASKPASS="$WORK/askpass.sh"

cleanup() {
  local rc=$?
  unset TRIVOX_GH_TOKEN GIT_ASKPASS GIT_TERMINAL_PROMPT
  rm -rf "$WORK" 2>/dev/null || true
  exit "$rc"
}
trap cleanup EXIT INT TERM

log "Validating and extracting ZIP"
extract_and_validate_zip "$ZIP_PATH" "$SRC_EXTRACT" >/dev/null
SRC_ROOT="$(cat "$SRC_EXTRACT/.trivox-source-root")"
[[ -d "$SRC_ROOT/app" ]] || die "Android app directory missing after extraction"
ok "ZIP integrity, paths, and executable permissions verified"

run_validators "$SRC_ROOT"

TRIVOX_GH_TOKEN="$(gh auth token -h github.com 2>/dev/null || true)"
[[ -n "$TRIVOX_GH_TOKEN" ]] || die "gh is logged in but no reusable GitHub token could be obtained"
export TRIVOX_GH_TOKEN
make_askpass "$ASKPASS"
export GIT_ASKPASS="$ASKPASS"
export GIT_TERMINAL_PROMPT=0

log "Fetching only Git metadata/tree objects; old file blobs are not checked out"
mkdir -p "$REPO_DIR"
(
  cd "$REPO_DIR"
  git init -q
  git remote add origin "https://github.com/$REPO.git"
  git -c protocol.version=2 fetch \
    --depth=1 \
    --filter=blob:none \
    --no-tags \
    origin "refs/heads/$BRANCH"

  git update-ref "refs/heads/$BRANCH" FETCH_HEAD
  git symbolic-ref HEAD "refs/heads/$BRANCH"
)
ok "Minimal Git fetch completed"

CURRENT_FETCHED="$(git -C "$REPO_DIR" rev-parse HEAD)"
[[ "$CURRENT_FETCHED" == "$ORIGINAL_SHA" ]] || die "Remote branch changed during preparation; run the script again"

BACKUP_TAG="trivox-backup-$(date +%Y%m%d-%H%M%S)"
log "Creating remote safety tag: $BACKUP_TAG"
git -C "$REPO_DIR" tag "$BACKUP_TAG" "$ORIGINAL_SHA"
git -C "$REPO_DIR" push origin "refs/tags/$BACKUP_TAG:refs/tags/$BACKUP_TAG" >/dev/null
ok "Backup tag pushed"

log "Building exact replacement tree without checking out old repository files"
commit_rc=0
(
  cd "$REPO_DIR"
  git read-tree --empty
  cp -a "$SRC_ROOT"/. "$REPO_DIR"/

  [[ -x gradlew ]] || die "gradlew lost executable permission while copying"
  [[ -d app ]] || die "app directory missing in replacement tree"
  [[ -f settings.gradle.kts || -f settings.gradle ]] || die "Gradle settings file missing in replacement tree"

  git add -A
  git diff --cached --check

  if git diff --cached --quiet; then
    ok "Repository already matches the ZIP exactly; nothing to commit"
    exit 20
  fi

  git config user.name "$LOGIN"
  git config user.email "${LOGIN}@users.noreply.github.com"
  git commit -q -m "Replace repository with Trivox Compose source"
) || commit_rc=$?

if [[ "$commit_rc" -eq 20 ]]; then
  log "No replacement push was necessary"
  exit 0
elif [[ "$commit_rc" -ne 0 ]]; then
  die "Could not create replacement commit"
fi

REMOTE_BEFORE_PUSH="$(git -C "$REPO_DIR" ls-remote origin "refs/heads/$BRANCH" | awk '{print $1}')"
[[ "$REMOTE_BEFORE_PUSH" == "$ORIGINAL_SHA" ]] || die "Remote branch changed before push; safety stop activated"

NEW_SHA="$(git -C "$REPO_DIR" rev-parse HEAD)"
log "Pushing complete replacement commit with the credential already stored by gh"
PUSH_LOG="$WORK/push.log"
if ! git -C "$REPO_DIR" push origin "HEAD:refs/heads/$BRANCH" >"$PUSH_LOG" 2>&1; then
  cat "$PUSH_LOG" >&2
  if grep -qiE 'workflows permission|workflow.*permission|refusing to allow.*workflow' "$PUSH_LOG"; then
    warn "Your existing gh credential needs the workflow scope."
    warn "Run this once, complete GitHub authorization, then rerun the same v7 command:"
    warn "gh auth refresh -h github.com -s workflow"
  fi
  die "GitHub rejected the final push"
fi
cat "$PUSH_LOG"
ok "Repository replacement pushed successfully: ${NEW_SHA:0:12}"

log "Verifying remote branch"
REMOTE_AFTER="$(gh api "repos/$REPO/git/ref/heads/$BRANCH" --jq '.object.sha')"
[[ "$REMOTE_AFTER" == "$NEW_SHA" ]] || die "Remote verification mismatch: expected $NEW_SHA, got $REMOTE_AFTER"
ok "Remote branch now points to replacement commit"

# Best-effort cleanup of temporary source releases from older failed Actions-only attempts.
while IFS=$'\t' read -r tag draft prerelease; do
  [[ "$tag" == trivox-source-* ]] || continue
  gh release delete "$tag" --repo "$REPO" --cleanup-tag --yes >/dev/null 2>&1 || true
done < <(gh release list --repo "$REPO" --limit 100 --json tagName,isDraft,isPrerelease --jq '.[] | [.tagName,.isDraft,.isPrerelease] | @tsv' 2>/dev/null || true)

printf '\n'
ok "DONE"
printf 'Repository: https://github.com/%s\n' "$REPO"
printf 'Branch: %s\n' "$BRANCH"
printf 'Backup tag: %s\n' "$BACKUP_TAG"
printf 'New commit: %s\n' "$NEW_SHA"
printf 'Actions: https://github.com/%s/actions\n' "$REPO"
