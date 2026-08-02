# Building

## Requirements

- Android Studio compatible with AGP 8.13.x
- Android SDK Platform 36 and Build Tools 35.0.0 or newer
- JDK 17
- Python 3, curl, unzip, and SHA-256 tool for the Bash wizard
- PowerShell 5.1+ or PowerShell 7 and Python 3 for the Windows wizard

No NDK, Go, gomobile, CMake, Node.js, or unusual local build tool is required when the official prebuilt libXray AAR is used.

## Android Studio

1. Run the wizard once.
2. Open this project root in Android Studio.
3. Allow the pinned Gradle wrapper to sync.
4. Select the `app` run configuration.
5. Build an APK or use `build.sh`/`build.ps1` for consistently named outputs.

The first sync downloads AGP, Kotlin, and three AndroidX runtime dependencies. Version catalogs and large frameworks are intentionally absent.

## Commands

```bash
./build.sh debug
./build.sh release
./build.sh arm64-v8a
./build.sh armeabi-v7a
./build.sh x86_64
./build.sh universal
./build.sh all
```

Each command validates `libXray.aar` against `core-manifest.json`, invokes the appropriate Gradle task, copies APKs to `dist/`, and calculates SHA-256. Release minification and resource shrinking are enabled.

## Signing

Create an untracked `signing.properties`:

```properties
storeFile=/absolute/path/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

On GitHub, configure `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Secrets are written only to temporary runner files, never printed, and deleted after the build. Without them, release variants use the installable debug key and are clearly workflow artifacts, not production-signed releases.

## Upload to GitHub

Authenticate without placing a token in the project:

```bash
gh auth login
gh auth status
./tools/trivox-wizard.sh --repo-owner USER --repo-name trivox --create-repo --private-repo --push
```

Or initialize and push with normal Git commands. The workflow uses the pinned `v26.7.28` version for ordinary push/PR runs. Stable or prerelease updates happen only through explicit manual inputs.
