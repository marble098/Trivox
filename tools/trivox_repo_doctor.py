#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path.cwd()
checks = []

def check(name, ok, detail=""):
    checks.append((name, ok, detail))
    prefix = "OK " if ok else "BAD"
    print(f"{prefix} {name}" + (f": {detail}" if detail else ""))

def text(path):
    p = ROOT / path
    return p.read_text(encoding="utf-8") if p.exists() else ""

core = text("app/src/main/java/com/trivox/client/core/CoreManager.kt")
vpn = text("app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt")
main = text("app/src/main/java/com/trivox/client/ui/MainActivity.kt")
importer = text("app/src/main/java/com/trivox/client/config/NativeConfigImporter.kt")
workflow = text(".github/workflows/trivox-multicore-binaries.yml")

check("CoreManager has true native VPN bridge verifier", "verifyNativeBridgeRoute(" in core)
check("CoreManager verifies native VPN through local proxy proof", "Native VPN bridge proof passed" in core and "mode = ConnectionMode.PROXY" in core)
check("CoreManager exposes exact self-bypass decision", "requiresSelfBypassForVpn(" in core)
check("TrivoxVpnService no longer bypasses app only because Smart is enabled", "settings.smartCoreSelection ||" not in vpn and "core.requiresSelfBypassForVpn(profile, settings)" in vpn)
check("MainActivity quickCoreButton duplicate setup cleaned", main.count("findViewById<Button>(R.id.quickCoreButton).setOnClickListener") <= 1, str(main.count("findViewById<Button>(R.id.quickCoreButton).setOnClickListener")))
check("File picker accepts YAML and sing-box config MIME hints", "application/x-yaml" in main and "application/x-sing-box-config" in main)
check("Native importer is wired into ConfigParser path", "NativeConfigImporter" in text("app/src/main/java/com/trivox/client/config/ConfigParser.kt"))
check("Native installer subscription URL parser exists", "nativeInstallSubscriptionUrl" in importer)
check("Workflow runs static doctor", "trivox_repo_doctor.py" in workflow)
check("Workflow uses setup-java v5", "actions/setup-java@v5" in workflow)

failed = [name for name, ok, _ in checks if not ok]
report_dir = ROOT / "build" / "reports"
report_dir.mkdir(parents=True, exist_ok=True)
(report_dir / "trivox-doctor.txt").write_text(
    "\n".join([f"{'OK' if ok else 'BAD'} {name} {detail}".rstrip() for name, ok, detail in checks]) + "\n",
    encoding="utf-8"
)
if failed:
    print("\nCritical debug checks failed:")
    for name in failed:
        print(" - " + name)
    sys.exit(1)
print("\nAll Trivox deep debug checks passed.")
