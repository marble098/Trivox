#!/usr/bin/env python3
import gzip
import hashlib
import json
import lzma
import os
import re
import shutil
import stat
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/cores"
MANIFEST = ROOT / "app/src/main/assets/core-manifest-multicore.json"

ALL_ABIS = {
    "arm64-v8a": [
        "android-arm64",
        "android-arm64-v8a",
        "android-aarch64",
        "aarch64",
        "arm64-v8a",
        "arm64",
    ],
    "armeabi-v7a": [
        "android-armv7",
        "android-armv7a",
        "android-arm",
        "armeabi-v7a",
        "armv7a",
        "armv7",
    ],
}

requested = [
    x.strip()
    for x in os.environ.get("TRIVOX_MULTICORE_ABIS", "arm64-v8a,armeabi-v7a").split(",")
    if x.strip()
]
ABIS = {k: ALL_ABIS[k] for k in requested if k in ALL_ABIS}
if not ABIS:
    raise SystemExit("No valid TRIVOX_MULTICORE_ABIS selected")

TOKEN = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
HDR = {
    "Accept": "application/vnd.github+json",
    "User-Agent": "trivox-multicore-preparer",
}
if TOKEN:
    HDR["Authorization"] = "Bearer " + TOKEN


def api(url):
    req = urllib.request.Request(url, headers=HDR)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def releases(repo):
    return api(f"https://api.github.com/repos/{repo}/releases?per_page=100")


def pick_release(repo):
    rels = releases(repo)
    pre = [r for r in rels if r.get("prerelease")]
    if pre:
        return pre[0]
    if rels:
        return rels[0]
    raise RuntimeError(f"no releases found for {repo}")


def download(url, dst):
    last_error = None
    for attempt in range(1, 6):
        try:
            if dst.exists():
                dst.unlink()
            req = urllib.request.Request(url, headers=HDR)
            with urllib.request.urlopen(req, timeout=240) as r, open(dst, "wb") as f:
                shutil.copyfileobj(r, f, length=1024 * 1024)
            if dst.stat().st_size <= 0:
                raise RuntimeError("downloaded empty file")
            return
        except Exception as e:
            last_error = e
            print(f"download failed attempt {attempt}/5: {e}", file=sys.stderr)
            time.sleep(min(20, attempt * 4))
    raise RuntimeError(f"download failed after retries: {last_error}")


