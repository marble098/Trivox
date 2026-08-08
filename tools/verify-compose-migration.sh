#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."

fail() { echo "Compose migration verification failed: $*" >&2; exit 1; }

[[ ! -d app/src/main/res/layout ]] || fail "legacy res/layout directory still exists"
[[ -s app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt ]] || fail "MainComposeScreen.kt missing"
[[ -s app/src/main/java/com/trivox/client/ui/compose/TrivoxComposeUi.kt ]] || fail "TrivoxComposeUi.kt missing"
[[ -s app/src/main/java/com/trivox/client/ui/compose/RoutingComposeScreen.kt ]] || fail "RoutingComposeScreen.kt missing"
[[ -s app/src/main/java/com/trivox/client/ui/compose/LegacyLayoutBridge.kt ]] || fail "LegacyLayoutBridge.kt missing"

grep -Fq 'org.jetbrains.kotlin.plugin.compose' build.gradle.kts || fail "Compose Kotlin plugin missing"
grep -Fq 'compose = true' app/build.gradle.kts || fail "Compose build feature disabled"
grep -Fq 'androidx.compose:compose-bom:' app/build.gradle.kts || fail "Compose BOM missing"
grep -Fq 'androidx.compose.material3:material3' app/build.gradle.kts || fail "Material 3 dependency missing"
grep -Fq 'NavigationBar(' app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt || fail "bottom navigation missing"
grep -Fq 'DisposeOnViewTreeLifecycleDestroyed' app/src/main/java/com/trivox/client/ui/compose/LegacyLayoutBridge.kt || fail "Compose disposal strategy missing"

legacy_refs="$(grep -R -n -E '\bR\.layout\.[A-Za-z_][A-Za-z0-9_]*' app/src/main/java --include='*.kt' | grep -v 'android\.R\.layout' || true)"
[[ -z "$legacy_refs" ]] || { echo "$legacy_refs" >&2; fail "app R.layout references remain"; }

for screen in \
  activity_advanced_manual_config \
  activity_app_routing \
  activity_config_editor \
  activity_diagnostics \
  activity_main \
  activity_manual_config \
  activity_proxy_chain \
  activity_settings \
  activity_subscriptions \
  dialog_edit_config \
  dialog_import \
  dialog_subscription \
  row_app \
  row_profile \
  row_profile_grid \
  row_subscription
do
  grep -Eq "fun[[:space:]]+$screen\\(" app/src/main/java/com/trivox/client/ui/compose/LegacyLayoutBridge.kt || \
    fail "compatibility factory missing: $screen"
done

for tab in HOME CONFIGS SUBSCRIPTIONS TOOLS SETTINGS; do
  grep -Fq "$tab" app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt || fail "main tab missing: $tab"
done

for activity in \
  MainActivity \
  SettingsActivity \
  SubscriptionManagementActivity \
  ConfigEditorActivity \
  DiagnosticsActivity \
  ManualConfigActivity \
  AdvancedManualConfigActivity \
  ProxyChainActivity
do
  file="app/src/main/java/com/trivox/client/ui/${activity}.kt"
  [[ -s "$file" ]] || fail "$activity missing"
  grep -Fq 'LegacyLayoutBridge' "$file" || fail "$activity is not attached to the Compose host"
done

grep -Fq 'androidx.activity.compose.setContent' app/src/main/java/com/trivox/client/ui/AppRoutingActivity.kt || \
  fail "AppRoutingActivity is not direct Compose"

if command -v python3 >/dev/null 2>&1; then
  python3 tools/validate-android-resources.py
  python3 tools/validate-api-guards.py
  python3 tools/validate-string-formats.py
  python3 - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path('.')
for path in sorted((root / 'app/src/main/res').rglob('*.xml')):
    ET.parse(path)
ET.parse(root / 'app/src/main/AndroidManifest.xml')

ids = set()
for path in (root / 'app/src/main/res').glob('values*/ids.xml'):
    tree = ET.parse(path)
    for node in tree.getroot():
        if node.attrib.get('type') == 'id' and node.attrib.get('name'):
            ids.add(node.attrib['name'])

used = set()
pattern = re.compile(r'(?<!android\.)\bR\.id\.([A-Za-z_][A-Za-z0-9_]*)')
for path in (root / 'app/src/main/java').rglob('*.kt'):
    used.update(pattern.findall(path.read_text(encoding='utf-8')))
missing = sorted(used - ids)
if missing:
    raise SystemExit('Missing generated IDs: ' + ', '.join(missing))
print(f'Compose ID contract passed: {len(used)} Kotlin IDs are defined.')
PY
fi

