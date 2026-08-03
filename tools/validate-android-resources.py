#!/usr/bin/env python3

from __future__ import annotations

from collections import defaultdict
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
JAVA_ROOT = ROOT / "app/src/main/java"

STRING_REFERENCE = re.compile(
    r"@string/([A-Za-z_][A-Za-z0-9_]*)"
)
KOTLIN_STRING_REFERENCE = re.compile(
    r"(?<!android\.)\bR\.string\.([A-Za-z_][A-Za-z0-9_]*)"
)


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def parse_xml(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as error:
        fail(f"Invalid Android XML in {path.relative_to(ROOT)}: {error}")


def strings_from_values(directory: Path) -> dict[str, list[Path]]:
    definitions: dict[str, list[Path]] = defaultdict(list)

    if not directory.is_dir():
        return definitions

    for path in sorted(directory.glob("*.xml")):
        root = parse_xml(path)

        for element in root:
            name = element.attrib.get("name", "").strip()

            if not name:
                continue

            if element.tag == "string":
                definitions[name].append(path)
                continue

            if (
                element.tag == "item" and
                element.attrib.get("type") == "string"
            ):
                definitions[name].append(path)

    return definitions


def xml_references(path: Path) -> set[str]:
    root = parse_xml(path)
    references: set[str] = set()

    for element in root.iter():
        values = list(element.attrib.values())

        if element.text:
            values.append(element.text)

        if element.tail:
            values.append(element.tail)

        for value in values:
            references.update(
                STRING_REFERENCE.findall(value)
            )

    return references


def kotlin_code_only(content: str) -> str:
    output = list(content)
    index = 0
    state = "code"
    block_depth = 0

    def blank(start: int, end: int) -> None:
        for position in range(start, min(end, len(output))):
            if output[position] != "\n":
                output[position] = " "

    while index < len(content):
        if state == "code":
            if content.startswith("//", index):
                blank(index, index + 2)
                state = "line_comment"
                index += 2
                continue

            if content.startswith("/*", index):
                blank(index, index + 2)
                state = "block_comment"
                block_depth = 1
                index += 2
                continue

            if content.startswith('"""', index):
                blank(index, index + 3)
                state = "raw_string"
                index += 3
                continue

            if content[index] == '"':
                blank(index, index + 1)
                state = "string"
                index += 1
                continue

            if content[index] == "'":
                blank(index, index + 1)
                state = "character"
                index += 1
                continue

            index += 1
            continue

        if state == "line_comment":
            if content[index] == "\n":
                state = "code"
                index += 1
                continue

            blank(index, index + 1)
            index += 1
            continue

        if state == "block_comment":
            if content.startswith("/*", index):
                blank(index, index + 2)
                block_depth += 1
                index += 2
                continue

            if content.startswith("*/", index):
                blank(index, index + 2)
                block_depth -= 1
                index += 2

                if block_depth == 0:
                    state = "code"

                continue

            blank(index, index + 1)
            index += 1
            continue

        if state == "raw_string":
            if content.startswith('"""', index):
                blank(index, index + 3)
                state = "code"
                index += 3
                continue

            blank(index, index + 1)
            index += 1
            continue

        if state in {"string", "character"}:
            if content[index] == "\\":
                blank(index, index + 2)
                index += 2
                continue

            closing = '"' if state == "string" else "'"

            if content[index] == closing:
                blank(index, index + 1)
                state = "code"
                index += 1
                continue

            blank(index, index + 1)
            index += 1
            continue

        fail(f"Unknown Kotlin scanner state: {state}")

    if state not in {"code", "line_comment"}:
        fail(f"Unterminated Kotlin {state.replace('_', ' ')}")

    return "".join(output)


def main() -> None:
    if not RES.is_dir():
        fail("Android resource directory is missing.")

    default_definitions = strings_from_values(
        RES / "values"
    )
    persian_definitions = strings_from_values(
        RES / "values-fa"
    )

    duplicates = {
        name: paths
        for name, paths in default_definitions.items()
        if len(paths) > 1
    }

    if duplicates:
        lines = ["Duplicate default string resources detected:"]

        for name, paths in sorted(duplicates.items()):
            locations = ", ".join(
                str(path.relative_to(ROOT))
                for path in paths
            )
            lines.append(f"  {name}: {locations}")

        fail("\n".join(lines))

    required_bilingual = {
        "wireguard_preshared_key",
    }

    for name in sorted(required_bilingual):
        if name not in default_definitions:
            fail(
                f"Required default string resource is missing: {name}"
            )

        if name not in persian_definitions:
            fail(
                f"Required Persian string resource is missing: {name}"
            )

    references: dict[str, set[Path]] = defaultdict(set)

    xml_files = sorted(RES.rglob("*.xml"))

    if MANIFEST.is_file():
        xml_files.append(MANIFEST)

    for path in xml_files:
        for name in xml_references(path):
            references[name].add(path)

    if JAVA_ROOT.is_dir():
        for path in sorted(JAVA_ROOT.rglob("*.kt")):
            code = kotlin_code_only(
                path.read_text(encoding="utf-8")
            )

            for name in KOTLIN_STRING_REFERENCE.findall(code):
                references[name].add(path)

    missing = {
        name: paths
        for name, paths in references.items()
        if name not in default_definitions
    }

    if missing:
        lines = ["Undefined Android string resources detected:"]

        for name, paths in sorted(missing.items()):
            locations = ", ".join(
                str(path.relative_to(ROOT))
                for path in sorted(paths)
            )
            lines.append(f"  {name}: {locations}")

        fail("\n".join(lines))

    forbidden = "wireguard_psk"

    if forbidden in references:
        fail(
            "Legacy WireGuard resource alias is still referenced: "
            + forbidden
        )

    print(
        "Android resource validation passed: "
        f"{len(default_definitions)} default strings, "
        f"{len(references)} referenced strings."
    )


if __name__ == "__main__":
    main()
