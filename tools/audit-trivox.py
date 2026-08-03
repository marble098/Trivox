#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class Finding:
    severity: str
    code: str
    path: str
    message: str


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def tracked_files(root: Path) -> list[Path]:
    try:
        output = subprocess.check_output(
            ["git", "-C", str(root), "ls-files", "-z"],
            stderr=subprocess.DEVNULL,
        )
        return [
            root / item.decode("utf-8")
            for item in output.split(b"\0")
            if item
        ]
    except Exception:
        return [path for path in root.rglob("*") if path.is_file()]


def audit(root: Path) -> tuple[list[Finding], dict[str, object]]:
    findings: list[Finding] = []
    files = tracked_files(root)
    kotlin = [
        path for path in files
        if path.suffix in {".kt", ".kts"} and path.is_file()
    ]
    xml_files = [
        path for path in files
        if path.suffix == ".xml" and path.is_file()
    ]

    def add(severity: str, code: str, path: Path | str, message: str) -> None:
        relative = (
            str(path.relative_to(root))
            if isinstance(path, Path) and path.is_absolute()
            else str(path)
        )
        findings.append(Finding(severity, code, relative, message))

    for path in xml_files:
        try:
            ET.parse(path)
        except Exception as error:
            add("error", "xml-parse", path, str(error))

    generated = [
        path for path in files
        if "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}
    ]
    for path in generated:
        add("error", "generated-bytecode", path, "Generated Python bytecode is tracked.")

    secret_names = {
        "signing.properties",
        "local.properties",
    }
    for path in files:
        if path.name in secret_names or path.suffix.lower() in {".jks", ".keystore"}:
            add("error", "secret-file", path, "A local/signing secret file is tracked.")

    secret_pattern = re.compile(
        r"(?i)(ghp_[a-z0-9]{20,}|github_pat_[a-z0-9_]{20,}|"
        r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)"
    )
    for path in files:
        if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
            continue
        try:
            text = read(path)
        except (UnicodeDecodeError, OSError):
            continue
        if secret_pattern.search(text):
            add("error", "secret-literal", path, "A credential/private-key pattern is tracked.")

    required = {
        "app/src/main/java/com/trivox/client/network/PingStatistics.kt":
            "REQUIRED_SUCCESS_NUMERATOR",
        "app/src/main/java/com/trivox/client/network/PingManager.kt":
            "FALLBACK_CONNECTIVITY_URLS",
        "app/src/main/java/com/trivox/client/network/NordVpnSubscriptionManager.kt":
            "?.optJSONObject(\"city\")",
        "app/src/main/java/com/trivox/client/config/ConfigParser.kt":
            "MAX_PROFILE_CANDIDATES",
        "app/src/test/java/com/trivox/client/network/PingStatisticsTest.kt":
            "twoOfThreeSamplesMeetTwoThirdsThreshold",
        "app/src/test/java/com/trivox/client/network/NordVpnCatalogParserTest.kt":
            "readsNestedCountryCityAndKeepsEveryLocation",
        "app/src/main/java/com/trivox/client/core/XrayCoreAdapter.kt":
            "logFailure = false",
        "app/src/main/java/com/trivox/client/network/SubscriptionManager.kt":
            "uri.userInfo == null",
        ".github/workflows/main.yml":
            "python3 tools/audit-trivox.py --ci",
        ".gitignore":
            "__pycache__/",
    }
    for relative, marker in required.items():
        path = root / relative
        if not path.is_file():
            add("error", "missing-file", relative, "Required audited source/test file is missing.")
        elif marker not in read(path):
            add("error", "missing-guard", relative, f"Required marker is missing: {marker}")

    nord = root / "app/src/main/java/com/trivox/client/network/NordVpnSubscriptionManager.kt"
    if nord.is_file():
        text = read(nord)
        if "InetAddress.getAllByName" in text:
            add(
                "error",
                "nord-system-dns",
                nord,
                "NordVPN API bootstrap still trusts the system resolver.",
            )
        if "MAX_COUNTRY_RESPONSE_BYTES" not in text:
            add("error", "nord-unbounded", nord, "Country response size is not bounded.")
        if "UUID.nameUUIDFromBytes" not in text:
            add("error", "nord-unstable-id", nord, "Nord locations do not use stable IDs.")
        if "ALL_COUNTRIES_TIMEOUT_SECONDS" not in text:
            add("error", "nord-global-deadline", nord, "Nord country refresh has no global deadline.")
        if "location.country.city" not in text and '?.optJSONObject("country")' not in text:
            add("error", "nord-city-schema", nord, "Nested country/city schema support is missing.")

    ping = root / "app/src/main/java/com/trivox/client/network/PingManager.kt"
    if ping.is_file():
        text = read(ping)
        if "ceil(total * REQUIRED_SUCCESS_RATIO)" in text:
            add(
                "error",
                "ping-threshold",
                ping,
                "Floating 0.67 threshold makes two-of-three samples fail.",
            )
        if "negativeDnsCache" not in text:
            add("error", "ping-negative-dns-cache", ping, "Negative DNS cache is missing.")
        if "preferredXrayTarget" not in text:
            add("error", "ping-single-target", ping, "Real delay still depends on one target.")

    main = root / "app/src/main/java/com/trivox/client/ui/MainActivity.kt"
    if main.is_file():
        text = read(main)
        if "reader.readText()" in text:
            add("error", "ui-unbounded-read", main, "A selected file is read fully on the UI path.")
        if "latencyWorker" not in text:
            add("error", "ui-worker-starvation", main, "Latency tests share the general UI worker.")
        if "drainPingResults" not in text:
            add("error", "ping-final-flush", main, "Final buffered ping results are not force-drained.")
        if "MAX_SHARED_INPUT_CHARS" not in text:
            add("error", "share-input-limit", main, "External share text has no explicit size limit.")
        if "readConfigurationText" not in text:
            add("error", "file-import-limit", main, "Selected-file import is not streamed through a bounded reader.")

    updater = root / "app/src/main/java/com/trivox/client/update/UpdateChecker.kt"
    if updater.is_file():
        text = read(updater)
        if "input.readBytes()" in text:
            add("error", "update-unbounded-read", updater, "Update response is allocated before its limit.")
        if "FAILURE_RETRY_INTERVAL_MS" not in text:
            add("error", "update-retry-policy", updater, "Transient update failures still use the daily interval.")
        if "checkInProgress" not in text:
            add("error", "update-overlap", updater, "Overlapping update checks are not prevented.")

    for path in kotlin:
        text = read(path)
        if "GlobalScope" in text or "runBlocking" in text:
            add("error", "unsafe-coroutine", path, "Global/blocking coroutine primitive found.")
        if "notifyDataSetChanged()" in text:
            add("warning", "full-list-refresh", path, "RecyclerView full-list invalidation found.")
        line_count = text.count("\n") + 1
        if line_count > 1500:
            add(
                "warning",
                "large-source-file",
                path,
                f"{line_count} lines; split responsibilities in a future architectural pass.",
            )

    manifest = root / "app/src/main/AndroidManifest.xml"
    if manifest.is_file():
        text = read(manifest)
        if 'android:allowBackup="false"' not in text:
            add("warning", "backup-policy", manifest, "Private configuration backup is not disabled.")
        if 'android:usesCleartextTraffic="false"' not in text:
            add("warning", "cleartext-policy", manifest, "Cleartext application traffic is not disabled.")

    stats = {
        "tracked_files": len(files),
        "kotlin_files": len(kotlin),
        "kotlin_lines": sum(read(path).count("\n") + 1 for path in kotlin),
        "xml_files": len(xml_files),
        "errors": sum(item.severity == "error" for item in findings),
        "warnings": sum(item.severity == "warning" for item in findings),
    }
    return findings, stats


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--ci", action="store_true")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    findings, stats = audit(root)

    if args.json:
        print(json.dumps(
            {
                "stats": stats,
                "findings": [asdict(item) for item in findings],
            },
            ensure_ascii=False,
            indent=2,
        ))
    else:
        print("Trivox technical audit")
        print(json.dumps(stats, ensure_ascii=False, indent=2))
        for item in findings:
            print(
                f"[{item.severity.upper()}] {item.code} "
                f"{item.path}: {item.message}"
            )

    return 1 if stats["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
