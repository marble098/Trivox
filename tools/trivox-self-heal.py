#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
RES = APP / "src" / "main" / "res"
VALUES = RES / "values"
SRC = APP / "src" / "main" / "java"

BASE_STRING_FILE = VALUES / "trivox_generated_missing_defaults.xml"
STYLE_ROOT_FILE = VALUES / "trivox_generated_style_roots.xml"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def write_if_changed(path: Path, text: str, apply: bool) -> bool:
    old = read(path)
    if old == text:
        return False
    if apply:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return True


def patch_text(path: Path, replacements: list[tuple[str, str]], apply: bool) -> bool:
    text = read(path)
    if not text:
        return False
    updated = text
    for old, new in replacements:
        updated = updated.replace(old, new)
    return write_if_changed(path, updated, apply)


def ensure_git_sha(app_gradle: Path, apply: bool) -> bool:
    text = read(app_gradle)
    if not text or "GIT_SHA" in text:
        return False
    marker = "buildConfigField(\"String\", \"XRAY_VERSION\""
    idx = text.find(marker)
    if idx < 0:
        return False
    line_start = text.rfind("\n", 0, idx) + 1
    line_end = text.find("\n", idx)
    if line_end < 0:
        line_end = len(text)
    indent = re.match(r"\s*", text[line_start:line_end]).group(0)
    insert = (
        f"\n{indent}val trivoxGitSha = providers.environmentVariable(\"GITHUB_SHA\")"
        f".orElse(\"local\").get().take(12)"
        f"\n{indent}buildConfigField(\"String\", \"GIT_SHA\", \"\\\"$trivoxGitSha\\\"\")"
    )
    updated = text[:line_end] + insert + text[line_end:]
    return write_if_changed(app_gradle, updated, apply)


def collect_strings() -> tuple[dict[str, str], dict[str, str]]:
    base: dict[str, str] = {}
    localized: dict[str, str] = {}
    if not RES.exists():
        return base, localized
    for file in RES.glob("values*/**/*.xml"):
        if file.name == BASE_STRING_FILE.name:
            continue
        try:
            root = ET.parse(file).getroot()
        except Exception:
            continue
        is_base = file.parent.name == "values"
        for node in root.findall("string"):
            name = node.attrib.get("name", "").strip()
            if not name:
                continue
            value = "".join(node.itertext()).strip()
            if is_base:
                base[name] = value
            else:
                localized.setdefault(name, value)
    return base, localized


def nice_default(name: str, sample: str) -> str:
    sample = re.sub(r"\s+", " ", sample).strip()
    if sample:
        return sample
    return name.replace("_", " ").strip().capitalize()


def generate_missing_default_strings(apply: bool) -> bool:
    base, localized = collect_strings()
    missing = {name: value for name, value in localized.items() if name not in base}
    if not missing:
        return write_if_changed(BASE_STRING_FILE, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n", apply)
    lines = ["<?xml version=\"1.0\" encoding=\"utf-8\"?>", "<resources>"]
    for name in sorted(missing):
        value = html.escape(nice_default(name, missing[name]), quote=False)
        lines.append(f"    <string name=\"{name}\">{value}</string>")
    lines.append("</resources>")
    return write_if_changed(BASE_STRING_FILE, "\n".join(lines) + "\n", apply)


def collect_styles() -> set[str]:
    styles: set[str] = set()
    if not RES.exists():
        return styles
    for file in RES.glob("values*/**/*.xml"):
        if file.name == STYLE_ROOT_FILE.name:
            continue
        try:
            root = ET.parse(file).getroot()
        except Exception:
            continue
        for node in root.findall("style"):
            name = node.attrib.get("name", "").strip()
            if name:
                styles.add(name)
    return styles


def generate_style_roots(apply: bool) -> bool:
    styles = collect_styles()
    needed = {"Widget", "ThemeOverlay"}
    for style in list(styles):
        parts = style.split(".")
        for i in range(1, len(parts)):
            needed.add(".".join(parts[:i]))
    missing = sorted(needed - styles)
    lines = ["<?xml version=\"1.0\" encoding=\"utf-8\"?>", "<resources>"]
    for name in missing:
        lines.append(f"    <style name=\"{name}\" />")
    lines.append("</resources>")
    return write_if_changed(STYLE_ROOT_FILE, "\n".join(lines) + "\n", apply)


def patch_appcompat(apply: bool) -> bool:
    changed = False
    for path in SRC.rglob("*.kt"):
        changed = patch_text(
            path,
            [("import androidx.appcompat.app.AlertDialog", "import android.app.AlertDialog")],
            apply,
        ) or changed
    return changed


def remove_removed_core_references(apply: bool) -> bool:
    changed = False
    for path in SRC.rglob("*.kt"):
        text = read(path)
        if not text:
            continue
        updated = text
        updated = re.sub(r'\s*"ssh",\s*"openssh"\s*->\s*OpenSshProfileCodec\.parse\(raw\)\n', "\n", updated)
        updated = re.sub(r'\s*"nordwhisper"\s*->\s*NordWhisperCompatibility\.reject\(raw\)\n', "\n", updated)
        if updated != text:
            changed = write_if_changed(path, updated, apply) or changed
    return changed


def run_sanity() -> list[str]:
    problems: list[str] = []
    banned = [
        "OpenSshProfileCodec",
        "NordWhisperCompatibility",
        "androidx.appcompat",
        "CoreId",
        "smartCoreSelection",
        "lastSmartCoreId",
        "preferredTestCore",
        "switchCore(",
        "smartSelect(",
    ]
    allow = {
        "ProxyChainCodec.kt",
        "NativeProfileDocument.kt",
    }
    for path in SRC.rglob("*.kt"):
        if path.name in allow:
            continue
        text = read(path)
        for token in banned:
            if token in text:
                problems.append(f"{path.relative_to(ROOT)} still contains {token}")
    if not (SRC / "com" / "trivox" / "client" / "config" / "ConfigParser.kt").exists():
        problems.append("ConfigParser.kt is missing")
    if not (SRC / "com" / "trivox" / "client" / "config" / "ProxyChainCodec.kt").exists():
        problems.append("ProxyChainCodec.kt is missing")
    if not (SRC / "com" / "trivox" / "client" / "config" / "NativeProfileDocument.kt").exists():
        problems.append("NativeProfileDocument.kt is missing")
    if "GIT_SHA" not in read(APP / "build.gradle.kts"):
        problems.append("BuildConfig.GIT_SHA is not declared in app/build.gradle.kts")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    apply = args.apply and not args.check

    changed = False
    changed = patch_appcompat(apply) or changed
    changed = remove_removed_core_references(apply) or changed
    changed = ensure_git_sha(APP / "build.gradle.kts", apply) or changed
    changed = generate_missing_default_strings(apply) or changed
    changed = generate_style_roots(apply) or changed

    problems = run_sanity()
    if problems:
        for problem in problems:
            print(f"ERROR: {problem}")
        return 2
    if args.check and changed:
        print("Self-heal would modify files; run with --apply")
        return 3
    print("Trivox self-heal OK" + ("; changes applied" if changed and apply else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
