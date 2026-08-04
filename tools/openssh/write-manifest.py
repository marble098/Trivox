#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import hashlib
import json
import sys

if len(sys.argv) not in (2, 3, 4):
    raise SystemExit(
        "usage: write-manifest.py ASSET_ROOT [OPENSSH_VERSION] [PACKAGE_NAME]"
    )

root = Path(sys.argv[1]).resolve()
version = (
    sys.argv[2].strip()
    if len(sys.argv) == 3
    else (root / "version.txt").read_text(encoding="utf-8").strip()
)
if not version:
    raise SystemExit("OpenSSH version is empty")
package_name = sys.argv[3].strip() if len(sys.argv) == 4 else "com.trivox.client"
if not package_name or "/" in package_name:
    raise SystemExit("Invalid Android package name")

required = {
    "bin/ssh",
    "bin/scp",
    "bin/sftp",
    "bin/ssh-add",
    "bin/ssh-agent",
    "bin/ssh-keygen",
    "bin/ssh-keyscan",
}
abis: dict[str, dict[str, dict[str, str]]] = {}
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    directory = root / abi
    if not directory.is_dir():
        raise SystemExit(f"Missing ABI directory: {directory}")
    available = {
        path.relative_to(directory).as_posix()
        for path in directory.rglob("*")
        if path.is_file()
    }
    missing = sorted(required - available)
    if missing:
        raise SystemExit(f"Missing {abi} OpenSSH files: {', '.join(missing)}")

    files = {}
    for relative in sorted(available):
        path = directory / relative
        files[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    abis[abi] = {"files": files}

manifest = {
    "format": 1,
    "version": version,
    "packageName": package_name,
    "prefix": f"/data/data/{package_name}/files/usr",
    "abis": abis,
}
(root / "manifest.json").write_text(
    json.dumps(manifest, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
