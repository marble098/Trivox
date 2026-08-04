#!/usr/bin/env python3
from pathlib import Path
import os
import shutil
import sys

root = Path(".").resolve()
assets = root / "app/src/main/assets/cores"
jni = root / "app/src/main/jniLibs"

abis = ["arm64-v8a", "armeabi-v7a"]
names = {
    "sing-box": "libtrivox_sing_box.so",
    "mihomo": "libtrivox_mihomo.so",
}

def is_elf(path: Path) -> bool:
    try:
        return path.read_bytes()[:4] == b"\x7fELF"
    except Exception:
        return False

if jni.exists():
    shutil.rmtree(jni)

for abi in abis:
    for src_name, dst_name in names.items():
        src = assets / abi / src_name
        if not src.is_file():
            raise SystemExit(f"missing prepared core: {src}")
        if not is_elf(src):
            raise SystemExit(f"prepared core is not ELF: {src}")
        dst_dir = jni / abi
        dst_dir.mkdir(parents=True, exist_ok=True)
        dst = dst_dir / dst_name
        shutil.copy2(src, dst)
        os.chmod(dst, 0o755)
        if not is_elf(dst):
            raise SystemExit(f"jni core is not ELF: {dst}")
        print(f"native core: {src} -> {dst} ({dst.stat().st_size / 1024 / 1024:.1f} MiB)")

if assets.exists():
    shutil.rmtree(assets)

manifest = root / "app/src/main/assets/core-manifest-multicore.json"
if manifest.exists():
    manifest.unlink()

for bad in [jni / "x86_64"]:
    if bad.exists():
        shutil.rmtree(bad)
