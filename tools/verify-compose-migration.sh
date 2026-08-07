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
grep -Fq 'NavigationBar {' app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt || fail "bottom navigation missing"
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

echo "Compose Material 3 migration verification passed."
