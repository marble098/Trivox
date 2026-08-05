#!/usr/bin/env python3
from pathlib import Path

ROOT = Path('.').resolve()


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding='utf-8')


def insert_after(text: str, needle: str, addition: str) -> str:
    if addition.strip() in text:
        return text
    if needle not in text:
        raise SystemExit(f'needle not found: {needle[:80]!r}')
    return text.replace(needle, needle + addition, 1)


def insert_before(text: str, needle: str, addition: str) -> str:
    if addition.strip() in text:
        return text
    if needle not in text:
        raise SystemExit(f'needle not found: {needle[:80]!r}')
    return text.replace(needle, addition + needle, 1)


# ConfigParser: native sing-box JSON / mihomo YAML import in existing Add flow.
p = 'app/src/main/java/com/trivox/client/config/ConfigParser.kt'
s = read(p)
needle = '        if (text.isBlank()) return emptyList()\n'
addition = '''        NativeConfigImporter.parseTextOrNull(text)?.let { native ->
            if (native.isNotEmpty()) return native
        }
'''
s = insert_after(s, needle, addition)
write(p, s)

# CoreManager: request-aware core resolution, real validation-based smart selection, stop all core processes.
p = 'app/src/main/java/com/trivox/client/core/CoreManager.kt'
s = read(p)

s = s.replace('''    fun prepare(request: CoreStartRequest): Pair<String?, CoreResult> =
        runCatching {
            val json = buildJson(request)
            val validation = selectedAdapter().validate(json)
            if (!validation.success) null to validation
            else json to CoreResult(true)
        }.getOrElse {
''', '''    fun prepare(request: CoreStartRequest): Pair<String?, CoreResult> =
        runCatching {
            val coreId = resolveCoreForRequest(request)
            val json = CoreConfigTranslator.build(request, coreId)
            val validation = validateWith(coreId, json)
            if (!validation.success) null to validation
            else json to CoreResult(true)
        }.getOrElse {
''')

s = s.replace('''        val json = runCatching { buildJson(request) }.getOrElse {
''', '''        val json = runCatching {
            val coreId = resolveCoreForRequest(request)
            CoreConfigTranslator.build(request, coreId)
        }.getOrElse {
''')

s = s.replace('''        val started = selectedAdapter().start(json, protect)
''', '''        stopAllAdapters()
        val started = adapterForRequest(request).start(json, protect)
''')

s = s.replace('''        runCatching { selectedAdapter().stop() }
''', '''        runCatching { stopAllAdapters() }
''')

s = s.replace('''    fun isRunning(): Boolean = selectedAdapter().state()
        .data
        ?.optJSONObject("data")
        ?.optBoolean("running") == true

    fun stop(): CoreResult = selectedAdapter().stop()

    private fun buildJson(request: CoreStartRequest): String =
        CoreConfigTranslator.build(request, selectedCoreId())
''', '''    fun isRunning(): Boolean = adapters.values.any { adapter ->
        adapter.state().data?.optJSONObject("data")?.optBoolean("running") == true
    }

    fun stop(): CoreResult = stopAllAdapters()

    fun validateWith(coreId: CoreId, configJson: String): CoreResult =
        (adapters[coreId] ?: adapters.getValue(CoreId.XRAY)).validate(configJson)

    private fun stopAllAdapters(): CoreResult {
        var ok = true
        var error = ""
        adapters.values.forEach { adapter ->
            val result = runCatching { adapter.stop() }.getOrElse {
                CoreResult(false, it.message ?: "unknown")
            }
            if (!result.success) {
                ok = false
                if (error.isBlank()) error = result.error
            }
        }
        return CoreResult(ok, error)
    }

    private fun adapterForRequest(request: CoreStartRequest): CoreAdapter =
        adapters[resolveCoreForRequest(request)] ?: adapters.getValue(CoreId.XRAY)

    private fun buildJson(request: CoreStartRequest): String =
        CoreConfigTranslator.build(request, resolveCoreForRequest(request))
''')

start = s.find('    fun smartSelect(request: CoreStartRequest): CoreId {')
end = s.find('    companion object {', start)
if start < 0 or end < 0:
    raise SystemExit('smartSelect block not found')
