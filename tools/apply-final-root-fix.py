#!/usr/bin/env python3
from pathlib import Path
import os
import re

ROOT = Path.cwd()
PATCH_ROOT = Path(os.environ.get("PATCH_ROOT", Path(__file__).resolve().parents[1])).resolve()

def read(path): return Path(path).read_text(encoding='utf-8')
def write(path, text): Path(path).write_text(text, encoding='utf-8')
def copy(src, dst):
    target = ROOT / dst
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text((PATCH_ROOT / src).read_text(encoding='utf-8'), encoding='utf-8')

copy('app/src/main/java/com/trivox/client/core/CoreManager.kt', 'app/src/main/java/com/trivox/client/core/CoreManager.kt')
copy('app/src/main/java/com/trivox/client/core/CoreConfigTranslator.kt', 'app/src/main/java/com/trivox/client/core/CoreConfigTranslator.kt')
copy('app/src/main/java/com/trivox/client/core/CoreConversionManager.kt', 'app/src/main/java/com/trivox/client/core/CoreConversionManager.kt')
copy('app/src/main/java/com/trivox/client/config/NativeConfigImporter.kt', 'app/src/main/java/com/trivox/client/config/NativeConfigImporter.kt')

# Native imports must run before Xray JSON parsing, otherwise sing-box JSON is mistaken for Xray JSON.
p = ROOT / 'app/src/main/java/com/trivox/client/config/ConfigParser.kt'
if p.exists():
    s = read(p)
    if 'NativeConfigImporter.parseTextOrNull(text)?.let { return it }' not in s:
        marker = '        if (text.isBlank()) return emptyList()\n'
        if marker in s:
            s = s.replace(marker, marker + '        NativeConfigImporter.parseTextOrNull(text)?.let { return it }\n', 1)
    write(p, s)

# Fix proxy lifecycle wording and pre-start cleanup without breaking existing code.
p = ROOT / 'app/src/main/java/com/trivox/client/service/ConnectionService.kt'
if p.exists():
    s = read(p)
    s = s.replace('"Xray Android core is " +\n                    "missing or corrupted"', '"Selected core is " +\n                    "missing or corrupted"')
    s = s.replace('"stopping a stale Xray instance"', '"stopping stale core processes"')
    s = s.replace('"Xray stopped " +\n                                    "unexpectedly"', '"Selected core stopped " +\n                                    "unexpectedly"')
    write(p, s)

# Ensure subscription conversion strings exist.
for values_dir, sing, mihomo, xray, done in [
    ('app/src/main/res/values/strings.xml',
     'Convert this subscription to sing-box profiles', 'Convert this subscription to mihomo profiles', 'Convert this subscription to Xray profiles', 'Converted %1$d of %2$d profiles to %3$s; failed: %4$d'),
    ('app/src/main/res/values-fa/strings.xml',
     'تبدیل این ساب به پروفایل‌های sing-box', 'تبدیل این ساب به پروفایل‌های mihomo', 'تبدیل این ساب به پروفایل‌های Xray', 'از %2$d پروفایل، %1$d مورد به %3$s تبدیل شد؛ ناموفق: %4$d')
]:
    p = ROOT / values_dir
    if not p.exists():
        continue
    s = read(p)
    additions = {
        'subscription_convert_singbox': sing,
        'subscription_convert_mihomo': mihomo,
        'subscription_convert_xray': xray,
        'subscription_convert_done': done,
    }
    insert = ''.join(f'    <string name="{k}">{v}</string>\n' for k, v in additions.items() if f'name="{k}"' not in s)
    if insert and '</resources>' in s:
        s = s.replace('</resources>', insert + '</resources>', 1)
    write(p, s)

# Build workflow: full embedded cores are larger than 100 MiB; keep split APKs and raise guard.
p = ROOT / '.github/workflows/trivox-multicore-binaries.yml'
if p.exists():
    s = read(p)
    s = s.replace('actions/setup-java@v4', 'actions/setup-java@v5')
    s = s.replace('TRIVOX_MULTICORE_ABIS: arm64-v8a,armeabi-v7a,x86_64', 'TRIVOX_MULTICORE_ABIS: arm64-v8a,armeabi-v7a')
    s = s.replace('-PtrivoxAbis=arm64-v8a,armeabi-v7a,x86_64', '-PtrivoxAbis=arm64-v8a,armeabi-v7a')
    # If the old 100MiB check exists, raise it to 220MiB for full embedded sing-box + mihomo.
    s = s.replace('limit=100*1024*1024', 'limit=220*1024*1024')
    s = s.replace('100 MB', '220 MB')
    s = s.replace('100 MiB', '220 MiB')
    write(p, s)

# Gradle split guard.
p = ROOT / 'app/build.gradle.kts'
if p.exists():
    s = read(p)
    s = re.sub(r'val supportedAbis = listOf\((.*?)\)', 'val supportedAbis = listOf(\n    "arm64-v8a",\n    "armeabi-v7a"\n)', s, count=1, flags=re.S)
    s = s.replace('isUniversalApk = true', 'isUniversalApk = false')
    s = re.sub(r'\n\s*ndk\s*\{\s*\n\s*abiFilters\s*\+=\s*requestedAbis\s*\n\s*\}\s*', '\n', s, flags=re.S)
    write(p, s)

print('Final Trivox root multicore fix applied')
