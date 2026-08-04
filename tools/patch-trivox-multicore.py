#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def p(rel): return ROOT / rel

def rw(rel, fn):
    path = p(rel)
    s = path.read_text(encoding='utf-8')
    ns = fn(s)
    if ns != s:
        path.write_text(ns, encoding='utf-8')
        print('patched', rel)
    else:
        print('unchanged', rel)

def patch_models(s):
    if 'enum class CoreId' not in s:
        s = s.replace('enum class ConnectionState { DISCONNECTED, PREPARING, CONNECTING, CONNECTED, RECONNECTING, STOPPING, ERROR }\n',
'''enum class ConnectionState { DISCONNECTED, PREPARING, CONNECTING, CONNECTED, RECONNECTING, STOPPING, ERROR }
enum class CoreId {
    XRAY,
    SING_BOX,
    MIHOMO;

    val label: String
        get() = when (this) {
            XRAY -> "Xray"
            SING_BOX -> "sing-box"
            MIHOMO -> "mihomo"
        }

    companion object {
        fun fromStored(value: String?): CoreId = entries.firstOrNull {
            it.name.equals(value, true) || it.label.equals(value, true)
        } ?: XRAY
    }
}
''')
    if 'var coreId: CoreId' not in s:
        s = s.replace('data class AppSettings(\n    var mode: ConnectionMode = ConnectionMode.VPN,\n',
'''data class AppSettings(
    var mode: ConnectionMode = ConnectionMode.VPN,
    var coreId: CoreId = CoreId.XRAY,
    var smartCoreSelection: Boolean = false,
    var lastSmartCoreId: CoreId = CoreId.XRAY,
''')
        s = s.replace('fun toJson(): JSONObject = JSONObject()\n        .put("mode", mode.name)\n',
'''fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("coreId", coreId.name)
        .put("smartCoreSelection", smartCoreSelection)
        .put("lastSmartCoreId", lastSmartCoreId.name)
''')
        s = s.replace('mode = runCatching {\n                    ConnectionMode.valueOf(json.optString("mode"))\n                }.getOrDefault(ConnectionMode.VPN),\n',
'''mode = runCatching {
                    ConnectionMode.valueOf(json.optString("mode"))
                }.getOrDefault(ConnectionMode.VPN),
                coreId = CoreId.fromStored(json.optString("coreId")),
                smartCoreSelection = json.optBoolean("smartCoreSelection", false),
                lastSmartCoreId = CoreId.fromStored(json.optString("lastSmartCoreId")),
''')
    return s

def patch_core_manager(s):
    if 'CoreConfigTranslator' not in s:
        s = s.replace('import com.trivox.client.data.ConnectionState\n', 'import com.trivox.client.data.ConnectionState\nimport com.trivox.client.data.CoreId\nimport com.trivox.client.data.SettingsRepository\n')
        s = s.replace('val adapter: CoreAdapter = XrayCoreAdapter(appContext)\n',
'''private val adapters: Map<CoreId, CoreAdapter> = mapOf(
        CoreId.XRAY to XrayCoreAdapter(appContext),
        CoreId.SING_BOX to SingBoxCoreAdapter(appContext),
        CoreId.MIHOMO to MihomoCoreAdapter(appContext)
    )

    val adapter: CoreAdapter
        get() = selectedAdapter()

    private fun selectedCoreId(): CoreId = SettingsRepository(appContext).load().let {
        if (it.smartCoreSelection) it.lastSmartCoreId else it.coreId
    }

    private fun selectedAdapter(): CoreAdapter = adapters[selectedCoreId()] ?: adapters.getValue(CoreId.XRAY)
''')
        s = s.replace('val validation = adapter.validate(json)\n', 'val validation = selectedAdapter().validate(json)\n')
        s = s.replace('val started = adapter.start(json, protect)\n', 'val started = selectedAdapter().start(json, protect)\n')
        s = s.replace('runCatching { adapter.stop() }\n', 'runCatching { selectedAdapter().stop() }\n')
        s = s.replace('fun isRunning(): Boolean = adapter.state()\n', 'fun isRunning(): Boolean = selectedAdapter().state()\n')
        s = s.replace('fun stop(): CoreResult = adapter.stop()\n', 'fun stop(): CoreResult = selectedAdapter().stop()\n')
        s = s.replace('private fun buildJson(request: CoreStartRequest): String =\n        XrayConfigBuilder.build(\n            profile = request.profile,\n            settings = request.settings,\n            mode = request.mode,\n            tunFd = request.tunFd,\n            errorLogPath = Diagnostics.xrayErrorLogPath()\n        )\n',
'''private fun buildJson(request: CoreStartRequest): String =
        CoreConfigTranslator.build(request, selectedCoreId())

    fun switchCore(coreId: CoreId) {
        val settings = SettingsRepository(appContext).load()
        settings.coreId = coreId
        settings.smartCoreSelection = false
        SettingsRepository(appContext).save(settings)
    }

    fun smartSelect(request: CoreStartRequest): CoreId {
        val settings = SettingsRepository(appContext).load()
        if (!settings.smartCoreSelection) return settings.coreId
        val candidates = adapters.filterValues { it.isAvailable() }.keys.ifEmpty { setOf(CoreId.XRAY) }
        val selected = candidates.firstOrNull { it == CoreId.SING_BOX && request.mode == com.trivox.client.data.ConnectionMode.PROXY }
            ?: candidates.firstOrNull { it == CoreId.MIHOMO && request.mode == com.trivox.client.data.ConnectionMode.PROXY }
            ?: candidates.first()
        settings.lastSmartCoreId = selected
        SettingsRepository(appContext).save(settings)
        return selected
    }
''')
    return s