replacement = '''    private fun resolveCoreForRequest(request: CoreStartRequest): CoreId {
        val settings = SettingsRepository(appContext).load()
        if (request.mode == com.trivox.client.data.ConnectionMode.VPN) {
            if (settings.lastSmartCoreId != CoreId.XRAY) {
                settings.lastSmartCoreId = CoreId.XRAY
                SettingsRepository(appContext).save(settings)
            }
            return CoreId.XRAY
        }
        return if (settings.smartCoreSelection) smartSelect(request) else settings.coreId
    }

    fun smartSelect(request: CoreStartRequest): CoreId {
        val settings = SettingsRepository(appContext).load()
        if (!settings.smartCoreSelection) return settings.coreId

        if (request.mode == com.trivox.client.data.ConnectionMode.VPN) {
            settings.lastSmartCoreId = CoreId.XRAY
            SettingsRepository(appContext).save(settings)
            Diagnostics.info("Smart core selected: XRAY for Android VPN mode")
            return CoreId.XRAY
        }

        val order = listOf(CoreId.MIHOMO, CoreId.SING_BOX, CoreId.XRAY)
        val available = order.filter { adapters[it]?.isAvailable() == true }.ifEmpty { listOf(CoreId.XRAY) }
        val diagnostics = StringBuilder()

        available.forEach { coreId ->
            val adapter = adapters[coreId] ?: return@forEach
            val json = runCatching { CoreConfigTranslator.build(request, coreId) }.getOrElse {
                diagnostics.append(coreId.name).append(": build failed: ").append(it.message ?: "unknown").append("\n")
                return@forEach
            }
            val validation = runCatching { adapter.validate(json) }.getOrElse {
                CoreResult(false, it.message ?: "validation crashed")
            }
            if (validation.success) {
                settings.lastSmartCoreId = coreId
                SettingsRepository(appContext).save(settings)
                Diagnostics.info("Smart core selected: " + coreId.name)
                return coreId
            }
            diagnostics.append(coreId.name).append(": ").append(validation.error).append("\n")
        }

        settings.lastSmartCoreId = CoreId.XRAY
        SettingsRepository(appContext).save(settings)
        Diagnostics.warning("Smart core fallback to XRAY. Candidates failed: " + diagnostics.toString().trim())
        return CoreId.XRAY
    }

'''
s = s[:start] + replacement + s[end:]
write(p, s)

# ConnectionService: generic core names, pre-start full cleanup, avoid killing proxy on expected restart.
p = 'app/src/main/java/com/trivox/client/service/ConnectionService.kt'
s = read(p)
s = s.replace('''        if (!core.adapter.isAvailable()) {
            fail(
                "Xray Android core is " +
                    "missing or corrupted"
            )
            return
        }
''', '''        if (!core.adapter.isAvailable()) {
            fail(
                "Selected core " + core.adapter.id +
                    " is missing or corrupted"
            )
            return
        }
''')
marker = '''        if (
            !portAvailable(
                settings.socksPort
            )
        ) {
'''
insert = '''        runCatching { core.stop() }
            .onFailure {
                Diagnostics.warning("Pre-start core cleanup failed: " + it.message)
            }

        try {
            Thread.sleep(180L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

'''
s = insert_before(s, marker, insert)
s = s.replace('''                "Mixed proxy port was busy; " +
                    "stopping a stale Xray instance"
''','''                "Mixed proxy port was busy; " +
                    "stopping stale core processes"
''')
s = s.replace('''                                "Xray stopped " +
                                    "unexpectedly"
''','''                                "Selected core stopped " +
                                    "unexpectedly"
''')
old = '''        if (
            ownsCore.compareAndSet(
                true,
                false
            )
        ) {
            val stopped =
                core.stop()

            if (!stopped.success) {
                Diagnostics.error(
                    "Proxy destroy stop failed: " +
                        stopped.error
                )
            }
        }
'''
new = '''        if (
            !restartExpected &&
            ownsCore.compareAndSet(
                true,
                false
            )
        ) {
            val stopped =
                core.stop()

            if (!stopped.success) {
                Diagnostics.error(
                    "Proxy destroy stop failed: " +
                        stopped.error
                )
            }
        } else if (restartExpected) {
            Diagnostics.info(
                "Proxy service destroy skipped core stop because Android restart is expected"
            )
        }
'''
if old in s:
    s = s.replace(old, new, 1)
write(p, s)

# MainActivity: import conversion manager and add subscription conversion actions.
p = 'app/src/main/java/com/trivox/client/ui/MainActivity.kt'
s = read(p)
s = insert_after(s, 'import com.trivox.client.core.CoreManager\n', 'import com.trivox.client.core.CoreConversionManager\n')

