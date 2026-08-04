#!/usr/bin/env python3
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
package_name = sys.argv[2]
text = path.read_text(encoding="utf-8")
replacements = {
    "TERMUX_APP__PACKAGE_NAME": package_name,
    "TERMUX_APP__DATA_DIR": f"/data/data/{package_name}",
}
for key, value in replacements.items():
    pattern = re.compile(rf"^{re.escape(key)}=.*$", re.MULTILINE)
    line = f'{key}="{value}"'
    if pattern.search(text):
        text = pattern.sub(line, text)
    else:
        text += "\n" + line + "\n"
path.write_text(text, encoding="utf-8")
