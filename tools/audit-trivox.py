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
        ignored = {".git", ".gradle", "build", "__pycache__"}
        return [
            path for path in root.rglob("*")
            if path.is_file() and not any(part in ignored for part in path.parts)
            and path.suffix not in {".pyc", ".pyo"}
        ]


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
        "app/src/test/java/com/trivox/client/network/RealDelayPolicyP2Test.kt":
            "turboKeepsFastProofAndAddsFailureOnlyRescue",
        "app/src/test/java/com/trivox/client/config/ConfigParserHysteria2P2Test.kt":
            "importsStandardHysteria2SalamanderShareLink",
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
        "app/src/main/java/com/trivox/client/service/NetworkHandoverCoordinator.kt":
            "TRIVOX_P1_HANDOVER_COORDINATOR",
        "app/src/test/java/com/trivox/client/service/NetworkHandoverCoordinatorTest.kt":
            "availableBeforeOldLostDoesNotReenterReconnect",
        "app/src/test/java/com/trivox/client/util/DiagnosticsSanitizerP1Test.kt":
            "redactsSshUriQuotedSecretsAndAuthorization",
        "app/src/main/java/com/trivox/client/network/SubscriptionManager.kt":
            "fun normalizeUrl",
        "app/src/main/java/com/trivox/client/network/SubscriptionSupport.kt":
            "isSubscriptionCancellation",
        "app/src/main/java/com/trivox/client/network/SubscriptionRefreshCoordinator.kt":
            "mergeSubscription",
        "app/src/main/java/com/trivox/client/ui/SubscriptionManagementActivity.kt":
            "runOnUiThreadIfAlive",
        "app/src/main/java/com/trivox/client/util/Diagnostics.kt":
            "isActionableExit",
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
        if "BATCH_XRAY_ATTEMPTS = 1" not in text:
            add(
                "error",
                "ping-batch-verification",
                ping,
                "Failure-only isolated Real Delay rescue must use one required verified sample.",
            )
        if (
            "BATCH_XRAY_MAX_TARGETS = 4" not in text
            or "allowSingleSample = true" not in text
            or "waitForLocalProxyListener" not in text
        ):
            add(
                "error",
                "ping-batch-fallback",
                ping,
                "Batch Real Delay rescue lost listener readiness or verified target diversity.",
            )
        if "statusCode in 200..299" not in text:
            add("error", "ping-redirect", ping, "HTTP redirects can still be accepted as successful probes.")

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
        if "showConnectionStartPending" not in text:
            add("error", "connect-feedback", main, "First-tap connection feedback is missing.")
        if "subscription_progress_format" not in text:
            add("error", "subscription-progress", main, "Main subscription refresh has no per-source progress.")
        if "activityDestroyed" not in text or "runOnUiThreadIfAlive" not in text:
            add("error", "ui-lifecycle-callback", main, "Background callbacks are not guarded after Activity destruction.")
        if "startConnectionService" not in text:
            add("error", "connect-start-recovery", main, "Foreground-service start failures do not restore the connection UI.")
        if "pingManager.tcp(" in text or "PingMethod.TCP_CONNECT" in text:
            add("error", "active-tcp-ping-ui", main, "TCP Connect ping returned to active MainActivity paths.")
        if "realLatencyMs" not in text:
            add("error", "real-delay-storage", main, "Real Delay storage/ranking marker is missing.")
        if "showSubscriptionActions" not in text:
            add("error", "subscription-cleanup-menu", main, "Subscription long-press cleanup actions are missing.")


    if ping.is_file():
        ping_text = read(ping)
        for marker in ("fun tcp(", "fun batchTcp(", "fun wireGuardTcp(", "trivox-tcp-ping", "PingMethod.TCP_CONNECT"):
            if marker in ping_text:
                add("error", "active-tcp-ping-engine", ping, f"Active TCP Ping implementation returned: {marker}")
        if "IcmpProbe" in ping_text or "fun icmp(" in ping_text:
            add("error", "icmp-returned", ping, "ICMP implementation returned to PingManager.")

    icmp_probe = root / "app/src/main/java/com/trivox/client/network/IcmpProbe.kt"
    if icmp_probe.exists():
        add("error", "icmp-file-returned", icmp_probe, "ICMP source file must stay deleted.")

    parser = root / "app/src/main/java/com/trivox/client/config/ConfigParser.kt"
    if parser.is_file():
        parser_text = read(parser)
        for marker in ("hysteriaFinalMask", '"salamander"', '"obfs-password"', '"finalmask"'):
            if marker not in parser_text:
                add("error", "hysteria2-salamander-regression", parser, f"Hysteria2 marker is missing: {marker}")

    real_policy = root / "app/src/main/java/com/trivox/client/network/RealDelayPolicy.kt"
    if real_policy.is_file():
        policy_text = read(real_policy)
        if (
            "rescueTargets" not in policy_text
            or "rescueProbeTimeoutMs" not in policy_text
            or "VerifiedHttpProbe.strongTraceTarget" not in policy_text
        ):
            add(
                "error",
                "turbo-rescue-regression",
                real_policy,
                "RootFix Real Delay rescue policy or DNS-free strong proof is missing.",
            )

    profile_adapter = root / "app/src/main/java/com/trivox/client/ui/ProfileAdapter.kt"
    if profile_adapter.is_file():
        adapter_text = read(profile_adapter)
        if "onTcpPing" in adapter_text:
            add("error", "tcp-ping-adapter-ui", profile_adapter, "Profile adapter exposes TCP Ping.")
        if "tcpLatency.visibility = View.GONE" not in adapter_text:
            add("error", "tcp-metric-visible", profile_adapter, "Legacy TCP metric view is not hidden.")

    xray_builder = root / "app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt"
    p3_optimizer = root / "app/src/main/java/com/trivox/client/network/SmartConnectionOptimizer.kt"
    p3_fragment = root / "app/src/main/java/com/trivox/client/config/XrayFragmentPolicy.kt"
    p3_bootstrap = root / "app/src/main/java/com/trivox/client/network/EndpointBootstrapResolver.kt"
    for path, marker in (
        (p3_optimizer, "verificationPlan"),
        (p3_fragment, "XrayFragmentPolicy"),
        (p3_bootstrap, "EndpointBootstrapResolver"),
    ):
        if not path.is_file() or marker not in read(path):
            add("error", "p3-smart-connection", path, f"P3 smart connection marker missing: {marker}")

    if main.is_file():
        p3_main_text = read(main)
        for marker in ("requestLivePingNow", "livePingTick", "composeLivePing", "liveIcmp"):
            if marker in p3_main_text:
                add("error", "automatic-live-ping-returned", main, f"Removed Automatic Live Ping marker returned: {marker}")

    if xray_builder.is_file():
        p3_xray = read(xray_builder)
        for marker in ('"dialerProxy"', 'FRAGMENT_OUTBOUND_TAG', 'installBootstrapHosts', 'serveExpiredTTL'):
            if marker not in p3_xray:
                add("error", "p3-xray-runtime", xray_builder, f"P3 Xray runtime marker missing: {marker}")

    leak_v2 = root / "app/src/main/java/com/trivox/client/network/LeakProtectionManager.kt"
    if leak_v2.is_file():
        leak_text = read(leak_v2)
        for marker in ("riskScore", "ipv6LeakRisk", "EXIT_PROBE_URLS", "routedPackages = emptySet()"):
            if marker not in leak_text:
                add("error", "leak-guard-v2", leak_v2, f"Leak Guard V2 marker missing: {marker}")

    coordinator = root / "app/src/main/java/com/trivox/client/network/SubscriptionRefreshCoordinator.kt"
    if coordinator.is_file():
        text = read(coordinator)
        if "activeTask" not in text or "closed" not in text:
            add("error", "subscription-lifecycle", coordinator, "Subscription refresh work is not owned and cancelled by its coordinator.")

    diagnostics = root / "app/src/main/java/com/trivox/client/util/Diagnostics.kt"
    if diagnostics.is_file():
        text = read(diagnostics)
        if "isActionableExit" not in text:
            add("error", "diagnostics-noise", diagnostics, "Non-actionable process exits are not filtered.")
        if "submitRuntimeWrite" not in text:
            add("error", "diagnostics-blocking", diagnostics, "Routine log writes still block caller threads.")
        if "isCancellation" not in text:
            add("error", "diagnostics-cancellation", diagnostics, "Expected cancellation is logged as a crash.")

    updater = root / "app/src/main/java/com/trivox/client/update/UpdateChecker.kt"
    if updater.is_file():
        text = read(updater)
        if "input.readBytes()" in text:
            add("error", "update-unbounded-read", updater, "Update response is allocated before its limit.")
        if "FAILURE_RETRY_INTERVAL_MS" not in text:
            add("error", "update-retry-policy", updater, "Transient update failures still use the daily interval.")
        if "checkInProgress" not in text:
            add("error", "update-overlap", updater, "Overlapping update checks are not prevented.")

    layout_dir = root / "app/src/main/res/layout"
    if layout_dir.exists():
        add("error", "legacy-layout-dir", layout_dir, "Legacy app layout resources remain after the Compose migration.")

    main_compose = root / "app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt"
    if not main_compose.is_file():
        add("error", "compose-main-missing", main_compose, "The Material 3 main screen is missing.")
    else:
        text = read(main_compose)
        if "NavigationBar" not in text or "MainTab.entries" not in text:
            add("error", "compose-bottom-tabs", main_compose, "Bottom-tab navigation is missing.")
        if "favoritesOnly" not in text:
            add("error", "compose-favorites-filter", main_compose, "Favorites filtering is missing from the Compose profile surface.")

    build_script = root / "app/build.gradle.kts"
    if build_script.is_file():
        build_text = read(build_script)
        if "com.wireguard.android:tunnel" in build_text:
            add(
                "error",
                "native-wireguard-dependency",
                build_script,
                "The crash-prone libwg-go WireGuard Android backend returned.",
            )

    proguard_rules = root / "app/proguard-rules.pro"
    if proguard_rules.is_file() and "com.wireguard.android" in read(proguard_rules):
        add(
            "error",
            "native-wireguard-proguard",
            proguard_rules,
            "Stale WireGuard Android backend keep rules returned.",
        )

    vpn_service = root / "app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt"
    if vpn_service.is_file() and "NativeWireGuardManager" in read(vpn_service):
        add(
            "error",
            "native-wireguard-runtime",
            vpn_service,
            "VPN service must use Xray WireGuard only after the v24 native crash.",
        )

    models = root / "app/src/main/java/com/trivox/client/data/Models.kt"
    if models.is_file():
        models_text = read(models)
        if "nativeWireGuardVpn: Boolean = true" in models_text:
            add(
                "error",
                "native-wireguard-default",
                models,
                "Legacy nativeWireGuardVpn must never default to true.",
            )
        strategy_start = models_text.find("WIREGUARD_DOMAIN_STRATEGIES")
        if strategy_start >= 0 and '"AsIs"' in models_text[strategy_start:]:
            add(
                "error",
                "wireguard-domain-strategy",
                models,
                "WireGuard settings expose unsupported AsIs.",
            )

    geometry = root / "app/src/main/java/com/trivox/client/ui/compose/WorldMapGeometry.kt"
    if not geometry.is_file():
        add(
            "error",
            "world-map-geometry",
            geometry,
            "Detailed dependency-free world geometry is missing.",
        )
    elif read(geometry).count("floatArrayOf(") < 220:
        add(
            "error",
            "world-map-detail",
            geometry,
            "World map geometry regressed to an overly coarse shape set.",
        )

    if main.is_file():
        main_text = read(main)
        single_start = main_text.find("private fun testSingleProfile")
        single_end = main_text.find("private fun testProfiles", single_start)
        single_text = (
            main_text[single_start:single_end]
            if single_start >= 0 and single_end > single_start
            else ""
        )
        if "latencyWorker.submit" not in single_text or "trackPingTasks" not in single_text:
            add(
                "error",
                "single-test-worker",
                main,
                "Single-profile tests must be cancellable and must not block storageWorker.",
            )

    for path in kotlin:
        text = read(path)
        if "GlobalScope" in text or "runBlocking" in text:
            add("error", "unsafe-coroutine", path, "Global/blocking coroutine primitive found.")
        if "notifyDataSetChanged()" in text:
            add("warning", "full-list-refresh", path, "RecyclerView full-list invalidation found.")
        if "Executors.newCachedThreadPool" in text:
            add(
                "warning",
                "unbounded-executor",
                path,
                "Unbounded cached executor can amplify memory pressure.",
            )
        if "/ui/" in path.as_posix() and "Thread.sleep(" in text:
            add(
                "warning",
                "ui-thread-sleep-review",
                path,
                "UI package contains Thread.sleep; verify it never runs on the main thread.",
            )
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

    network_security = root / "app/src/main/res/xml/network_security_config.xml"
    if network_security.is_file():
        network_security_text = read(network_security)
        if 'cleartextTrafficPermitted="true"' in network_security_text:
            add(
                "error",
                "cleartext-domain-exception",
                network_security,
                "A cleartext domain exception is enabled in the app network policy.",
            )

    if vpn_service.is_file():
        vpn_text = read(vpn_service)
        if "NetworkHandoverCoordinator" not in vpn_text:
            add(
                "error",
                "vpn-handover-coordinator",
                vpn_service,
                "VPN default-network handover is not using the tested coordinator.",
            )
        if "var observedInitial = false" in vpn_text:
            add(
                "error",
                "vpn-order-sensitive-handover",
                vpn_service,
                "The old order-sensitive default-network callback returned.",
            )

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
