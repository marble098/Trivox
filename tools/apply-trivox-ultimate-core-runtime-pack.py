#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

PATCH_ROOT = Path(__file__).resolve().parents[1]
REPO = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()

def fail(message: str) -> None:
    raise SystemExit(message)

def read(path: str) -> str:
    target = REPO / path
    if not target.is_file():
        fail(f"required repo file is missing: {path}")
    return target.read_text(encoding="utf-8")

def write(path: str, content: str) -> None:
    target = REPO / path
    old = target.read_text(encoding="utf-8") if target.exists() else None
    target.parent.mkdir(parents=True, exist_ok=True)
    if old == content:
        print(f"unchanged {path}")
        return
    target.write_text(content, encoding="utf-8")
    print(f"patched {path}")

def copy_payload(path: str) -> None:
    source = PATCH_ROOT / path
    if not source.is_file():
        fail(f"payload file is missing: {path}")
    target = REPO / path
    target.parent.mkdir(parents=True, exist_ok=True)
    data = source.read_bytes()
    if target.exists() and target.read_bytes() == data:
        print(f"unchanged {path}")
    else:
        target.write_bytes(data)
        print(f"copied {path}")
    if source.stat().st_mode & 0o111:
        target.chmod(target.stat().st_mode | 0o755)

def add_import(text: str, line: str, after: str) -> str:
    if line in text:
        return text
    if after not in text:
        fail(f"import marker missing: {after.strip()}")
    return text.replace(after, after + line, 1)

def shorten_xml_values(path: str, replacements: list[tuple[str, str]]) -> None:
    text = read(path)

    def repl(match: re.Match[str]) -> str:
        head, value, tail = match.group(1), match.group(2), match.group(3)
        new = value
        for src, dst in replacements:
            new = new.replace(src, dst)
        return head + new + tail

    text = re.sub(
        r'(<string\s+name="[^"]+"\s*>)(.*?)(</string>)',
        repl,
        text,
        flags=re.S,
    )
    write(path, text)

for payload in [
    "app/src/main/java/com/trivox/client/core/CoreSupportFiles.kt",
    "tools/prepare-core-support-files.py",
    "tools/trivox-ultimate-core-doctor.py",
]:
    copy_payload(payload)

# Kotlin conversion newline compile fix, included so this pack can be used directly.
path = "app/src/main/java/com/trivox/client/ui/SubscriptionManagementActivity.kt"
s = read(path)
s = s.replace('"' + chr(10) + '" + summary.firstError', '"\\n" + summary.firstError')
s = s.replace('"' + chr(10) + '" + warning', '"\\n" + warning')
write(path, s)

path = "tools/apply-native-subscription-smartcore-fix.py"
s = read(path)
s = s.replace('"\\n" + summary.firstError', '"\\\\n" + summary.firstError')
s = s.replace('"\\n" + warning', '"\\\\n" + warning')
write(path, s)

# Native executable cores need their runtime database files staged before validation/start.
path = "app/src/main/java/com/trivox/client/core/ExecutableCoreAdapter.kt"
s = read(path)
s = s.replace("Thread.sleep(320)", "Thread.sleep(CORE_START_GRACE_MS)")
old = '''    private fun processBuilder(args: List<String>): ProcessBuilder {
        val home = configHomeDir()
        val builder = ProcessBuilder(args)
            .directory(coreWorkDir())
            .redirectErrorStream(true)

        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["XDG_CONFIG_HOME"] = home.absolutePath
        builder.environment()["MIHOMO_HOME"] = home.absolutePath
        builder.environment()["CLASH_HOME"] = home.absolutePath
        builder.environment()["TMPDIR"] = File(coreWorkDir(), "tmp").apply { mkdirs() }.absolutePath

        return builder
    }
'''
new = '''    private fun processBuilder(args: List<String>): ProcessBuilder {
        val work = coreWorkDir()
        val home = configHomeDir()
        CoreSupportFiles.install(context, work, home)
        val builder = ProcessBuilder(args)
            .directory(work)
            .redirectErrorStream(true)

        val tmp = File(work, "tmp").apply { mkdirs() }
        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["XDG_CONFIG_HOME"] = home.absolutePath
        builder.environment()["TMPDIR"] = tmp.absolutePath
        CoreSupportFiles.applyEnvironment(builder.environment(), work, home)

        return builder
    }
'''
if old in s:
    s = s.replace(old, new, 1)
elif "CoreSupportFiles.install(context, work, home)" not in s:
    fail("ExecutableCoreAdapter processBuilder block changed")
