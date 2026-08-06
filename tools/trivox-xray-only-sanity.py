#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main"

BANNED = {
    "mihomo": "mihomo core must stay removed",
    "sing-box": "sing-box core must stay removed",
    "sing_box": "sing-box core must stay removed",
    "sing box": "sing-box core must stay removed",
    "CoreId": "multi-core selector must stay removed",
    "smartCoreSelection": "smart selector must stay removed",
    "lastSmartCoreId": "smart selector must stay removed",
    "preferredTestCore": "smart selector must stay removed",
    "OpenSshProfileCodec": "OpenSSH runtime must stay removed",
    "NordWhisperCompatibility": "removed non-Xray compatibility must stay removed",
    "androidx.appcompat": "AppCompat must stay removed from the lightweight build",
}

ALLOW_FILES = {
    "tools/trivox-xray-only-sanity.py",
    "tools/trivox-self-heal.py",
    "docs/TRIVOX_XRAY_ONLY_RELEASE.md",
}

REQUIRED = [
    "app/src/main/java/com/trivox/client/config/ConfigParser.kt",
    "app/src/main/java/com/trivox/client/config/ProxyChainCodec.kt",
    "app/src/main/java/com/trivox/client/config/NativeProfileDocument.kt",
    "app/src/main/java/com/trivox/client/config/NativeConfigImporter.kt",
    "app/src/main/java/com/trivox/client/core/CoreManager.kt",
    "app/src/main/assets/core-manifest.json",
]


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def scan_text_files() -> list[str]:
    problems: list[str] = []
    roots = [ROOT / "app" / "src" / "main" / "java", ROOT / "app" / "build.gradle.kts", ROOT / "build.gradle.kts"]
    for root in roots:
        paths = root.rglob("*") if root.is_dir() else [root]
        for path in paths:
            if not path.is_file():
                continue
            rp = rel(path)
            if rp in ALLOW_FILES:
                continue
            if path.suffix not in {".kt", ".kts", ".xml", ".json", ".pro"}:
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            lower = text.lower()
            for token, reason in BANNED.items():
                haystack = lower if token.lower() == token else text
                needle = token.lower() if token.lower() == token else token
                if needle in haystack:
                    problems.append(f"{rp}: {reason} ({token})")
    return problems


def verify_required() -> list[str]:
    problems: list[str] = []
    for item in REQUIRED:
        if not (ROOT / item).exists():
            problems.append(f"missing required file: {item}")
    gradle = ROOT / "app" / "build.gradle.kts"
    if gradle.exists() and "GIT_SHA" not in gradle.read_text(encoding="utf-8", errors="ignore"):
        problems.append("app/build.gradle.kts does not declare BuildConfig.GIT_SHA")
    manifest = ROOT / "app" / "src" / "main" / "assets" / "core-manifest.json"
    if manifest.exists():
        text = manifest.read_text(encoding="utf-8", errors="ignore")
        if "Xray" not in text and "xray" not in text:
            problems.append("core-manifest.json does not describe Xray")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-only", action="store_true")
    _ = parser.parse_args()
    problems = scan_text_files() + verify_required()
    if problems:
        print("Xray-only sanity check failed:", file=sys.stderr)
        for problem in problems:
            print(f" - {problem}", file=sys.stderr)
        return 2
    print("Xray-only sanity check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
