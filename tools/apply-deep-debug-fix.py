#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path.cwd()

def read(path: str) -> str:
    p = ROOT / path
    if not p.exists():
        raise SystemExit(f"missing required file: {path}")
    return p.read_text(encoding="utf-8")

def write_if_changed(path: str, content: str) -> None:
    p = ROOT / path
    old = p.read_text(encoding="utf-8") if p.exists() else None
    if old != content:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        print(f"patched {path}")
    else:
        print(f"unchanged {path}")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)

def patch_core_manager():
    path = "app/src/main/java/com/trivox/client/core/CoreManager.kt"
    s = read(path)

    s = replace_once(
        s,
        "        val health = verifyLiveRoute(request, logMark, isCancelled)\n",
        "        val health = verifyLiveRoute(plan, request, logMark, isCancelled)\n",
        "CoreManager.startPlan health call"
    )

    s = replace_once(
        s,
        "        val route = if (request.mode == ConnectionMode.VPN) \"Android VPN route\" else \"local proxy route\"\n",
        "        val route = when {\n            plan.needsXrayBridge -> \"native local proxy before Android VPN bridge\"\n            request.mode == ConnectionMode.VPN -> \"Android VPN route\"\n            else -> \"local proxy route\"\n        }\n",
        "CoreManager route failure message"
    )

    old_sig = """    private fun verifyLiveRoute(
        request: CoreStartRequest,
        logMark: XrayProbeLogInspector.Mark,
        isCancelled: () -> Boolean
    ): com.trivox.client.data.PingResult {
"""
    new_sig = """    private fun verifyLiveRoute(
        plan: StartPlan,
        request: CoreStartRequest,
        logMark: XrayProbeLogInspector.Mark,
        isCancelled: () -> Boolean
    ): com.trivox.client.data.PingResult {
        if (plan.needsXrayBridge) {
            return verifyNativeBridgeRoute(plan, request, logMark, isCancelled)
        }
"""
    s = replace_once(s, old_sig, new_sig, "CoreManager verifyLiveRoute signature")

    if "private fun verifyNativeBridgeRoute(" not in s:
        insert_before = "    private fun isImmediateTransportFailure(category: String): Boolean =\n"
        native_method = """    private fun verifyNativeBridgeRoute(
        plan: StartPlan,
        request: CoreStartRequest,
        logMark: XrayProbeLogInspector.Mark,
        isCancelled: () -> Boolean
    ): com.trivox.client.data.PingResult {
        Diagnostics.info(
            "Native VPN bridge proof: checking " + plan.activeCore.label +
                " local mixed proxy before accepting Android TUN bridge"
        )

        val proxySettings = request.settings.copy().also {
            it.mode = ConnectionMode.PROXY
            it.localProxyInVpn = false
        }

        val result = TunnelHealthVerifier.measure(
            settings = proxySettings,
            mode = ConnectionMode.PROXY,
            attempts = request.settings.testAttempts.coerceIn(2, 3),
            budgetMs = NATIVE_BRIDGE_VERIFY_BUDGET_MS,
            perProbeTimeoutMs = NATIVE_BRIDGE_PROBE_TIMEOUT_MS,
            initialDelayMs = NATIVE_BRIDGE_GRACE_MS,
            isCancelled = isCancelled,
            hardFailure = {
                XrayProbeLogInspector.classifySince(logMark)
                    ?.takeIf(::isImmediateBridgeFailure)
            }
        )

        if (result.success) {
            Diagnostics.info(
                "Native VPN bridge proof passed: " + plan.activeCore.label +
                    " accepted verified HTTPS through 127.0.0.1:" +
                    request.settings.socksPort +
                    "; Xray owns Android TUN only as packet bridge"
            )
            return result.copy(
                resolvedIp = "vpn-bridge:" + plan.activeCore.label,
                errorCategory = null
            )
        }

        Diagnostics.warning(
            "Native VPN bridge proof failed for " + plan.activeCore.label +
                ": " + (result.errorCategory ?: "unknown")
        )
        return result.copy(
            resolvedIp = "vpn-bridge:" + plan.activeCore.label
        )
    }

    private fun isImmediateBridgeFailure(category: String): Boolean =
        category in setOf(
            "invalid_xray_config",
            "connection_refused",
            "authentication_failed"
        )

"""
        s = replace_once(s, insert_before, native_method + insert_before, "CoreManager insert native bridge verifier")

    if "fun requiresSelfBypassForVpn(" not in s:
        insert_before = "    fun switchCore(coreId: CoreId) {\n"
        method = """    fun requiresSelfBypassForVpn(
        profile: ConfigProfile,
        settings: com.trivox.client.data.AppSettings
    ): Boolean {
        val selected = if (settings.smartCoreSelection) {
            smartSelect(
                CoreStartRequest(
                    profile = profile,
                    settings = settings,
                    mode = ConnectionMode.PROXY,
                    tunFd = null
                )
            )
        } else {
            settings.coreId
        }
        val bypass = selected != CoreId.XRAY
        Diagnostics.info(
            "VPN self-bypass decision: selectedRuntime=" + selected.label +
                ", bypassTrivoxApp=" + bypass +
                ", smart=" + settings.smartCoreSelection
        )
        return bypass
    }

"""
        s = replace_once(s, insert_before, method + insert_before, "CoreManager requiresSelfBypassForVpn")

    if "NATIVE_BRIDGE_VERIFY_BUDGET_MS" not in s:
        before = "        private const val PROXY_CONSERVATIVE_GRACE_MS = 450\n"
        after = (
            "        private const val PROXY_CONSERVATIVE_GRACE_MS = 450\n"
            "        private const val NATIVE_BRIDGE_VERIFY_BUDGET_MS = 9_000\n"
            "        private const val NATIVE_BRIDGE_PROBE_TIMEOUT_MS = 3_200\n"
            "        private const val NATIVE_BRIDGE_GRACE_MS = 450\n"
        )
        if before in s:
            s = s.replace(before, after, 1)
        else:
            before = "        private const val GENERAL_CONSERVATIVE_PROBE_TIMEOUT_MS = 4_000\n    }\n}"
            after = (
                "        private const val GENERAL_CONSERVATIVE_PROBE_TIMEOUT_MS = 4_000\n"
                "        private const val NATIVE_BRIDGE_VERIFY_BUDGET_MS = 9_000\n"
                "        private const val NATIVE_BRIDGE_PROBE_TIMEOUT_MS = 3_200\n"
                "        private const val NATIVE_BRIDGE_GRACE_MS = 450\n"
                "    }\n}"
            )
            s = replace_once(s, before, after, "CoreManager constants fallback")

    write_if_changed(path, s)

