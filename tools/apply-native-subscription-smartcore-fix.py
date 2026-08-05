#!/usr/bin/env python3
from __future__ import annotations

import re
import shutil
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
    target.parent.mkdir(parents=True, exist_ok=True)
    old = target.read_text(encoding="utf-8") if target.exists() else None
    if old == content:
        print(f"unchanged {path}")
        return
    target.write_text(content, encoding="utf-8")
    print(f"patched {path}")


def copy(path: str) -> None:
    source = PATCH_ROOT / path
    if not source.is_file():
        fail(f"patch payload is missing: {path}")
    target = REPO / path
    target.parent.mkdir(parents=True, exist_ok=True)
    data = source.read_bytes()
    if target.exists() and target.read_bytes() == data:
        print(f"unchanged {path}")
        return
    target.write_bytes(data)
    if source.stat().st_mode & 0o111:
        target.chmod(target.stat().st_mode | 0o755)
    print(f"copied {path}")


def add_import(content: str, import_line: str, after: str) -> str:
    if import_line in content:
        return content
    if after not in content:
        fail(f"import marker missing: {after.strip()}")
    return content.replace(after, after + import_line, 1)


def append_xml_strings(path: str, values: dict[str, str]) -> None:
    content = read(path)
    additions = []
    for name, value in values.items():
        if f'name="{name}"' not in content:
            escaped = (
                value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "\\'")
            )
            additions.append(f'    <string name="{name}">{escaped}</string>')
    if additions:
        if "</resources>" not in content:
            fail(f"resources end marker missing: {path}")
        content = content.replace("</resources>", "\n".join(additions) + "\n</resources>")
        write(path, content)


FULL_FILES = [
    "app/src/main/java/com/trivox/client/config/MiniYaml.kt",
    "app/src/main/java/com/trivox/client/config/NativeProfileDocument.kt",
    "app/src/main/java/com/trivox/client/config/NativeConfigImporter.kt",
    "app/src/main/java/com/trivox/client/network/SubscriptionExpansionManager.kt",
    "app/src/main/java/com/trivox/client/core/SmartCoreSelector.kt",
    "app/src/main/java/com/trivox/client/core/CoreConversionManager.kt",
    "tools/trivox-native-smart-doctor.py",
    "tools/apply-native-subscription-smartcore-fix.py",
    "docs/TRIVOX_NATIVE_SUBSCRIPTIONS_SMARTCORE_FA.md",
    "fixtures/mihomo-provider-main.yaml",
    "fixtures/mihomo-provider-list.yaml",
    "fixtures/sing-box-remote-link.txt",
]
for file in FULL_FILES:
    copy(file)

# ConfigParser: native documents are parsed once and only lines beginning with a URI scheme
# are treated as links. Nested YAML values such as "url: https://..." are never schemes.
path = "app/src/main/java/com/trivox/client/config/ConfigParser.kt"
s = read(path)
s = re.sub(
    r"\n\s*NativeConfigImporter\.parseTextOrNull\(text\)\?\.let \{ return it \}\s*\n\s*NativeConfigImporter\.parseTextOrNull\(text\)\?\.let \{ native ->\s*\n\s*if \(native\.isNotEmpty\(\)\) return native\s*\n\s*\}",
    "\n        NativeConfigImporter.parseTextOrNull(text)?.let { native ->\n            if (native.isNotEmpty()) return native\n        }",
    s,
    count=1,
)
if "private val URI_LINE_PATTERN" not in s:
    marker = "object ConfigParser {\n"
    if marker not in s:
        fail("ConfigParser object marker missing")
    s = s.replace(
        marker,
        marker + '    private val URI_LINE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")\n',
        1,
    )