def is_elf(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except Exception:
        return False


def safe_extract_tar(archive, tmp, mode):
    root = tmp.resolve()
    with tarfile.open(archive, mode) as t:
        for member in t.getmembers():
            target = (tmp / member.name).resolve()
            if not str(target).startswith(str(root)):
                raise RuntimeError("unsafe tar path: " + member.name)
        t.extractall(tmp)


def extract_archive(archive, tmp, binary):
    name = archive.name.lower()

    if name.endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            z.extractall(tmp)
        return

    if name.endswith(".tar.gz") or name.endswith(".tgz"):
        safe_extract_tar(archive, tmp, "r:gz")
        return

    if name.endswith(".tar.xz") or name.endswith(".txz"):
        safe_extract_tar(archive, tmp, "r:xz")
        return

    if name.endswith(".gz") and not name.endswith(".tar.gz"):
        out = tmp / binary
        with gzip.open(archive, "rb") as i, open(out, "wb") as o:
            shutil.copyfileobj(i, o)
        return

    if name.endswith(".xz") and not name.endswith(".tar.xz"):
        out = tmp / binary
        with lzma.open(archive, "rb") as i, open(out, "wb") as o:
            shutil.copyfileobj(i, o)
        return

    shutil.copy2(archive, tmp / binary)


def unpack_nested_file(path, binary):
    if is_elf(path):
        return path

    name = path.name.lower()
    tmp = path.parent / (path.name + ".unpacked")
    tmp.mkdir(exist_ok=True)

    if name.endswith(".gz") and not name.endswith(".tar.gz"):
        out = tmp / binary
        with gzip.open(path, "rb") as i, open(out, "wb") as o:
            shutil.copyfileobj(i, o)
        return out

    if name.endswith(".xz") and not name.endswith(".tar.xz"):
        out = tmp / binary
        with lzma.open(path, "rb") as i, open(out, "wb") as o:
            shutil.copyfileobj(i, o)
        return out

    return path


def find_elf_binary(tmp, binary):
    files = [p for p in tmp.rglob("*") if p.is_file()]
    expanded = []

    for p in files:
        q = unpack_nested_file(p, binary)
        if q.is_file():
            expanded.append(q)

    exact = [p for p in expanded if p.name == binary and is_elf(p)]
    if exact:
        return exact[0]

    named = [p for p in expanded if binary.lower() in p.name.lower() and is_elf(p)]
    if named:
        named.sort(key=lambda p: (len(p.name), str(p)))
        return named[0]

    elf_files = [p for p in expanded if is_elf(p)]
    elf_files = [
        p for p in elf_files
        if not re.search(r"/lib/|classes|resources|kotlin|META-INF", str(p), re.I)
    ]

    if len(elf_files) == 1:
        return elf_files[0]

    raise RuntimeError("ELF binary not found")


def extract_binary(archive, binary, dst):
    tmp = Path(tempfile.mkdtemp())
    try:
        extract_archive(archive, tmp, binary)
        found = find_elf_binary(tmp, binary)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(found, dst)
        dst.chmod(dst.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        if not is_elf(dst):
            raise RuntimeError("extracted output is not ELF: " + str(dst))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def asset_score(asset_name, abi_terms, binary):
    n = asset_name.lower()

    blocked = [
        ".apk",
        ".aab",
        "sfa-",
        "sfi-",
        "androidtv",
        "app-universal",
        "universal.apk",
        "windows",
        "darwin",
        "ios",
        "freebsd",
        "openwrt",
        "musl",
        "mips",
        "riscv",
        "loong",
        "source",
        "checksums",
        ".sha256",
    ]

    if any(x in n for x in blocked):
        return -9999

    if binary == "sing-box":
        if "sing-box" not in n:
            return -9999
        if n.startswith("sfa") or "sfa-" in n:
            return -9999

    if binary == "mihomo":
        if not any(x in n for x in ["mihomo", "clash.meta", "clash-meta"]):
            return -9999

    allowed_ext = [".zip", ".tar.gz", ".tgz", ".gz", ".xz", ".tar.xz", ".txz"]
    if not any(n.endswith(x) for x in allowed_ext):
        return -9999

    s = 0

    for t in abi_terms:
        if t in n:
            s += 80

    if "android" in n:
        s += 120

    if binary == "sing-box" and n.startswith("sing-box"):
        s += 100

    if binary == "mihomo" and n.startswith("mihomo"):
        s += 100

    if "linux" in n:
        s += 10

    if "compatible" in n:
        s -= 30

    if "debug" in n:
        s -= 500

    return s


def install(repo, binary):
    rel = pick_release(repo)
    installed = []

    for abi, terms in ABIS.items():
        assets = rel.get("assets") or []
        scored = sorted(
            [(asset_score(a["name"], terms, binary), a) for a in assets],
            key=lambda x: x[0],
            reverse=True,
        )
        scored = [x for x in scored if x[0] > 0]

        if not scored:
            names = ", ".join(a["name"] for a in assets[:80])
            raise RuntimeError(f"no valid non-APK ELF asset for {binary} {abi}. Seen: {names}")

        last_error = None

        for score, asset in scored[:8]:
            with tempfile.TemporaryDirectory() as td:
                arc = Path(td) / asset["name"]
                print("downloading", repo, rel["tag_name"], abi, asset["name"], "score", score)
                download(asset["browser_download_url"], arc)
                dst = OUT / abi / binary
                try:
                    extract_binary(arc, binary, dst)
                    sha = hashlib.sha256(dst.read_bytes()).hexdigest()
                    installed.append(
                        {
                            "abi": abi,
                            "asset": asset["name"],
                            "sha256": sha,
                            "path": str(dst.relative_to(ROOT)),
                            "size": dst.stat().st_size,
                        }
                    )
                    break
                except Exception as e:
                    last_error = e
                    print("asset rejected:", asset["name"], str(e), file=sys.stderr)
                    if dst.exists():
                        dst.unlink()
        else:
            raise RuntimeError(f"failed to extract ELF for {binary} {abi}: {last_error}")

    return {
        "repo": repo,
        "binary": binary,
        "version": rel.get("tag_name") or rel.get("name"),
        "prerelease": rel.get("prerelease"),
        "html_url": rel.get("html_url"),
        "installed": installed,
    }


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)

    data = {
        "generatedBy": "tools/prepare-multicore-binaries.py",
        "cores": [
            install("SagerNet/sing-box", "sing-box"),
            install("MetaCubeX/mihomo", "mihomo"),
        ],
    }

    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(data, indent=2))


if __name__ == "__main__":
    main()