def patch_settings_activity(s):
    if 'CoreId' not in s:
        s = s.replace('import com.trivox.client.data.ConnectionMode\n' if 'import com.trivox.client.data.ConnectionMode' in s else 'import com.trivox.client.data.DnsMode\n', 'import com.trivox.client.data.DnsMode\nimport com.trivox.client.data.CoreId\n')
    if 'coreSpinner' not in s:
        s = s.replace('val language = spinner(R.id.languageSpinner)\n', 'val coreSpinner = spinner(R.id.coreSpinner)\n        val smartCore = switch(R.id.smartCoreSwitch)\n        val language = spinner(R.id.languageSpinner)\n')
        s = s.replace('language.adapter = compactAdapter(\n', 'val cores = CoreId.entries\n        coreSpinner.adapter = compactAdapter(cores.map { it.label }.toTypedArray())\n        coreSpinner.setSelection(cores.indexOf(settings.coreId).coerceAtLeast(0))\n        smartCore.isChecked = settings.smartCoreSelection\n\n        language.adapter = compactAdapter(\n')
        s = s.replace('settings.socksPort = port!!\n', 'settings.coreId = cores[coreSpinner.selectedItemPosition]\n            settings.smartCoreSelection = smartCore.isChecked\n            settings.socksPort = port!!\n')
    return s

def patch_settings_layout(s):
    if '@+id/coreSpinner' not in s:
        marker = '<TextView\n                style="@style/Widget.Trivox.SectionTitle"\n                android:text="@string/interface_section" />'
        block = '''<TextView
                style="@style/Widget.Trivox.SectionTitle"
                android:text="@string/core_selection_section" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="@drawable/panel_background"
                android:orientation="vertical"
                android:padding="10dp">

                <TextView style="@style/Widget.Trivox.FieldLabel" android:text="@string/core_engine" />
                <Spinner
                    android:id="@+id/coreSpinner"
                    android:layout_width="match_parent"
                    android:layout_height="42dp"
                    android:layout_marginTop="3dp"
                    android:background="@drawable/input_background"
                    android:paddingStart="10dp"
                    android:paddingEnd="8dp" />
                <androidx.appcompat.widget.SwitchCompat
                    android:id="@+id/smartCoreSwitch"
                    android:layout_width="match_parent"
                    android:layout_height="46dp"
                    android:layout_marginTop="5dp"
                    android:gravity="center_vertical"
                    android:text="@string/smart_core_selection"
                    android:textColor="@color/text_primary"
                    android:textSize="12sp"
                    app:showText="false" />
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:text="@string/smart_core_selection_summary"
                    android:textColor="@color/muted"
                    android:textSize="10sp" />
            </LinearLayout>

            ''' + marker
        s = s.replace(marker, block)
    return s