old_candidates = '''        val directLines = boundedLines(text)
        val candidates = if (directLines.any { "://" in it || it.startsWith("{") }) {
            directLines
        } else {
            val decoded = decodeBase64OrNull(text) ?: throw ConfigParseException(
                "Input is neither a supported URI, Xray JSON, wg-quick, nor Base64 subscription content"
            )
            if (looksLikeWireGuardQuickConfig(decoded)) {
                return listOf(parseWireGuardQuickConfig(decoded))
            }
            boundedLines(decoded)
        }
'''
new_candidates = '''        val directLines = boundedLines(text)
        val directCandidates = directLines.filter { line ->
            val candidate = line.trim()
            candidate.startsWith("{") || URI_LINE_PATTERN.containsMatchIn(candidate)
        }
        val candidates = if (directCandidates.isNotEmpty()) {
            directCandidates
        } else {
            val decoded = decodeBase64OrNull(text) ?: throw ConfigParseException(
                "Input is neither a supported URI, native Mihomo/sing-box document, Xray JSON, wg-quick, nor Base64 subscription content"
            )
            NativeConfigImporter.parseTextOrNull(decoded)?.let { native ->
                if (native.isNotEmpty()) return native
            }
            if (looksLikeWireGuardQuickConfig(decoded)) {
                return listOf(parseWireGuardQuickConfig(decoded))
            }
            boundedLines(decoded).filter { line ->
                val candidate = line.trim()
                candidate.startsWith("{") || URI_LINE_PATTERN.containsMatchIn(candidate)
            }
        }
'''
if old_candidates in s:
    s = s.replace(old_candidates, new_candidates, 1)
elif "val directCandidates = directLines.filter" not in s:
    fail("ConfigParser candidate block changed; refusing an unsafe patch")
write(path, s)

# CoreConfigTranslator: exact native docs stay exact. "none" transport is canonical TCP.
path = "app/src/main/java/com/trivox/client/core/CoreConfigTranslator.kt"
s = read(path)
s = add_import(
    s,
    "import com.trivox.client.config.NativeProfileDocument\n",
    "import com.trivox.client.config.NativeConfigImporter\n",
)
old_build = '''    fun build(request: CoreStartRequest, coreId: CoreId): String = when (coreId) {
        CoreId.XRAY -> XrayConfigBuilder.build(
            profile = xrayProfileFor(request.profile),
            settings = request.settings,
            mode = request.mode,
            tunFd = request.tunFd,
            errorLogPath = com.trivox.client.util.Diagnostics.xrayErrorLogPath()
        )
        CoreId.SING_BOX -> buildSingBox(request.profile, request.settings.socksPort).toString()
        CoreId.MIHOMO -> buildMihomoYaml(request.profile, request.settings.socksPort)
    }

    fun buildNativeProfile(profile: ConfigProfile, coreId: CoreId, mixedPort: Int): String = when (coreId) {
        CoreId.XRAY -> xrayOutboundFor(profile)
        CoreId.SING_BOX -> buildSingBox(profile, mixedPort).toString(2)
        CoreId.MIHOMO -> buildMihomoYaml(profile, mixedPort)
    }
'''
new_build = '''    fun build(request: CoreStartRequest, coreId: CoreId): String {
        NativeProfileDocument.exactRuntimeConfig(
            request.profile,
            coreId,
            request.settings.socksPort
        )?.let { return it }
        return when (coreId) {
            CoreId.XRAY -> XrayConfigBuilder.build(
                profile = xrayProfileFor(request.profile),
                settings = request.settings,
                mode = request.mode,
                tunFd = request.tunFd,
                errorLogPath = com.trivox.client.util.Diagnostics.xrayErrorLogPath()
            )
            CoreId.SING_BOX -> buildSingBox(request.profile, request.settings.socksPort).toString()
            CoreId.MIHOMO -> buildMihomoYaml(request.profile, request.settings.socksPort)
        }
    }

    fun buildNativeProfile(profile: ConfigProfile, coreId: CoreId, mixedPort: Int): String {
        NativeProfileDocument.exactRuntimeConfig(profile, coreId, mixedPort)?.let { return it }
        return when (coreId) {
            CoreId.XRAY -> xrayOutboundFor(profile)
            CoreId.SING_BOX -> buildSingBox(profile, mixedPort).toString(2)
            CoreId.MIHOMO -> buildMihomoYaml(profile, mixedPort)
        }
    }
'''
if old_build in s:
    s = s.replace(old_build, new_build, 1)
elif "NativeProfileDocument.exactRuntimeConfig" not in s:
    fail("CoreConfigTranslator build block changed; refusing an unsafe patch")