if "private const val CORE_START_GRACE_MS" not in s:
    marker = '''    private fun missing(): CoreResult =
        CoreResult(false, "$displayName binary is missing, compressed, or not executable. Rebuild APK with native ELF jniLibs.")
}'''
    replacement = '''    private fun missing(): CoreResult =
        CoreResult(false, "$displayName binary is missing, compressed, or not executable. Rebuild APK with native ELF jniLibs.")

    companion object {
        private const val CORE_START_GRACE_MS = 650L
    }
}'''
    if marker not in s:
        fail("ExecutableCoreAdapter companion insertion marker missing")
    s = s.replace(marker, replacement, 1)
write(path, s)

# Core manager: enforce native affinity, wait for native local listener before Xray bridge,
# and avoid treating normal startup connection_refused as a fatal native-bridge proof failure.
path = "app/src/main/java/com/trivox/client/core/CoreManager.kt"
s = read(path)
s = add_import(s, "import com.trivox.client.config.NativeProfileDocument\n", "import android.content.Context\n")
s = add_import(s, "import java.net.InetSocketAddress\n", "import java.lang.ref.WeakReference\n")
s = add_import(s, "import java.net.Socket\n", "import java.net.InetSocketAddress\n")

old = '''    private fun resolveCoreForRequest(request: CoreStartRequest): CoreId {
        val settings = SettingsRepository(appContext).load()
        return if (settings.smartCoreSelection) smartSelect(request) else settings.coreId
    }

    fun smartSelect(request: CoreStartRequest): CoreId =
        smartCoreSelector.select(request)
'''
new = '''    private fun resolveCoreForRequest(request: CoreStartRequest): CoreId {
        NativeProfileDocument.affinity(request.profile)?.let { return it }
        val settings = SettingsRepository(appContext).load()
        return if (settings.smartCoreSelection) smartSelect(request) else settings.coreId
    }

    fun smartSelect(request: CoreStartRequest): CoreId {
        NativeProfileDocument.affinity(request.profile)?.let { required ->
            Diagnostics.info("Smart core native affinity: " + required.label)
            return required
        }
        return smartCoreSelector.select(request)
    }
'''
if old in s:
    s = s.replace(old, new, 1)
elif "Smart core native affinity" not in s:
    fail("CoreManager resolve/smart block changed")

old = '''        val selected = if (settings.smartCoreSelection) {
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
'''
new = '''        val selected = NativeProfileDocument.affinity(profile) ?: if (settings.smartCoreSelection) {
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
'''
if old in s:
    s = s.replace(old, new, 1)
elif "val selected = NativeProfileDocument.affinity(profile)" not in s:
    fail("CoreManager self-bypass selection block changed")

old = '''            Diagnostics.info("Starting Xray Android TUN bridge for " + plan.activeCore.label)
            val bridge = adapters.getValue(CoreId.XRAY).start(plan.vpnBridgeConfig ?: return CoreResult(false, "Missing Xray VPN bridge config"), protect)
'''
new = '''            waitForLocalMixedProxy(
                port = request.settings.socksPort,
                isCancelled = isCancelled
            )?.let { error ->
                stopAfterRejectedStart()
                return CoreResult(
                    false,
                    plan.activeCore.label + " started, but its localhost mixed proxy was not ready before Android VPN bridge: " + error
                )
            }

            Diagnostics.info("Starting Xray Android TUN bridge for " + plan.activeCore.label)
            val bridge = adapters.getValue(CoreId.XRAY).start(plan.vpnBridgeConfig ?: return CoreResult(false, "Missing Xray VPN bridge config"), protect)
'''
if old in s:
    s = s.replace(old, new, 1)
elif "waitForLocalMixedProxy(" not in s:
    fail("CoreManager bridge start marker changed")

helper = '''    private fun waitForLocalMixedProxy(
        port: Int,
        isCancelled: () -> Boolean
    ): String? {
        val deadline = System.currentTimeMillis() + NATIVE_LOCAL_LISTENER_BUDGET_MS
        var lastError = "not attempted"
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return "cancelled"
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress("127.0.0.1", port),
                        NATIVE_LOCAL_CONNECT_TIMEOUT_MS
                    )
                }
                Diagnostics.info("Native local mixed proxy is ready on 127.0.0.1:$port")
                return null
            } catch (error: Throwable) {
                lastError = error.javaClass.simpleName + ": " + (error.message ?: "not ready")
                try {
                    Thread.sleep(NATIVE_LOCAL_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return "interrupted"
                }
            }
        }
        return lastError
    }

'''
if "private fun waitForLocalMixedProxy(" not in s:
    marker = "    private fun verifyLiveRoute(\n"
    if marker not in s:
        fail("verifyLiveRoute marker missing")
    s = s.replace(marker, helper + marker, 1)

