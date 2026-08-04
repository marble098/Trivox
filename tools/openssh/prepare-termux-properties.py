#!/usr/bin/env python3
from pathlib import Path
import re
import sys


def fail(message: str):
    raise SystemExit(message)


def resolve_properties_path(raw: str) -> Path:
    source = Path(raw)

    if source.is_file():
        return source

    if not source.is_dir():
        fail(f"Termux packages path does not exist: {source}")

    candidates = (
        source / "scripts" / "properties.sh",
        source / "properties.sh",
    )
    existing = [path for path in candidates if path.is_file()]

    if len(existing) == 1:
        return existing[0]
    if len(existing) > 1:
        return source / "scripts" / "properties.sh"

    fallback = [
        path
        for path in source.rglob("properties.sh")
        if ".git" not in path.parts and path.is_file()
    ]
    if len(fallback) == 1:
        return fallback[0]

    fail(
        "Unable to locate a unique Termux properties.sh under "
        f"{source}; found {len(fallback)} candidates."
    )


def replace_single_assignment(text: str, key: str, value: str) -> str:
    pattern = re.compile(rf"^{re.escape(key)}=.*$", re.MULTILINE)
    matches = pattern.findall(text)
    if len(matches) != 1:
        fail(f"Expected exactly one {key} assignment, found {len(matches)}")
    return pattern.sub(f'{key}="{value}"', text, count=1)


if len(sys.argv) != 3:
    fail(
        "Usage: prepare-termux-properties.py "
        "<termux-packages-dir-or-properties-file> <package-name>"
    )

package_name = sys.argv[2].strip()
if not re.fullmatch(
    r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+",
    package_name,
):
    fail(f"Invalid Android package name: {package_name}")

path = resolve_properties_path(sys.argv[1])
text = path.read_text(encoding="utf-8")
text = replace_single_assignment(text, "TERMUX_APP__PACKAGE_NAME", package_name)
text = replace_single_assignment(
    text,
    "TERMUX_APP__DATA_DIR",
    f"/data/data/{package_name}",
)
path.write_text(text, encoding="utf-8", newline="\n")

verified = path.read_text(encoding="utf-8")
expected_package = f'TERMUX_APP__PACKAGE_NAME="{package_name}"'
expected_data = f'TERMUX_APP__DATA_DIR="/data/data/{package_name}"'
if expected_package not in verified or expected_data not in verified:
    fail(f"Failed to verify patched Termux properties: {path}")

print(f"Patched Termux properties: {path}")
