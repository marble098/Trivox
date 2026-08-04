#!/usr/bin/env python3
from pathlib import Path
import shutil
import os

root = Path(".").resolve()
assets = root / "app/src/main/assets/cores"
jni = root / "app/src/main/jniLibs"

abis = ["arm64-v8a", "armeabi-v7a"]
names = {
    "sing-box": "libtrivox_sing_box.so",
    "mihomo": "libtrivox_mihomo.so",
}

for abi in abis:
    for src_name, dst_name in names.items():
        src = assets / abi / src_name
        if not src.is_file():
            continue
        dst_dir = jni / abi
        dst_dir.mkdir(parents=True, exist_ok=True)
        dst = dst_dir / dst_name
        shutil.copy2(src, dst)
        os.chmod(dst, 0o755)
        print(f"native core: {src} -> {dst}")

x86 = jni / "x86_64"
if x86.exists():
    shutil.rmtree(x86)
