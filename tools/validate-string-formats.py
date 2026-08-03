#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
BASE_FILE = RES / "values" / "strings.xml"

FORMAT_RE = re.compile(
    r"%(?:(?P<position>[1-9]\d*)\$)?"
    r"(?P<flags>[-#+ 0,(<]*)"
    r"(?P<width>\d*)"
    r"(?:\.(?P<precision>\d+))?"
    r"(?:(?P<date>[tT])(?P<date_suffix>[a-zA-Z])|(?P<conversion>[a-zA-Z%]))"
)


def text_of(element: ET.Element) -> str:
    return "".join(element.itertext())


def category(date_prefix: str | None, conversion: str | None) -> str | None:
    if date_prefix:
        return "date"
    if conversion in {"%", "n"}:
        return None
    if conversion in {"d", "o", "x", "X"}:
        return "integer"
    if conversion in {"e", "E", "f", "g", "G", "a", "A"}:
        return "float"
    if conversion in {"c", "C"}:
        return "character"
    if conversion in {"b", "B"}:
        return "boolean"
    if conversion in {"h", "H"}:
        return "hash"
    if conversion in {"s", "S"}:
        return "string"
    return f"other:{conversion}"


def signature(value: str) -> dict[int, str]:
    result: dict[int, str] = {}
    next_implicit = 1
    previous_index: int | None = None

    for match in FORMAT_RE.finditer(value):
        kind = category(match.group("date"), match.group("conversion"))
        if kind is None:
            continue

        explicit = match.group("position")
        flags = match.group("flags") or ""
        if explicit is not None:
            index = int(explicit)
        elif "<" in flags:
            if previous_index is None:
                raise ValueError("relative '<' formatter has no previous argument")
            index = previous_index
        else:
            index = next_implicit
            next_implicit += 1

        previous_index = index
        old = result.get(index)
        if old is not None and old != kind:
            raise ValueError(
                f"argument {index} has conflicting conversions: {old} and {kind}"
            )
        result[index] = kind

    return result


def load_strings(path: Path) -> dict[str, tuple[str, dict[int, str]]]:
    root = ET.parse(path).getroot()
    output: dict[str, tuple[str, dict[int, str]]] = {}
    for element in root.findall("string"):
        name = element.attrib.get("name", "")
        if not name:
            continue
        value = text_of(element)
        if element.attrib.get("formatted") == "false":
            output[name] = (value, {})
            continue
        try:
            output[name] = (value, signature(value))
        except ValueError as error:
            raise SystemExit(f"{path}: string {name}: {error}") from error
    return output


def fail(message: str, errors: list[str]) -> None:
    errors.append(message)


def main() -> int:
    if not BASE_FILE.is_file():
        raise SystemExit(f"Missing base strings file: {BASE_FILE}")

    base = load_strings(BASE_FILE)
    errors: list[str] = []

    string_files = sorted(RES.glob("values*/strings.xml"))
    for path in string_files:
        current = load_strings(path)
        for name, (_, current_signature) in current.items():
            if name not in base:
                continue
            base_signature = base[name][1]
            if current_signature != base_signature:
                fail(
                    f"{path.relative_to(ROOT)}: {name} has format signature "
                    f"{current_signature}, expected {base_signature}",
                    errors,
                )

    expected_progress = {1: "string", 2: "string", 3: "string"}
    for path in string_files:
        current = load_strings(path)
        item = current.get("subscription_progress_format")
        if item is None:
            continue
        if item[1] != expected_progress:
            fail(
                f"{path.relative_to(ROOT)}: subscription_progress_format must "
                f"use string arguments {expected_progress}, found {item[1]}",
                errors,
            )

    callsites = [
        ROOT
        / "app/src/main/java/com/trivox/client/ui/"
        "SubscriptionManagementActivity.kt",
        ROOT / "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
    ]
    for path in callsites:
        text = path.read_text(encoding="utf-8")
        if "subscription_progress_format" not in text:
            fail(
                f"{path.relative_to(ROOT)}: subscription progress call is missing",
                errors,
            )
        if ".toString()" not in text:
            fail(
                f"{path.relative_to(ROOT)}: progress counts are not explicitly "
                "converted to strings",
                errors,
            )

    if errors:
        print("Android string format validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(
        "Android string format validation passed: "
        f"{len(string_files)} string tables checked."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
