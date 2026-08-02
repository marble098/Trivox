# Build verification report

Date: 2026-08-02

## Passed

- Official `XTLS/libXray v26.7.28` Android release asset downloaded and matched the upstream release archive SHA-256 `28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666`.
- The preparation tool safely extracted `libXray.aar`, detected `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, generated a manifest, and revalidated the final AAR SHA-256.
- A copied project completed the full local-core wizard path for `arm64-v8a` and passed manifest/AAR validation.
- Bash syntax validation passed for `gradlew`, `build.sh`, `trivox-wizard.sh`, and `prepare-core.sh`.
- Python bytecode compilation passed for `generate-core-manifest.py`.
- All main Kotlin sources compiled with Kotlin 2.4.10 against Android API 36 plus the declared AndroidX bytecode.
- Android resource compilation passed with Android Build Tools 35 `aapt2`.
- 13 JUnit tests passed: standard and URL-safe Base64, VMess, VLESS/REALITY/IPv6, Trojan, Shadowsocks, exact duplicate handling, malformed fields, unsupported schemes, mixed subscription text, proxy/VPN Xray JSON generation, DNS/port validation, and core manifest parsing.
- No critical `TODO`, `FIXME`, fake connection result, simulated VPN, or bundled secret was found.

## Not executed in this workspace

The exact Gradle commands below were not executed because the workspace did not contain an Android SDK/JDK toolchain and Java dependency downloads were blocked by its network sandbox:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

Equivalent Kotlin source compilation, JUnit execution, AAPT2 resource compilation, core preparation, checksum validation, and script syntax checks passed as listed above. GitHub Actions and a normal Android Studio installation run the exact Gradle tasks. No APK size is reported because an APK was not produced locally.

PowerShell scripts were reviewed but not executed because PowerShell was unavailable in the workspace.