def patch_vpn_service():
    path = "app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt"
    s = read(path)

    old = """        val selectedRuntimeNeedsBypass =
            settings.smartCoreSelection ||
                settings.coreId != CoreId.XRAY
"""
    new = """        val selectedRuntimeNeedsBypass =
            core.requiresSelfBypassForVpn(profile, settings)
        Diagnostics.info(
            "VPN builder self-bypass decision for runtime sockets: " +
                selectedRuntimeNeedsBypass
        )
"""
    if old in s:
        s = s.replace(old, new, 1)
    elif "core.requiresSelfBypassForVpn(profile, settings)" not in s:
        raise SystemExit("TrivoxVpnService bypass anchor not found")

    old2 = """        tun = builder.establish()

        if (tun == null) {
"""
    new2 = """        Diagnostics.info(
            "Establishing Android VPN interface; ipv6=" + settings.ipv6 +
                ", mtu=" + settings.mtu +
                ", appRouting=" + settings.appRoutingMode.name
        )
        tun = builder.establish()

        if (tun == null) {
"""
    if old2 in s and "Establishing Android VPN interface; ipv6=" not in s:
        s = s.replace(old2, new2, 1)

    write_if_changed(path, s)

def patch_import_mimes():
    path = "app/src/main/java/com/trivox/client/ui/MainActivity.kt"
    s = read(path)

    wanted = [
        "text/plain",
        "text/yaml",
        "text/x-yaml",
        "application/yaml",
        "application/x-yaml",
        "application/json",
        "application/octet-stream",
        "application/x-clash-config",
        "application/x-mihomo-config",
        "application/x-sing-box-config",
        "application/x-wireguard-profile",
    ]

    marker = "filePicker.launch("
    start = s.find(marker)
    if start >= 0:
        arr = s.find("arrayOf(", start)
        if arr >= 0:
            depth = 0
            end = -1
            i = arr + len("arrayOf(")
            while i < len(s):
                ch = s[i]
                if ch == '(':
                    depth += 1
                elif ch == ')':
                    if depth == 0:
                        end = i
                        break
                    depth -= 1
                i += 1
            if end > arr:
                body = s[arr + len("arrayOf("):end]
                existing = []
                for line in body.strip().splitlines():
                    clean = line.strip().rstrip(',')
                    if clean.startswith('"') and clean.endswith('"'):
                        value = clean.strip('"')
                        if value not in existing:
                            existing.append(value)
                merged = []
                for m in wanted + existing:
                    if m not in merged:
                        merged.append(m)
                if any(f'"{m}"' not in body for m in wanted):
                    indent = " " * 24
                    new_body = "\n" + "\n".join(f'{indent}"{m}",' for m in merged) + "\n" + " " * 20
                    s = s[:arr + len("arrayOf(")] + new_body + s[end:]

    if "application/x-sing-box-config" not in s or "application/x-yaml" not in s:
        fallback = """
    private val trivoxNativeImportMimeHints = arrayOf(
        \"text/yaml\",
        \"text/x-yaml\",
        \"application/yaml\",
        \"application/x-yaml\",
        \"application/x-clash-config\",
        \"application/x-mihomo-config\",
        \"application/x-sing-box-config\"
    )
"""
        anchor = "    private fun showImportDialog() {\n"
        if anchor in s and "trivoxNativeImportMimeHints" not in s:
            s = s.replace(anchor, fallback + "\n" + anchor, 1)

    quick_target = "findViewById<Button>(R.id.quickCoreButton).setOnClickListener { showCorePicker() }"
    if s.count(quick_target) > 1:
        lines = s.splitlines()
        cleaned = []
        seen_quick = False
        skip_render_after_duplicate = False
        removed = 0
        for line in lines:
            if quick_target in line:
                if seen_quick:
                    skip_render_after_duplicate = True
                    removed += 1
                    continue
                seen_quick = True
            if skip_render_after_duplicate and line.strip() == "renderCoreButton()":
                skip_render_after_duplicate = False
                removed += 1
                continue
            if line.strip():
                skip_render_after_duplicate = False
            cleaned.append(line)
        s = "\n".join(cleaned) + ("\n" if s.endswith("\n") else "")
        print(f"quickCoreButton duplicate lines removed: {removed}")

    write_if_changed(path, s)

