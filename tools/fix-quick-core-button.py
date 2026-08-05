#!/usr/bin/env python3
from pathlib import Path
import re
import sys

MAIN = Path("app/src/main/java/com/trivox/client/ui/MainActivity.kt")
LAYOUT = Path("app/src/main/res/layout/activity_main.xml")

QUICK_LINE = "findViewById<Button>(R.id.quickCoreButton).setOnClickListener { showCorePicker() }"
SEARCH_ANCHOR = "        findViewById<EditText>(R.id.searchInput)"


def ensure_imports(s: str) -> str:
    if "import com.trivox.client.data.CoreId\n" not in s:
        s = s.replace(
            "import com.trivox.client.data.ConnectionState\n",
            "import com.trivox.client.data.ConnectionState\nimport com.trivox.client.data.CoreId\n",
            1,
        )
    return s


def remove_quick_button_property_style(s: str) -> str:
    s = re.sub(r"\n\s*private\s+lateinit\s+var\s+quickCoreButton\s*:\s*Button\s*\n", "\n", s)
    s = re.sub(r"\n\s*quickCoreButton\s*=\s*findViewById(?:<Button>)?\(R\.id\.quickCoreButton\)\s*\n", "\n", s)
    s = re.sub(r"(?<!\.)\bquickCoreButton\b", "findViewById<Button>(R.id.quickCoreButton)", s)
    return s


def remove_all_quick_listener_blocks(s: str) -> str:
    lines = s.splitlines()
    out = []
    removed = 0
    skip_possible_render = False
    for line in lines:
        if QUICK_LINE in line:
            removed += 1
            skip_possible_render = True
            continue
        if skip_possible_render and line.strip() == "renderCoreButton()":
            removed += 1
            skip_possible_render = False
            continue
        if line.strip():
            skip_possible_render = False
        out.append(line)
    if removed:
        print(f"quickCoreButton old listener/render lines removed: {removed}")
    return "\n".join(out) + ("\n" if s.endswith("\n") else "")


def insert_single_listener(s: str) -> str:
    if QUICK_LINE in s:
        return s
    if SEARCH_ANCHOR not in s:
        raise SystemExit("searchInput setup anchor not found in MainActivity.kt")
    block = (
        "        findViewById<Button>(R.id.quickCoreButton).setOnClickListener { showCorePicker() }\n"
        "        renderCoreButton()\n\n"
    )
    return s.replace(SEARCH_ANCHOR, block + SEARCH_ANCHOR, 1)


def ensure_render_methods(s: str) -> str:
    if "private fun renderCoreButton()" in s and "private fun showCorePicker()" in s:
        return s
    anchor = "\n\n    private fun showAddOptions() {"
    if anchor not in s:
        raise SystemExit("showAddOptions anchor not found in MainActivity.kt")
    methods = '''\n\n    private fun renderCoreButton() {
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
'''
    return s.replace(anchor, methods + "\n    private fun showAddOptions() {", 1)


def ensure_quick_core_layout() -> None:
    if not LAYOUT.exists():
        raise SystemExit("activity_main.xml not found")
    s = LAYOUT.read_text(encoding="utf-8")
    if '@+id/quickCoreButton' in s:
        return
    marker = '        <EditText\n            android:id="@+id/searchInput"'
    if marker not in s:
        raise SystemExit("searchInput marker not found in activity_main.xml")
    button = '''        <Button
            android:id="@+id/quickCoreButton"
            style="@style/Widget.Trivox.Button.Secondary"
            android:layout_width="wrap_content"
            android:layout_height="42dp"
            android:layout_marginStart="7dp"
            android:minWidth="92dp"
            android:paddingStart="10dp"
            android:paddingEnd="10dp"
            android:text="@string/core_manual_badge"
            android:textSize="11sp" />

'''
    LAYOUT.write_text(s.replace(marker, button + marker, 1), encoding="utf-8")


def main() -> None:
    if not MAIN.exists():
        raise SystemExit("MainActivity.kt not found")
    s = MAIN.read_text(encoding="utf-8")
    s = ensure_imports(s)
    s = remove_quick_button_property_style(s)
    s = ensure_render_methods(s)
    s = remove_all_quick_listener_blocks(s)
    s = insert_single_listener(s)
    MAIN.write_text(s, encoding="utf-8")
    ensure_quick_core_layout()

    left = [(i, line) for i, line in enumerate(s.splitlines(), 1) if re.search(r"(?<!\.)\bquickCoreButton\b", line)]
    if left:
        print("ERROR: bare quickCoreButton still exists:", file=sys.stderr)
        for i, line in left:
            print(f"{i}: {line}", file=sys.stderr)
        sys.exit(1)
    count = s.count(QUICK_LINE)
    if count != 1:
        raise SystemExit(f"quickCoreButton listener count must be 1, got {count}")
    print("quickCoreButton listener normalized: 1")


if __name__ == "__main__":
    main()
