#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PAYLOAD=""
for candidate in \
    "$SCRIPT_DIR/apply-v5-wireguard-dns-ui.py" \
    "$SCRIPT_DIR/tools/apply-v5-wireguard-dns-ui.py"
do
    [[ -f "$candidate" ]] && PAYLOAD="$candidate" && break
done
[[ -n "$PAYLOAD" ]] || {
    printf 'Patch payload not found next to installer.\n' >&2
    exit 1
}

if [[ -n "${1:-}" && "${1:-}" != "--no-push" ]]; then
    REPO_DIR="$1"
elif [[ -d "$SCRIPT_DIR/../.git" ]]; then
    REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
    REPO_DIR="$HOME/Trivox"
fi
PUSH=1
[[ "${1:-}" == "--no-push" || "${2:-}" == "--no-push" ]] && PUSH=0

say() { printf '\n[Trivox v5.1] %s\n' "$*"; }
die() { printf '\n[Trivox v5.1] ERROR: %s\n' "$*" >&2; exit 1; }

PATCH_PATHS=(
    README.md
    app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt
    app/src/main/java/com/trivox/client/core/CoreManager.kt
    app/src/main/java/com/trivox/client/network/PingManager.kt
    app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt
    app/src/main/java/com/trivox/client/ui/MainActivity.kt
    app/src/main/java/com/trivox/client/ui/ProfileAdapter.kt
    app/src/main/res/drawable/ic_expand_more_rounded.xml
    app/src/main/res/layout/activity_main.xml
    app/src/main/res/layout/activity_settings.xml
    app/src/main/res/layout/row_profile_grid.xml
    app/src/main/res/values/strings_v5.xml
    app/src/main/res/values-fa/strings_v5.xml
    app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardTest.kt
    tools/apply-v5-wireguard-dns-ui.py
    tools/install-trivox-v5-termux.sh
)

is_patch_path() {
    local wanted="$1" item
    for item in "${PATCH_PATHS[@]}"; do
        [[ "$wanted" == "$item" ]] && return 0
    done
    return 1
}

if ! command -v git >/dev/null 2>&1; then
    command -v pkg >/dev/null 2>&1 || die "git is missing and this is not Termux"
    pkg install -y git
fi
if ! command -v python3 >/dev/null 2>&1; then
    command -v pkg >/dev/null 2>&1 || die "python3 is missing"
    pkg install -y python
fi

if [[ ! -d "$REPO_DIR/.git" ]]; then
    say "Cloning Trivox into $REPO_DIR"
    git clone https://github.com/marble098/Trivox.git "$REPO_DIR"
fi
cd "$REPO_DIR"

branch="$(git symbolic-ref --quiet --short HEAD || true)"
[[ -n "$branch" ]] || die "Repository is in detached HEAD state"
remote="$(git remote get-url origin 2>/dev/null || true)"
[[ "$remote" == *"marble098/Trivox"* ]] || say "Warning: origin is $remote"

# Preserve the backup made by the interrupted v5.0 installer, but keep backups
# outside the repository so they can never be staged by git add.
if [[ -d "$REPO_DIR/.trivox-backups" ]]; then
    legacy_target="$HOME/.trivox-backups/Trivox-legacy-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$(dirname "$legacy_target")"
    mv -- "$REPO_DIR/.trivox-backups" "$legacy_target"
    say "Moved previous in-repository backup to $legacy_target"
fi

# Keep generated status files out of commits without changing the repository's
# tracked .gitignore.
mkdir -p .git/info
for local_exclude in /trivox-v5-status.txt /.trivox-backups/; do
    grep -Fqx "$local_exclude" .git/info/exclude 2>/dev/null || \
        printf '%s\n' "$local_exclude" >> .git/info/exclude
done

declare -A dirty_seen=()
while IFS= read -r -d '' path; do
    dirty_seen["$path"]=1
done < <(
    git diff --name-only -z
    git diff --cached --name-only -z
    git ls-files --others --exclude-standard -z
)

dirty_paths=("${!dirty_seen[@]}")
interrupted_patch=0
if (( ${#dirty_paths[@]} > 0 )) && {
    [[ -f app/src/main/res/values/strings_v5.xml ]] ||
    [[ -f app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt ]];
}; then
    interrupted_patch=1
    for path in "${dirty_paths[@]}"; do
        if ! is_patch_path "$path"; then
            interrupted_patch=0
            break
        fi
    done
fi

stash_created=0
if (( ${#dirty_paths[@]} > 0 )); then
    if (( interrupted_patch )); then
        say "Detected the interrupted v5.0 patch; resuming it in place without stashing partial files"
    else
        say "Saving unrelated local work in an automatic stash"
        git stash push -u -m "trivox-v5.1-auto-stash-$(date +%Y%m%d-%H%M%S)"
        stash_created=1
    fi
fi

if (( interrupted_patch )); then
    say "Keeping current HEAD because the previous patch already started from it"
else
    say "Updating branch $branch"
    git pull --ff-only origin "$branch"
fi

backup_dir="${TRIVOX_BACKUP_DIR:-$HOME/.trivox-backups/Trivox}"
mkdir -p "$backup_dir"
backup="$backup_dir/trivox-v5.1-$(date +%Y%m%d-%H%M%S).tar.gz"
git ls-files -z | tar --null -czf "$backup" --files-from=-
say "Backup created: $backup"

mkdir -p tools
cp -f -- "$PAYLOAD" tools/apply-v5-wireguard-dns-ui.py
chmod +x tools/apply-v5-wireguard-dns-ui.py

say "Applying reviewed WireGuard, DNS and UI changes"
python3 tools/apply-v5-wireguard-dns-ui.py
chmod +x tools/install-trivox-v5-termux.sh 2>/dev/null || true

git diff --check
python3 tools/validate-android-resources.py
python3 tools/validate-api-guards.py
python3 tools/validate-string-formats.py
python3 tools/audit-trivox.py --ci

if [[ "${TRIVOX_RUN_GRADLE:-auto}" == "1" ]] || {
    [[ "${TRIVOX_RUN_GRADLE:-auto}" == "auto" ]] &&
    [[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] &&
    [[ -d "$HOME/.gradle/caches" ]];
}; then
    say "Running cached Gradle tests and lint"
    ./gradlew --no-daemon test lint
else
    say "Gradle download/build skipped; GitHub Actions will perform the full build"
fi

if [[ -n "$(git status --porcelain -- "${PATCH_PATHS[@]}")" ]]; then
    git add -- "${PATCH_PATHS[@]}"
    git diff --cached --check
    git commit -m "Improve WireGuard verification DNS and grid UI"
else
    say "Repository already matches this patch"
fi

if (( PUSH )); then
    say "Pushing $branch"
    git push origin "$branch"
fi

status_file="$REPO_DIR/trivox-v5-status.txt"
{
    echo "repository=$REPO_DIR"
    echo "branch=$branch"
    echo "commit=$(git rev-parse HEAD)"
    echo "remote=$remote"
    echo "push=$PUSH"
    echo "backup=$backup"
    echo
    git status --short --branch
} | tee "$status_file"

if (( stash_created )); then
    say "Restoring the unrelated work that was stashed before installation"
    git stash pop || say "Stash restore needs manual conflict resolution; the stash remains available"
fi

say "Done. Status: $status_file"