if "Complete native documents cannot be passed to Xray" not in s:
    s, inserted = re.subn(
        r"(    private fun xrayProfileFor\(profile: ConfigProfile\): ConfigProfile \{\s*)",
        r'''\1        NativeProfileDocument.affinity(profile)?.let { required ->
            error("Complete native documents cannot be passed to Xray; this profile requires ${required.label}")
        }
''',
        s,
        count=1,
    )
    if inserted == 0:
        fail("xrayProfileFor marker missing")
s = re.sub(
    r'''    private fun streamNetwork\(stream: JSONObject\): String =\s*\n\s*stream\.optString\("network", "tcp"\)\.lowercase\(\)''',
    '''    private fun streamNetwork(stream: JSONObject): String =
        when (val network = stream.optString("network", "tcp").trim().lowercase()) {
            "", "none", "raw" -> "tcp"
            "websocket" -> "ws"
            "http-upgrade", "http_upgrade" -> "httpupgrade"
            "splithttp" -> "xhttp"
            else -> network
        }''',
    s,
    count=1,
)
write(path, s)

# CoreManager: delegate smart selection to an actual benchmarker.
path = "app/src/main/java/com/trivox/client/core/CoreManager.kt"
s = read(path)
if "private val smartCoreSelector" not in s:
    marker = "\n\n    private data class StartPlan("
    if marker not in s:
        fail("CoreManager StartPlan marker missing")
    s = s.replace(
        marker,
        "\n    private val smartCoreSelector = SmartCoreSelector(appContext, adapters)\n" + marker,
        1,
    )
s, replaced = re.subn(
    r'''    fun smartSelect\(request: CoreStartRequest\): CoreId \{.*?\n    \}\n\n    private fun buildPlanForCandidate''',
    '''    fun smartSelect(request: CoreStartRequest): CoreId =
        smartCoreSelector.select(request)

    private fun buildPlanForCandidate''',
    s,
    count=1,
    flags=re.S,
)
if replaced == 0 and "smartCoreSelector.select(request)" not in s:
    fail("CoreManager smartSelect block changed; refusing an unsafe patch")
s = re.sub(r"private const val NATIVE_BRIDGE_VERIFY_BUDGET_MS = [0-9_]+", "private const val NATIVE_BRIDGE_VERIFY_BUDGET_MS = 18_000", s)
s = re.sub(r"private const val NATIVE_BRIDGE_PROBE_TIMEOUT_MS = [0-9_]+", "private const val NATIVE_BRIDGE_PROBE_TIMEOUT_MS = 4_800", s)
s = re.sub(r"private const val NATIVE_BRIDGE_GRACE_MS = [0-9_]+", "private const val NATIVE_BRIDGE_GRACE_MS = 1_000", s)
write(path, s)

# SubscriptionManager: unwrap official remote-profile installers before HTTPS normalization.
path = "app/src/main/java/com/trivox/client/network/SubscriptionManager.kt"
s = read(path)
s = add_import(
    s,
    "import com.trivox.client.config.NativeProfileDocument\n",
    "import com.trivox.client.config.ConfigParser\n",
)
s = re.sub(
    r"normalizeUrl\(\s*url\s*\)",
    "normalizeUrl(NativeProfileDocument.remoteProfileUrl(url) ?: url)",
    s,
    count=1,
)
s = s.replace(
    '"text/plain, application/json;q=0.9, " +\n                    "application/octet-stream;q=0.8, */*;q=0.1"',
    '"text/yaml, application/yaml, application/x-yaml, " +\n                    "application/json;q=0.9, text/plain;q=0.9, " +\n                    "application/octet-stream;q=0.8, */*;q=0.1"',
)
write(path, s)

# Subscription row long-press conversion UI.
path = "app/src/main/java/com/trivox/client/ui/SubscriptionManagementActivity.kt"
s = read(path)
s = add_import(
    s,
    "import com.trivox.client.core.CoreConversionManager\n",
    "import com.trivox.client.core.ConnectionRuntime\n",
)
s = add_import(
    s,
    "import com.trivox.client.data.CoreId\n",
    "import com.trivox.client.data.ConnectionState\n",
)
listener_marker = '''            delete.setOnClickListener {
                confirmDelete(source)
            }
'''
listener_code = listener_marker + '''            val conversionLongClick = View.OnLongClickListener {
                showCoreConversion(source)
                true
            }
            row.setOnLongClickListener(conversionLongClick)
            name.setOnLongClickListener(conversionLongClick)
'''
if "showCoreConversion(source)" not in s:
    if listener_marker not in s:
        fail("Subscription row listener marker missing")
    s = s.replace(listener_marker, listener_code, 1)

