#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()


def text(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        return ""
    return target.read_text(encoding="utf-8")


checks: list[tuple[str, bool]] = []

def check(label: str, condition: bool) -> None:
    checks.append((label, condition))
    print(("OK  " if condition else "BAD ") + label)

parser = text("app/src/main/java/com/trivox/client/config/ConfigParser.kt")
importer = text("app/src/main/java/com/trivox/client/config/NativeConfigImporter.kt")
document = text("app/src/main/java/com/trivox/client/config/NativeProfileDocument.kt")
yaml = text("app/src/main/java/com/trivox/client/config/MiniYaml.kt")
translator = text("app/src/main/java/com/trivox/client/core/CoreConfigTranslator.kt")
selector = text("app/src/main/java/com/trivox/client/core/SmartCoreSelector.kt")
manager = text("app/src/main/java/com/trivox/client/core/CoreManager.kt")
conversion = text("app/src/main/java/com/trivox/client/core/CoreConversionManager.kt")
expansion = text("app/src/main/java/com/trivox/client/network/SubscriptionExpansionManager.kt")
subscription = text("app/src/main/java/com/trivox/client/network/SubscriptionManager.kt")
ui = text("app/src/main/java/com/trivox/client/ui/SubscriptionManagementActivity.kt")
workflow = text(".github/workflows/trivox-multicore-binaries.yml")

check("MiniYaml dependency-free parser exists", "object MiniYaml" in yaml and "parseInlineMap" in yaml)
check("Complete native document model exists", "MIHOMO_PROTOCOL" in document and "SING_BOX_PROTOCOL" in document)
check("Official sing-box remote profile URL is recognized", "import-remote-profile" in document and "remoteProfileUrl" in document)
check("Mihomo proxy-providers are resolvable", "proxy-providers" in document and "providerUrls" in document)
check("Native importer is called only once before URI parsing", parser.count("NativeConfigImporter.parseTextOrNull(text)") == 1)
check("YAML nested URLs cannot become URI schemes", "directCandidates = directLines.filter" in parser and 'any { "://" in it' not in parser)
check("Native importer preserves full documents", "parseExactDocumentOrNull" in importer)
check("Nested Mihomo ws/reality options are parsed", "ws-opts" in importer and "reality-opts" in importer)
check("Provider expansion fetcher exists", "class SubscriptionExpansionManager" in expansion and "providerUrls" in expansion)
check("Remote installer URL is unwrapped before HTTPS fetch", "NativeProfileDocument.remoteProfileUrl(url)" in subscription)
check("Core translator executes exact native configs", "NativeProfileDocument.exactRuntimeConfig" in translator)
check("Transport none is normalized to TCP", '"", "none", "raw" -> "tcp"' in translator or '"none"' in importer)
check("Smart selector runs actual core candidates", "adapter.start(config, null)" in selector and "TunnelHealthVerifier.measure" in selector)
check("Smart selector has listener and proof scoring", "listenerReady" in selector and "proof" in selector and "score" in selector)
check("CoreManager delegates smart selection", "smartCoreSelector.select(request)" in manager)
check("Conversion expands provider documents", "SubscriptionExpansionManager().expand" in conversion)
check("Converted profiles survive normal subscription refresh", "destinationSubscriptionId = null" in conversion)
raw_error = '"' + chr(10) + '" + summary.firstError'
raw_warning = '"' + chr(10) + '" + warning'
escaped_error = '"\\n" + summary.firstError'
escaped_warning = '"\\n" + warning'
check("Subscription name/row long press opens conversion", "setOnLongClickListener(conversionLongClick)" in ui and "showCoreConversion" in ui)
check("Conversion dialog newline literals are compile-safe", escaped_error in ui and escaped_warning in ui and raw_error not in ui and raw_warning not in ui)
check("Workflow reapplies patch after legacy mutators", "Apply native subscriptions and smart core fix" in workflow)
check("Workflow verifies patch before compile", "Verify native subscriptions and smart core" in workflow)

fixture_main = text("fixtures/mihomo-provider-main.yaml")
fixture_list = text("fixtures/mihomo-provider-list.yaml")
check("Mihomo provider fixture is present", "proxy-providers:" in fixture_main and "url: >-" in fixture_main)
check("Nested inline proxy fixture is present", "ws-opts:" in fixture_list and "reality-opts:" in fixture_list)

failed = [label for label, ok in checks if not ok]
if failed:
    print("\nCritical native/smart-core checks failed:")
    for label in failed:
        print(" - " + label)
    raise SystemExit(1)

print("\nAll native subscription, conversion and smart-core checks passed.")
