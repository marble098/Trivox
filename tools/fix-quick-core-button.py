#!/usr/bin/env python3
from pathlib import Path

p = Path("app/src/main/java/com/trivox/client/ui/MainActivity.kt")
s = p.read_text(encoding="utf-8")

if "import com.trivox.client.data.CoreId\n" not in s:
    s = s.replace(
        "import com.trivox.client.data.ConnectionState\n",
        "import com.trivox.client.data.ConnectionState\nimport com.trivox.client.data.CoreId\n"
    )

s = s.replace("    private lateinit var quickCoreButton: Button\n", "")
s = s.replace("        quickCoreButton = findViewById(R.id.quickCoreButton)\n", "")

if "findViewById<Button>(R.id.quickCoreButton).setOnClickListener { showCorePicker() }" not in s:
    s = s.replace(
        "        findViewById<EditText>(R.id.searchInput)\n",
        "        findViewById<Button>(R.id.quickCoreButton).setOnClickListener { showCorePicker() }\n"
        "        renderCoreButton()\n\n"
        "        findViewById<EditText>(R.id.searchInput)\n"
    )

s = s.replace(
    "        quickCoreButton.text = if (settings.smartCoreSelection) {",
    "        findViewById<Button>(R.id.quickCoreButton).text = if (settings.smartCoreSelection) {"
)

if "private fun renderCoreButton()" not in s:
    s = s.replace(
        "\n\n    private fun showAddOptions() {",
        '''\n\n    private fun renderCoreButton() {
        val settings = settingsRepository.load()
        findViewById<Button>(R.id.quickCoreButton).text = if (settings.smartCoreSelection) {
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

    private fun showAddOptions() {'''
    )

p.write_text(s, encoding="utf-8")