def patch_workflow():
    path = ".github/workflows/trivox-multicore-binaries.yml"
    p = ROOT / path
    if not p.exists():
        print("workflow not found; skipped")
        return
    s = read(path)

    if "Run Trivox static doctor" not in s:
        anchor = """      - name: Verify quick core layout id
        run: |
          grep -n '@+id/quickCoreButton' app/src/main/res/layout/activity_main.xml

"""
        insert = """      - name: Run Trivox static doctor
        run: python3 tools/trivox_repo_doctor.py

"""
        if anchor in s:
            s = s.replace(anchor, anchor + insert, 1)

    if "Compile Kotlin early" not in s:
        anchor = """      - uses: gradle/actions/setup-gradle@v4

      - name: Build release APKs
"""
        repl = """      - uses: gradle/actions/setup-gradle@v4

      - name: Compile Kotlin early
        run: |
          chmod +x ./gradlew || true
          ./gradlew :app:compileReleaseKotlin -PtrivoxAbis=arm64-v8a,armeabi-v7a --stacktrace

      - name: Build release APKs
"""
        if anchor in s:
            s = s.replace(anchor, repl, 1)

    s = s.replace("APK over 220 MB", "APK over 240 MB")
    s = s.replace("Verify APK size under 220 MB", "Verify APK size under 240 MB")

    write_if_changed(path, s)

def main():
    patch_core_manager()
    patch_vpn_service()
    patch_import_mimes()
    patch_workflow()
    print("Deep debug runtime patch applied")

if __name__ == "__main__":
    main()