s = s.replace('?.takeIf(::isImmediateBridgeFailure)', '?.takeIf { it == "invalid_xray_config" }')
s = re.sub(
    r'''    private fun isImmediateBridgeFailure\(category: String\): Boolean =\s*category in setOf\([^)]*\)\n''',
    '''    private fun isImmediateBridgeFailure(category: String): Boolean =
        category == "invalid_xray_config"
''',
    s,
    count=1,
)
s = s.replace("private const val NATIVE_BRIDGE_VERIFY_BUDGET_MS = 18_000", "private const val NATIVE_BRIDGE_VERIFY_BUDGET_MS = 22_000")
s = s.replace("private const val NATIVE_BRIDGE_GRACE_MS = 1_000", "private const val NATIVE_BRIDGE_GRACE_MS = 1_500")
if "private const val NATIVE_LOCAL_LISTENER_BUDGET_MS" not in s:
    s = s.replace(
        "        private const val NATIVE_BRIDGE_GRACE_MS = 1_500\n",
        "        private const val NATIVE_BRIDGE_GRACE_MS = 1_500\n"
        "        private const val NATIVE_LOCAL_LISTENER_BUDGET_MS = 6_500\n"
        "        private const val NATIVE_LOCAL_CONNECT_TIMEOUT_MS = 420\n"
        "        private const val NATIVE_LOCAL_RETRY_DELAY_MS = 140L\n",
        1,
    )
write(path, s)

# DNS: default should be robust; users can still choose THROUGH_PROXY explicitly.
path = "app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt"
s = read(path)
old = '''        DnsMode.THROUGH_PROXY,
        DnsMode.TRIVOX_DEFAULT ->
            smartDns(remoteSecureDns(profile), settings)
'''
new = '''        DnsMode.THROUGH_PROXY ->
            smartDns(remoteSecureDns(profile), settings)

        DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)
'''
if old in s:
    s = s.replace(old, new, 1)
elif "DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)" not in s:
    fail("Xray DNS default block changed")
write(path, s)

# Native Mihomo defaults: keep it localhost-only and enable better delay behaviour.
path = "app/src/main/java/com/trivox/client/config/NativeProfileDocument.kt"
s = read(path)
old = '''        val output = mutableListOf(
            "mixed-port: $mixedPort",
            "allow-lan: false",
            "bind-address: '127.0.0.1'"
        )
'''
new = '''        val output = mutableListOf(
            "mixed-port: $mixedPort",
            "allow-lan: false",
            "bind-address: '127.0.0.1'",
            "unified-delay: true",
            "tcp-concurrent: true",
            "find-process-mode: off"
        )
'''
if old in s:
    s = s.replace(old, new, 1)
elif '"tcp-concurrent: true"' not in s:
    fail("NativeProfileDocument Mihomo output block changed")
write(path, s)

# Gradle: core databases must remain uncompressed.
path = "app/build.gradle.kts"
s = read(path)
if "androidResources" not in s or "noCompress" not in s:
    marker = '''    packaging {
'''
    insert = '''    androidResources {
        noCompress += setOf("dat", "db", "mmdb", "metadb")
    }

'''
    if marker not in s:
        fail("androidResources insertion marker missing")
    s = s.replace(marker, insert + marker, 1)
write(path, s)

# Workflow: download/stage support data and commit it with the prepared core files.
path = ".github/workflows/trivox-multicore-binaries.yml"
s = read(path)
if "Prepare core support data" not in s:
    anchor = "      - name: Fix native core packaging\n"
    step = '''      - name: Prepare core support data
        run: python3 tools/prepare-core-support-files.py

'''
    if anchor in s:
        s = s.replace(anchor, step + anchor, 1)
    else:
        anchor = "      - name: Verify native core libs\n"
        if anchor not in s:
            fail("workflow core-data insertion marker missing")
        s = s.replace(anchor, step + anchor, 1)
if "app/src/main/assets/core-data" not in s:
    s = s.replace(
        "git add app/src/main tools docs .github/workflows",
        "git add app/src/main tools docs .github/workflows app/src/main/assets/core-data",
    )
write(path, s)

# Compact visible UI strings without changing resource identifiers.
shorten_xml_values(
    "app/src/main/res/values/strings.xml",
    [
        ("Subscriptions", "Subs"),
        ("Subscription", "Sub"),
        ("subscription", "sub"),
        ("Configuration", "Config"),
        ("configuration", "config"),
        ("Connection", "Conn"),
        ("connection", "conn"),
    ],
)
shorten_xml_values(
    "app/src/main/res/values-fa/strings.xml",
    [
        ("اشتراک‌ها", "ساب‌ها"),
        ("اشتراک", "ساب"),
        ("پیکربندی", "کانفیگ"),
        ("پیکره‌بندی", "کانفیگ"),
        ("کانفیگوریشن", "کانفیگ"),
        ("اتصال", "وصل"),
    ],
)

print("Trivox ultimate core runtime pack applied")
