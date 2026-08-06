#!/usr/bin/env python3
from pathlib import Path
import argparse
import re
import sys

ROOT = Path.cwd()
BANNED = re.compile(r"\b(mihomo|sing[-_ ]?box|smartCoreSelection|lastSmartCoreId|preferredTestCore|switchCore|smartSelect|CoreId)\b", re.IGNORECASE)
SKIP_DIRS = {'.git', '.gradle', 'build', '.idea'}
SCAN_SUFFIXES = {'.kt', '.kts', '.xml', '.json', '.yml', '.yaml'}
ALLOW = {
    Path('tools/trivox-xray-only-sanity.py'),
}

def iter_files():
    for base in [ROOT / 'app' / 'src' / 'main', ROOT / 'app' / 'build.gradle.kts', ROOT / 'build.gradle.kts']:
        if base.is_file():
            yield base
        elif base.is_dir():
            for p in base.rglob('*'):
                if any(part in SKIP_DIRS for part in p.parts):
                    continue
                if p.is_file() and p.suffix in SCAN_SUFFIXES:
                    yield p

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-only', action='store_true')
    args = parser.parse_args()
    hits = []
    for path in iter_files():
        rel = path.relative_to(ROOT)
        if rel in ALLOW:
            continue
        text = path.read_text(encoding='utf-8', errors='ignore')
        for i, line in enumerate(text.splitlines(), 1):
            if BANNED.search(line):
                hits.append(f'{rel}:{i}: {line.strip()}')
    manifest = ROOT / 'app' / 'src' / 'main' / 'assets' / 'core-manifest.json'
    if manifest.exists() and 'Xray-core' not in manifest.read_text(encoding='utf-8', errors='ignore'):
        hits.append('core-manifest.json does not declare Xray-core')
    if hits:
        print('Xray-only sanity check failed:', file=sys.stderr)
        print('\n'.join(hits[:200]), file=sys.stderr)
        sys.exit(1)
    print('Xray-only sanity check passed.')

if __name__ == '__main__':
    main()
