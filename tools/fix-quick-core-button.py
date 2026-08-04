#!/usr/bin/env python3
from pathlib import Path

p = Path("app/src/main/java/com/trivox/client/ui/MainActivity.kt")
s = p.read_text(encoding="utf-8")

if "import com.trivox.client.data.CoreId\n" not in s:
    s = s.replace(
        "import com.trivox.client.data.ConnectionState\n",
        "import com.trivox.client.data.ConnectionState\nimport com.trivox.client.data.CoreId\n"
    )

if "private lateinit var quickCoreButton: Button" not in s:
    s = s.replace(
        "    private lateinit var modeSpinner: Spinner\n",
        "    private lateinit var modeSpinner: Spinner\n    private lateinit var quickCoreButton: Button\n"
    )

if "quickCoreButton = findViewById(R.id.quickCoreButton)" not in s:
    s = s.replace(
        "        modeSpinner = findViewById(R.id.modeSpinner)\n",
        "        modeSpinner = findViewById(R.id.modeSpinner)\n        quickCoreButton = findViewById(R.id.quickCoreButton)\n"
    )

s = s.replace(
    "findViewById<Button>(R.id.quickCoreButton).setOnClickListener { showCorePicker() }",
    "quickCoreButton.setOnClickListener { showCorePicker() }"
)

s = s.replace(
    "findViewById<Button>(R.id.quickCoreButton).text = if (settings.smartCoreSelection) {",
    "quickCoreButton.text = if (settings.smartCoreSelection) {"
)

if "quickCoreButton.setOnClickListener { showCorePicker() }" not in s:
    s = s.replace(
        "        findViewById<EditText>(R.id.searchInput)\n",
        "        quickCoreButton.setOnClickListener { showCorePicker() }\n"
        "        renderCoreButton()\n\n"
        "        findViewById<EditText>(R.id.searchInput)\n"
    )

p.write_text(s, encoding="utf-8")