method_marker = "    private fun showAddSourceOptions() {\n"
if "private fun showCoreConversion(" not in s:
    if method_marker not in s:
        fail("Subscription showAddSourceOptions marker missing")
    methods = '''    private fun showCoreConversion(source: SubscriptionSource) {
        if (operationBusy.get()) {
            toast(getString(R.string.subscription_conversion_busy))
            return
        }
        val cores = CoreId.entries
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.subscription_conversion_title, source.name))
            .setMessage(R.string.subscription_conversion_hint)
            .setItems(cores.map { it.label }.toTypedArray()) { _, position ->
                convertSubscriptionToCore(source, cores[position])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun convertSubscriptionToCore(source: SubscriptionSource, target: CoreId) {
        if (!operationBusy.compareAndSet(false, true)) {
            toast(getString(R.string.subscription_conversion_busy))
            return
        }
        progressText.visibility = View.VISIBLE
        progressText.text = getString(R.string.subscription_conversion_progress, target.label)
        render()
        activeTask = worker.submit {
            val summary = CoreConversionManager(applicationContext)
                .convertSubscriptionFromSource(source, target)
            operationBusy.set(false)
            activeTask = null
            runOnUiThreadIfAlive {
                progressText.visibility = View.GONE
                render()
                val warning = summary.warnings.firstOrNull().orEmpty()
                val message = getString(
                    R.string.subscription_conversion_result,
                    summary.target.label,
                    summary.added,
                    summary.failed
                ) + if (summary.firstError.isNotBlank()) {
                    "\n" + summary.firstError
                } else if (warning.isNotBlank()) {
                    "\n" + warning
                } else {
                    ""
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

'''
    s = s.replace(method_marker, methods + method_marker, 1)
write(path, s)

append_xml_strings(
    "app/src/main/res/values/strings.xml",
    {
        "subscription_conversion_title": "Convert %1$s",
        "subscription_conversion_hint": "The target core is validated with its real native configuration. Full provider-based documents are expanded before cross-core conversion. Converted profiles are stored locally so normal subscription updates cannot delete them.",
        "subscription_conversion_busy": "Another subscription operation is already running.",
        "subscription_conversion_progress": "Converting with %1$s…",
        "subscription_conversion_result": "%1$s conversion: %2$d added or updated, %3$d failed",
    },
)
append_xml_strings(
    "app/src/main/res/values-fa/strings.xml",
    {
        "subscription_conversion_title": "تبدیل %1$s",
        "subscription_conversion_hint": "کانفیگ native واقعی هسته مقصد اعتبارسنجی می‌شود. برای سندهای provider-based ابتدا providerها باز می‌شوند. خروجی‌ها محلی ذخیره می‌شوند تا آپدیت عادی ساب آن‌ها را حذف نکند.",
        "subscription_conversion_busy": "یک عملیات دیگر روی اشتراک‌ها در حال اجرا است.",
        "subscription_conversion_progress": "در حال تبدیل با %1$s…",
        "subscription_conversion_result": "تبدیل %1$s: تعداد %2$d افزوده یا به‌روزرسانی شد و %3$d مورد ناموفق بود",
    },
)

# Workflow re-applies this patch after older source mutators and verifies it before compile.
path = ".github/workflows/trivox-multicore-binaries.yml"
s = read(path)
step = '''      - name: Apply native subscriptions and smart core fix
        run: python3 tools/apply-native-subscription-smartcore-fix.py "$GITHUB_WORKSPACE"

      - name: Verify native subscriptions and smart core
        run: python3 tools/trivox-native-smart-doctor.py "$GITHUB_WORKSPACE"

'''
if "Verify native subscriptions and smart core" not in s:
    anchors = [
        "      - name: Run Trivox static doctor\n",
        "      - name: Download latest prerelease sing-box and mihomo assets\n",
    ]
    for anchor in anchors:
        if anchor in s:
            s = s.replace(anchor, step + anchor, 1)
            break
    else:
        fail("workflow insertion marker missing")
write(path, s)

print("Native subscription, real conversion and benchmark smart-core patch applied")
