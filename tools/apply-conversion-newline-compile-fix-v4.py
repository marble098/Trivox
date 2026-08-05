#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path

repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()

activity = repo / "app/src/main/java/com/trivox/client/ui/SubscriptionManagementActivity.kt"
generator = repo / "tools/apply-native-subscription-smartcore-fix.py"
doctor = repo / "tools/trivox-native-smart-doctor.py"

def require(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"required file missing: {path}")

def write_if_changed(path: Path, text: str) -> None:
    old = path.read_text(encoding="utf-8")
    if old == text:
        print(f"unchanged {path.relative_to(repo)}")
        return
    path.write_text(text, encoding="utf-8")
    print(f"patched {path.relative_to(repo)}")

for path in (activity, generator, doctor):
    require(path)

raw_error = '"' + chr(10) + '" + summary.firstError'
raw_warning = '"' + chr(10) + '" + warning'
escaped_error = '"\\n" + summary.firstError'
escaped_warning = '"\\n" + warning'
source_escaped_error = '"\\\\n" + summary.firstError'
source_escaped_warning = '"\\\\n" + warning'

# 1) Kotlin UI: repair physical newline literals produced by the generator.
ui = activity.read_text(encoding="utf-8")
ui = ui.replace(raw_error, escaped_error)
ui = ui.replace(raw_warning, escaped_warning)
write_if_changed(activity, ui)

ui = activity.read_text(encoding="utf-8")
if escaped_error not in ui or escaped_warning not in ui:
    raise SystemExit("Kotlin conversion dialog newline literals were not repaired")
if raw_error in ui or raw_warning in ui:
    raise SystemExit("raw quote-newline-quote still exists in SubscriptionManagementActivity.kt")

# 2) Generator: source must contain double backslash so the generated Kotlin contains a single escaped newline.
gen = generator.read_text(encoding="utf-8")
gen = gen.replace(escaped_error, source_escaped_error)
gen = gen.replace(escaped_warning, source_escaped_warning)
# idempotence: avoid turning already-correct source into too many backslashes
gen = gen.replace('"\\\\\\n" + summary.firstError', source_escaped_error)
gen = gen.replace('"\\\\\\n" + warning', source_escaped_warning)
write_if_changed(generator, gen)

gen = generator.read_text(encoding="utf-8")
if source_escaped_error not in gen or source_escaped_warning not in gen:
    raise SystemExit("generator newline literals are not double-escaped")

# 3) Doctor: remove any broken line from older v3 attempts, then add a syntactically safe check.
doc = doctor.read_text(encoding="utf-8")
lines = []
for line in doc.splitlines():
    if "Conversion dialog newline literals" in line or "Conversion dialog uses escaped newline literals" in line:
        continue
    lines.append(line)
doc = "\n".join(lines) + ("\n" if doc.endswith("\n") else "")

safe_check = (
    'check("Conversion dialog newline literals are compile-safe", '
    'escaped_error in ui and escaped_warning in ui and raw_error not in ui and raw_warning not in ui)\n'
)
prelude = (
    'raw_error = \'"\' + chr(10) + \'" + summary.firstError\'\n'
    'raw_warning = \'"\' + chr(10) + \'" + warning\'\n'
    'escaped_error = \'"\\\\n" + summary.firstError\'\n'
    'escaped_warning = \'"\\\\n" + warning\'\n'
)
anchor = 'check("Subscription name/row long press opens conversion", "setOnLongClickListener(conversionLongClick)" in ui and "showCoreConversion" in ui)\n'
if safe_check.strip() not in doc:
    if anchor not in doc:
        raise SystemExit("doctor insertion anchor missing")
    doc = doc.replace(anchor, prelude + anchor + safe_check, 1)
write_if_changed(doctor, doc)

print("Trivox conversion newline compile fix v4 applied")
