#!/usr/bin/env python3
from pathlib import Path
import re
import sys

p = Path("app/src/main/java/com/trivox/client/ui/MainActivity.kt")
s = p.read_text(encoding="utf-8")

if "import com.trivox.client.data.CoreId\n" not in s:
    s = s.replace(
        "import com.trivox.client.data.ConnectionState\n",
        "import com.trivox.client.data.ConnectionState\nimport com.trivox.client.data.CoreId\n"
    )

s = re.sub(
    r"\n\s*private\s+lateinit\s+var\s+quickCoreButton\s*:\s*Button\s*\n",
    "\n",
    s
)

s = re.sub(
    r"\n\s*quickCoreButton\s*=\s*findViewById(?:<Button>)?\(R\.id\.quickCoreButton\)\s*\n",
    "\n",
    s
)

s = re.sub(
    r"(?<!\.)\bquickCoreButton\b",
    "findViewById<Button>(R.id.quickCoreButton)",
    s
)

if "findViewById<Button>(R.id.quickCoreButton).setOnClickListener { showCorePicker() }" not in s:
    s = s.replace(
        "        findViewById<EditText>(R.id.searchInput)\n",
        "        findViewById<Button>(R.id.quickCoreButton).setOnClickListener { showCorePicker() }\n"
        "        renderCoreButton()\n\n"
        "        findViewById<EditText>(R.id.searchInput)\n"
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

left = [
    (i, line)
    for i, line in enumerate(s.splitlines(), 1)
    if re.search(r"(?<!\.)\bquickCoreButton\b", line)
]

if left:
    print("ERROR: bare quickCoreButton still exists:", file=sys.stderr)
    for i, line in left:
        print(f"{i}: {line}", file=sys.stderr)
    sys.exit(1)

def ensure_quick_core_layout():
    from pathlib import Path
    layout = Path("app/src/main/res/layout/activity_main.xml")
    s = layout.read_text(encoding="utf-8")
    button = (
        '        <Button\\n'
        '            android:id="@+id/quickCoreButton"\\n'
        '            style="@style/Widget.Trivox.Button.Secondary"\\n'
        '            android:layout_width="wrap_content"\\n'
        '            android:layout_height="42dp"\\n'
        '            android:layout_marginStart="7dp"\\n'
        '            android:minWidth="92dp"\\n'
        '            android:paddingStart="10dp"\\n'
        '            android:paddingEnd="10dp"\\n'
        '            android:text="@string/core_manual_badge"\\n'
        '            android:textSize="11sp" />\\n\\n'
    )
    marker = '        <EditText\\n            android:id="@+id/searchInput"'
    if '@+id/quickCoreButton' not in s:
        if marker not in s:
            raise SystemExit("searchInput marker not found in activity_main.xml")
        s = s.replace(marker, button + marker, 1)
        layout.write_text(s, encoding="utf-8")

ensure_quick_core_layout()
