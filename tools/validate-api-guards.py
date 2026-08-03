#!/usr/bin/env python3
from pathlib import Path
import sys

SOURCE = Path("app/src/main/java/com/trivox/client/util/Diagnostics.kt")


def fail(message: str) -> None:
    print(f"API guard validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def block_for(text: str, signature: str) -> str:
    start = text.find(signature)
    if start < 0:
        fail(f"missing function: {signature}")
    opening = text.find("{", start)
    if opening < 0:
        fail(f"missing function body: {signature}")

    depth = 0
    state = "normal"
    i = opening
    while i < len(text):
        if state == "normal":
            if text.startswith('"""', i):
                state = "triple"
                i += 3
                continue
            if text.startswith("//", i):
                state = "line"
                i += 2
                continue
            if text.startswith("/*", i):
                state = "block"
                i += 2
                continue
            char = text[i]
            if char == '"':
                state = "string"
            elif char == "'":
                state = "char"
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return text[start:i + 1]
        elif state == "string":
            if text[i] == "\\":
                i += 2
                continue
            if text[i] == '"':
                state = "normal"
        elif state == "char":
            if text[i] == "\\":
                i += 2
                continue
            if text[i] == "'":
                state = "normal"
        elif state == "triple":
            if text.startswith('"""', i):
                state = "normal"
                i += 3
                continue
        elif state == "line":
            if text[i] == "\n":
                state = "normal"
        elif state == "block":
            if text.startswith("*/", i):
                state = "normal"
                i += 2
                continue
        i += 1

    fail(f"unclosed function body: {signature}")
    return ""


def main() -> int:
    text = SOURCE.read_text(encoding="utf-8")

    wrapper = block_for(
        text,
        "private fun collectHistoricalExitReasons()",
    )
    api30 = block_for(
        text,
        "private fun collectHistoricalExitReasonsApi30()",
    )
    actionable = block_for(
        text,
        "private fun isActionableExit(",
    )

    if "Build.VERSION.SDK_INT" not in wrapper:
        fail("historical-exit wrapper has no runtime SDK check")
    if "Build.VERSION_CODES.R" not in wrapper:
        fail("historical-exit wrapper is not guarded for Android 11")
    if "collectHistoricalExitReasonsApi30()" not in wrapper:
        fail("guarded wrapper does not delegate to API-30 implementation")

    annotation = (
        "@android.annotation.TargetApi(\n"
        "        Build.VERSION_CODES.R\n"
        "    )\n"
        "    private fun collectHistoricalExitReasonsApi30()"
    )
    if annotation not in text:
        fail("API-30 implementation is missing its explicit TargetApi boundary")

    required_api30_markers = (
        "ApplicationExitInfo",
        "getHistoricalProcessExitReasons",
        "traceInputStream",
    )
    for marker in required_api30_markers:
        if marker not in api30:
            fail(f"API-30 implementation is missing {marker}")

    if "ApplicationExitInfo" in actionable:
        fail("isActionableExit must accept primitive values, not API-30 objects")
    if "reason: Int" not in actionable:
        fail("isActionableExit must accept a primitive reason")
    if "descriptionValue: String" not in actionable:
        fail("isActionableExit must accept a plain description")

    print("Android API guard validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
