# Complete project tree

```text
trivox/
├── .github/
│   └── workflows/
│       └── build.yml
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/core-manifest.json
│       │   ├── java/com/trivox/client/
│       │   │   ├── TrivoxApp.kt
│       │   │   ├── config/
│       │   │   │   ├── ConfigParser.kt
│       │   │   │   └── XrayConfigBuilder.kt
│       │   │   ├── core/
│       │   │   │   ├── CoreManager.kt
│       │   │   │   ├── CoreModels.kt
│       │   │   │   └── XrayCoreAdapter.kt
│       │   │   ├── data/
│       │   │   │   ├── Models.kt
│       │   │   │   └── Repositories.kt
│       │   │   ├── network/
│       │   │   │   ├── PingManager.kt
│       │   │   │   └── SubscriptionManager.kt
│       │   │   ├── service/
│       │   │   │   ├── BootReceiver.kt
│       │   │   │   ├── ConnectionService.kt
│       │   │   │   ├── NotificationSupport.kt
│       │   │   │   └── TrivoxVpnService.kt
│       │   │   ├── ui/
│       │   │   │   ├── AppRoutingActivity.kt
│       │   │   │   ├── DiagnosticsActivity.kt
│       │   │   │   ├── MainActivity.kt
│       │   │   │   ├── ProfileAdapter.kt
│       │   │   │   └── SettingsActivity.kt
│       │   │   └── util/Diagnostics.kt
│       │   └── res/
│       │       ├── drawable/
│       │       │   ├── ic_launcher.xml
│       │       │   └── row_background.xml
│       │       ├── layout/
│       │       │   ├── activity_app_routing.xml
│       │       │   ├── activity_diagnostics.xml
│       │       │   ├── activity_main.xml
│       │       │   ├── activity_settings.xml
│       │       │   ├── dialog_import.xml
│       │       │   ├── row_app.xml
│       │       │   └── row_profile.xml
│       │       ├── values/
│       │       │   ├── colors.xml
│       │       │   ├── strings.xml
│       │       │   └── themes.xml
│       │       ├── values-fa/strings.xml
│       │       ├── values-night/themes.xml
│       │       └── xml/locales_config.xml
│       └── test/java/com/trivox/client/ConfigParserTest.kt
├── core-input/README.md
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── tools/
│   ├── generate-core-manifest.py
│   ├── prepare-core.ps1
│   ├── prepare-core.sh
│   ├── trivox-wizard.ps1
│   └── trivox-wizard.sh
├── .gitignore
├── BUILD_VERIFICATION.md
├── BUILDING.md
├── CHANGELOG.md
├── CORE_INTEGRATION.md
├── LICENSE
├── LICENSES.md
├── PROJECT_TREE.md
├── README.md
├── README_FA.md
├── SECURITY.md
├── build.gradle.kts
├── build.ps1
├── build.sh
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

`app/libs/libXray.aar` and ABI directories are generated only after the wizard validates an official Android artifact; they are intentionally absent from the source ZIP.