s = s.replace('''        val labels = arrayOf(
            getString(R.string.subscription_open_settings),
            getString(R.string.subscription_export_links),
            getString(R.string.subscription_delete_all_profiles),
            getString(R.string.subscription_delete_without_tcp),
            getString(R.string.subscription_delete_without_real),
            getString(R.string.subscription_delete_without_both)
        )
''', '''        val labels = arrayOf(
            getString(R.string.subscription_open_settings),
            getString(R.string.subscription_export_links),
            getString(R.string.subscription_convert_xray),
            getString(R.string.subscription_convert_singbox),
            getString(R.string.subscription_convert_mihomo),
            getString(R.string.subscription_delete_all_profiles),
            getString(R.string.subscription_delete_without_tcp),
            getString(R.string.subscription_delete_without_real),
            getString(R.string.subscription_delete_without_both)
        )
''')

s = s.replace('''                    2 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) { true }
                    3 -> confirmSubscriptionProfileCleanup(
''', '''                    2 -> confirmConvertSubscription(source, profiles, CoreId.XRAY)
                    3 -> confirmConvertSubscription(source, profiles, CoreId.SING_BOX)
                    4 -> confirmConvertSubscription(source, profiles, CoreId.MIHOMO)
                    5 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) { true }
                    6 -> confirmSubscriptionProfileCleanup(
''')

s = s.replace('''                    4 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.realTestStatus != TestStatus.ALIVE ||
                            it.realLatencyMs == null
                    }
                    5 -> confirmSubscriptionProfileCleanup(
''', '''                    7 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.realTestStatus != TestStatus.ALIVE ||
                            it.realLatencyMs == null
                    }
                    8 -> confirmSubscriptionProfileCleanup(
''')

method = '''
    private fun confirmConvertSubscription(
        source: SubscriptionSource,
        profiles: List<ConfigProfile>,
        target: CoreId
    ) {
        if (profiles.isEmpty()) {
            toast(getString(R.string.subscription_cleanup_none))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.subscription_convert_title, target.label))
            .setMessage(getString(R.string.subscription_convert_confirm, profiles.size, source.name, target.label))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.convert) { _, _ ->
                storageWorker.execute {
                    val summary = CoreConversionManager(this).convertSubscription(source, profiles, target)
                    runOnUiThread {
                        subscriptionTabsSignature = ""
                        renderSubscriptionTabs(force = true)
                        refresh()
                        toast(
                            getString(
                                R.string.subscription_convert_done,
                                summary.added,
                                summary.failed,
                                target.label
                            )
                        )
                    }
                }
            }
            .show()
    }

'''
s = insert_before(s, '    private fun exportSubscriptionLinks(', method)
write(p, s)

# Strings
additions = {
    'app/src/main/res/values/strings.xml': '''    <string name="convert">Convert</string>
    <string name="subscription_convert_xray">Convert this subscription to Xray profiles</string>
    <string name="subscription_convert_singbox">Convert this subscription to sing-box profiles</string>
    <string name="subscription_convert_mihomo">Convert this subscription to mihomo profiles</string>
    <string name="subscription_convert_title">Convert to %1$s</string>
    <string name="subscription_convert_confirm">Convert %1$d profiles in %2$s to %3$s and add the converted copies to the same subscription?</string>
    <string name="subscription_convert_done">Converted %1$d profiles, failed %2$d for %3$s</string>
''',
    'app/src/main/res/values-fa/strings.xml': '''    <string name="convert">تبدیل</string>
    <string name="subscription_convert_xray">تبدیل این ساب به پروفایل‌های Xray</string>
    <string name="subscription_convert_singbox">تبدیل این ساب به پروفایل‌های sing-box</string>
    <string name="subscription_convert_mihomo">تبدیل این ساب به پروفایل‌های mihomo</string>
    <string name="subscription_convert_title">تبدیل به %1$s</string>
    <string name="subscription_convert_confirm">تعداد %1$d کانفیگ از %2$s به %3$s تبدیل و نسخه‌های تبدیل‌شده به همان ساب اضافه شود؟</string>
    <string name="subscription_convert_done">%1$d کانفیگ تبدیل شد، %2$d ناموفق برای %3$s</string>
'''
}
for path, text in additions.items():
    s = read(path)
    if 'subscription_convert_singbox' not in s:
        s = s.replace('</resources>', text + '</resources>')
        write(path, s)

print('Root multicore patch applied')