# Runtime/UI regression checks added after the full Material 3 stabilization pass.
main_compose="app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt"
routing_compose="app/src/main/java/com/trivox/client/ui/compose/RoutingComposeScreen.kt"
legacy_compose="app/src/main/java/com/trivox/client/ui/compose/TrivoxComposeUi.kt"
main_activity="app/src/main/java/com/trivox/client/ui/MainActivity.kt"

grep -Fq 'contentWindowInsets = WindowInsets(0, 0, 0, 0)' "$main_compose" || \
  fail "main Compose screen may double-apply system bar insets"
python3 - "$main_compose" <<'PY_NAV' || fail "bottom navigation may double-apply navigation-bar insets"
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
pattern = re.compile(
    r"NavigationBar\s*\(\s*"
    r"windowInsets\s*=\s*WindowInsets\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)",
    re.S,
)
if pattern.search(source) is None:
    raise SystemExit(1)
PY_NAV
grep -Fq 'composeBatchState' "$main_compose" || fail "Compose batch-test progress state missing"
grep -Fq 'uiRevision = revision' "$main_compose" || \
  fail "Compose revision is not propagated into tab content"
grep -Fq 'remember(uiRevision)' "$main_compose" || \
  fail "tab state is not keyed to the UI revision"
grep -Fq 'composeSetGrid(value: Boolean)' "$main_compose" || \
  fail "settings grid switch is not value-driven"
grep -Fq 'override fun composeSetGrid(value: Boolean)' "$main_activity" || \
  fail "settings grid state setter is missing"
grep -Fq 'ButtonDefaults.buttonColors' "$main_compose" || \
  fail "connection action does not publish its active/destructive color"
grep -Fq 'connectionStartPending.get()' "$main_activity" || \
  fail "pending VPN starts are not exposed to Compose"
grep -Fq 'UI live ping completed' "$main_activity" || \
  fail "live ping runtime diagnostics missing"
! grep -Fq 'livePingText.text?.toString()' "$main_activity" || \
  fail "Compose live ping label still depends on the hidden legacy button"
grep -Fq 'composeSubscriptionActions' "$main_compose" || fail "subscription actions are not exposed in Compose"
grep -Fq 'GridCells.Adaptive' "$main_compose" || fail "Compose grid mode is not implemented"
! grep -Fq 'R.string.status_alive' "$main_compose" || fail "formatted legacy alive label used without arguments"
! grep -Fq 'R.string.status_dead' "$main_compose" || fail "formatted legacy dead label used without arguments"
grep -Fq 'current.tcpTestStatus = TestStatus.TESTING' "$main_activity" || \
  fail "TCP tests do not publish method-specific running state"
grep -Fq 'current.realTestStatus = TestStatus.TESTING' "$main_activity" || \
  fail "Real Delay tests do not publish method-specific running state"
grep -Fq 'repairStaleTestingStates' "$main_activity" || fail "stale testing-state recovery missing"
grep -Fq 'RadioButton' "$routing_compose" || fail "responsive routing policy selector missing"
grep -Fq 'AlertDialog' "$legacy_compose" || fail "safe spinner dialog renderer missing"

echo "Compose Material 3 migration verification passed."

grep -Fq 'UnifiedSettingsScreen' app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt || fail "unified settings screen not wired"
[[ -s app/src/main/java/com/trivox/client/ui/compose/UnifiedSettingsScreen.kt ]] || fail "UnifiedSettingsScreen.kt missing"
[[ -s app/src/main/java/com/trivox/client/importing/SmartClipboardImporter.kt ]] || fail "SmartClipboardImporter.kt missing"
[[ -s app/src/main/java/com/trivox/client/service/NotificationActionActivity.kt ]] || fail "NotificationActionActivity.kt missing"
[[ -s app/src/main/java/com/trivox/client/service/LocalProxyPortGuard.kt ]] || fail "LocalProxyPortGuard.kt missing"
grep -Fq 'composeMagicClipboard' app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt || fail "magic clipboard action missing"
grep -Fq 'combinedClickable' app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt || fail "subscription long press missing"
grep -Fq 'refreshSources' app/src/main/java/com/trivox/client/network/SubscriptionRefreshCoordinator.kt || fail "single subscription refresh missing"
grep -Fq 'notification_pause_15' app/src/main/java/com/trivox/client/service/NotificationSupport.kt || fail "notification quick actions missing"
grep -Fq 'LocalProxyPortGuard.prepare' app/src/main/java/com/trivox/client/service/ConnectionService.kt || fail "proxy port guard missing"
if grep -Fq 'LocaleListCompat.forLanguageTags("fa")' app/src/main/java/com/trivox/client/ui/MainActivity.kt; then
  fail "fresh install still forces Persian instead of System Default"
fi