def patch_main_activity(s):
    if 'CoreId' not in s:
        s = s.replace('import com.trivox.client.data.ConnectionState\n', 'import com.trivox.client.data.ConnectionState\nimport com.trivox.client.data.CoreId\n')
    if 'quickCoreButton' not in s:
        s = s.replace('private lateinit var modeSpinner: Spinner\n', 'private lateinit var modeSpinner: Spinner\n    private lateinit var quickCoreButton: Button\n')
        s = s.replace('modeSpinner = findViewById(R.id.modeSpinner)\n', 'modeSpinner = findViewById(R.id.modeSpinner)\n        quickCoreButton = findViewById(R.id.quickCoreButton)\n')
        s = s.replace('findViewById<EditText>(R.id.searchInput)\n', 'quickCoreButton.setOnClickListener { showCorePicker() }\n        renderCoreButton()\n\n        findViewById<EditText>(R.id.searchInput)\n')
        s = s.replace('\n\n    private fun showAddOptions() {', '''

    private fun renderCoreButton() {
        val settings = settingsRepository.load()
        quickCoreButton.text = if (settings.smartCoreSelection) {
            getString(R.string.core_smart_badge, settings.lastSmartCoreId.label)
        } else {
            getString(R.string.core_manual_badge, settings.coreId.label)
        }
    }

    private fun showCorePicker() {
        val cores = CoreId.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.core_engine)
            .setItems(cores.map { it.label }.toTypedArray()) { _, pos ->
                val settings = settingsRepository.load()
                settings.coreId = cores[pos]
                settings.smartCoreSelection = false
                settingsRepository.save(settings)
                coreManager.switchCore(cores[pos])
                pingManager = PingManager(coreManager.adapter)
                renderCoreButton()
            }
            .setPositiveButton(R.string.smart_core_selection) { _, _ ->
                val settings = settingsRepository.load()
                settings.smartCoreSelection = true
                settingsRepository.save(settings)
                renderCoreButton()
            }
            .show()
    }

    private fun showAddOptions() {''')
    return s

def patch_main_layout(s):
    if '@+id/quickCoreButton' not in s:
        insert = '''
        <Button
            android:id="@+id/quickCoreButton"
            style="@style/Widget.Trivox.Button.Secondary"
            android:layout_width="wrap_content"
            android:layout_height="38dp"
            android:layout_marginTop="4dp"
            android:minWidth="92dp"
            android:paddingStart="10dp"
            android:paddingEnd="10dp"
            android:text="@string/core_manual_badge" />
'''
        # place after modeSpinner when possible
        s = s.replace('</Spinner>\n', '</Spinner>\n' + insert, 1)
    return s

def patch_strings(s, fa=False):
    if 'core_selection_section' not in s:
        if fa:
            add = '''
    <string name="core_selection_section">انتخاب هسته</string>
    <string name="core_engine">هسته برنامه</string>
    <string name="smart_core_selection">انتخاب هوشمند هسته</string>
    <string name="smart_core_selection_summary">هنگام اتصال، Trivox بین هسته‌های آماده تست سبک انجام می‌دهد و بهترین گزینه را ذخیره می‌کند.</string>
    <string name="core_manual_badge">هسته: %1$s</string>
    <string name="core_smart_badge">هوشمند: %1$s</string>
'''
        else:
            add = '''
    <string name="core_selection_section">Core selection</string>
    <string name="core_engine">App core</string>
    <string name="smart_core_selection">Smart core selection</string>
    <string name="smart_core_selection_summary">When connecting, Trivox performs a light readiness check across prepared cores and stores the best choice.</string>
    <string name="core_manual_badge">Core: %1$s</string>
    <string name="core_smart_badge">Smart: %1$s</string>
'''
        s = s.replace('</resources>', add + '</resources>')
    return s

files = [
    ('app/src/main/java/com/trivox/client/data/Models.kt', patch_models),
    ('app/src/main/java/com/trivox/client/core/CoreManager.kt', patch_core_manager),
    ('app/src/main/java/com/trivox/client/ui/SettingsActivity.kt', patch_settings_activity),
    ('app/src/main/res/layout/activity_settings.xml', patch_settings_layout),
    ('app/src/main/java/com/trivox/client/ui/MainActivity.kt', patch_main_activity),
    ('app/src/main/res/layout/activity_main.xml', patch_main_layout),
    ('app/src/main/res/values/strings.xml', lambda s: patch_strings(s, False)),
    ('app/src/main/res/values-fa/strings.xml', lambda s: patch_strings(s, True)),
]
for rel, fn in files:
    if p(rel).exists(): rw(rel, fn)
    else: print('missing', rel)
