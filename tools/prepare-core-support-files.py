#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path.cwd()
OUT = ROOT / "app/src/main/assets/core-data"
OUT.mkdir(parents=True, exist_ok=True)

DOWNLOADS = [
    ("Country.mmdb", [
        "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/country.mmdb",
        "https://github.com/Dreamacro/maxmind-geoip/releases/latest/download/Country.mmdb",
    ]),
    ("geoip.dat", [
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/geoip.dat",
    ]),
    ("geosite.dat", [
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/geosite.dat",
    ]),
    ("geoip.db", [
        "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db",
    ]),
    ("geosite.db", [
        "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db",
    ]),
    ("geoip.metadb", [
        "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/geoip.metadb",
    ]),
]

def download_one(name: str, urls: list[str]) -> bool:
    target = OUT / name
    if target.is_file() and target.stat().st_size > 1024:
        print(f"core data exists: {name} ({target.stat().st_size} bytes)")
        return True

    last = ""
    for url in urls:
        for attempt in range(1, 4):
            try:
                request = urllib.request.Request(
                    url,
                    headers={"User-Agent": "Trivox-CoreData/1.0"},
                )
                with urllib.request.urlopen(request, timeout=35) as response:
                    data = response.read(80 * 1024 * 1024)
                if len(data) < 1024:
                    raise RuntimeError(f"too small: {len(data)} bytes")
                target.write_bytes(data)
                print(
                    f"downloaded {name}: {len(data)} bytes "
                    f"sha256={hashlib.sha256(data).hexdigest()}"
                )
                return True
            except Exception as exc:
                last = f"{url}: {exc}"
                time.sleep(attempt)

    print(f"warning: could not download {name}: {last}", file=sys.stderr)
    return False

ok = 0
for name, urls in DOWNLOADS:
    if download_one(name, urls):
        ok += 1

manifest = OUT / "core-data-manifest.txt"
manifest.write_text(
    "\n".join(
        f"{p.name} {p.stat().st_size} {hashlib.sha256(p.read_bytes()).hexdigest()}"
        for p in sorted(OUT.iterdir())
        if p.is_file() and p.name != manifest.name
    ) + "\n",
    encoding="utf-8",
)

print(f"prepared {ok}/{len(DOWNLOADS)} core support files")
