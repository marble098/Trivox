# Project tree — Compose Material 3 source

```text
trivox/
├── .github/workflows/
│   ├── main.yml
│   └── openssh-binaries.yml
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   ├── java/com/trivox/client/
│       │   │   ├── config/
│       │   │   ├── core/
│       │   │   ├── data/
│       │   │   ├── network/
│       │   │   ├── service/
│       │   │   ├── ssh/
│       │   │   ├── ui/
│       │   │   │   ├── MainActivity.kt
│       │   │   │   ├── SettingsActivity.kt
│       │   │   │   ├── SubscriptionManagementActivity.kt
│       │   │   │   ├── AppRoutingActivity.kt
│       │   │   │   └── compose/
│       │   │   │       ├── MainComposeScreen.kt
│       │   │   │       ├── RoutingComposeScreen.kt
│       │   │   │       ├── TrivoxComposeUi.kt
│       │   │   │       └── LegacyLayoutBridge.kt
│       │   │   └── util/
│       │   └── res/
│       │       ├── color/
│       │       ├── drawable/
│       │       ├── font/
│       │       ├── values/
│       │       │   ├── ids.xml
│       │       │   ├── strings.xml
│       │       │   ├── strings_compose.xml
│       │       │   └── themes.xml
│       │       ├── values-fa/
│       │       │   └── strings_compose.xml
│       │       ├── values-night/
│       │       └── xml/
│       └── test/
├── core-input/
├── gradle/wrapper/
├── tools/
│   ├── audit-trivox.py
│   ├── generate-core-manifest.py
│   ├── prepare-core.sh
│   ├── prepare-core.ps1
│   ├── replace-github-repo.sh
│   ├── trivox-wizard.sh
│   ├── trivox-wizard.ps1
│   ├── validate-android-resources.py
│   ├── validate-api-guards.py
│   ├── validate-string-formats.py
│   ├── verify-compose-migration.sh
│   └── openssh/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── build.sh
└── build.ps1
```

There is intentionally no `app/src/main/res/layout` directory. The visible application UI is Compose Material 3. The libXray AAR remains an externally prepared official artifact and is not embedded in the source package.
