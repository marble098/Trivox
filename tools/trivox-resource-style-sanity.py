#!/usr/bin/env python3
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

THEMES = Path("app/src/main/res/values/themes.xml")
if not THEMES.is_file():
    print("Missing app/src/main/res/values/themes.xml", file=sys.stderr)
    sys.exit(2)

text = THEMES.read_text(encoding="utf-8")
if "AppCompat" in text:
    print("themes.xml must not depend on AppCompat", file=sys.stderr)
    sys.exit(2)

try:
    root = ET.fromstring(text)
except ET.ParseError as exc:
    print(f"themes.xml parse error: {exc}", file=sys.stderr)
    sys.exit(2)

styles = {}
for style in root.findall("style"):
    name = style.attrib.get("name", "").strip()
    if name:
        styles[name] = style.attrib.get("parent", "").strip()

required = [
    "Widget",
    "Widget.Trivox",
    "Widget.Trivox.Button",
    "Widget.Trivox.Button.Default",
    "Widget.Trivox.Button.Primary",
    "Widget.Trivox.Button.Secondary",
    "Widget.Trivox.Button.LivePing",
    "Widget.Trivox.EditText",
    "Widget.Trivox.EditText.Default",
    "Widget.Trivox.TextView",
    "Widget.Trivox.TextView.Default",
    "Widget.Trivox.Spinner",
    "Widget.Trivox.Spinner.Default",
    "Widget.Trivox.RadioButton",
    "Widget.Trivox.RadioButton.Default",
    "Widget.Trivox.CheckBox",
    "Widget.Trivox.Input",
    "Widget.Trivox.SectionTitle",
    "Widget.Trivox.FieldLabel",
    "TextAppearance",
    "TextAppearance.Trivox",
    "TextAppearance.Trivox.Toolbar",
    "ThemeOverlay",
    "ThemeOverlay.Trivox",
    "ThemeOverlay.Trivox.AlertDialog",
    "ThemeOverlay.Trivox.Popup",
    "Theme.Trivox.Base",
    "Theme.Trivox",
    "Theme.Trivox.Transparent",
]
missing = [name for name in required if name not in styles]
if missing:
    print("Missing required styles:", file=sys.stderr)
    for name in missing:
        print(f"  - {name}", file=sys.stderr)
    sys.exit(2)

for name, parent in styles.items():
    if name in {"Widget", "ThemeOverlay", "TextAppearance"}:
        continue
    if (name.startswith("Widget.") or name.startswith("ThemeOverlay.") or name.startswith("TextAppearance.")) and not parent:
        print(f"Style {name} must have an explicit parent to avoid implicit AAPT inheritance.", file=sys.stderr)
        sys.exit(2)

print("Android style root sanity check passed.")
