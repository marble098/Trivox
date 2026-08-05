import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supportedAbis = listOf(
    "arm64-v8a",
    "armeabi-v7a"
)

val requestedAbis = providers
    .gradleProperty("trivoxAbis")
    .orElse(supportedAbis.joinToString(","))
    .get()
    .split(',')
    .map(String::trim)
    .filter(supportedAbis::contains)
    .distinct()

val signingPropsFile =
    rootProject.file("signing.properties")

val signingProps =
    Properties().apply {
        if (signingPropsFile.isFile) {
            signingPropsFile
                .inputStream()
                .use(::load)
        }
    }

val generatedVersionCode =
    providers
        .environmentVariable(
            "TRIVOX_VERSION_CODE"
        )
        .orElse(
            providers.gradleProperty(
                "trivoxVersionCode"
            )
        )
        .orElse("1001")
        .get()
        .toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 1001

val generatedVersionName =
    providers
        .environmentVariable(
            "TRIVOX_VERSION_NAME"
        )
        .orElse(
            providers.gradleProperty(
                "trivoxVersionName"
            )
        )
        .orElse("0.2.1")
        .get()
        .trim()
        .ifBlank { "0.2.1" }

val generatedGitSha =
    providers
        .environmentVariable(
            "TRIVOX_GIT_SHA"
        )
        .orElse(
            providers.gradleProperty(
                "trivoxGitSha"
            )
        )
        .orElse("source")
        .get()
        .trim()
        .replace(
            Regex("[^A-Za-z0-9._-]"),
            ""
        )
        .take(40)
        .ifBlank { "source" }

android {
    namespace = "com.trivox.client"
    compileSdk = 36

    defaultConfig {
        applicationId =
            providers
                .gradleProperty(
                    "trivoxPackage"
                )
                .orElse(
                    "com.trivox.client"
                )
                .get()

        minSdk = 26
        targetSdk = 36
        versionCode =
            generatedVersionCode
        versionName =
            generatedVersionName

        buildConfigField(
            "String",
            "GIT_SHA",
            "\"$generatedGitSha\""
        )
        buildConfigField(
            "int",
            "BUILD_NUMBER",
            generatedVersionCode
                .toString()
        )

        vectorDrawables {
            useSupportLibrary = true
        }

        testInstrumentationRunner =
            "androidx.test.runner." +
                "AndroidJUnitRunner"
}

    signingConfigs {
        if (signingPropsFile.isFile) {
            create("persistent") {
                storeFile = file(
                    signingProps
                        .getProperty(
                            "storeFile"
                        )
                )
                storePassword =
                    signingProps
                        .getProperty(
                            "storePassword"
                        )
                keyAlias =
                    signingProps
                        .getProperty(
                            "keyAlias"
                        )
                keyPassword =
                    signingProps
                        .getProperty(
                            "keyPassword"
                        )
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix =
                ".debug"
            versionNameSuffix =
                "-debug"

            if (
                signingPropsFile.isFile
            ) {
                signingConfig =
                    signingConfigs
                        .getByName(
                            "persistent"
                        )
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true

            if (
                signingPropsFile.isFile
            ) {
                signingConfig =
                    signingConfigs
                        .getByName(
                            "persistent"
                        )
            }

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-" +
                        "optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(
                *requestedAbis
                    .toTypedArray()
            )
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17
        targetCompatibility =
            JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    androidResources {
        noCompress += setOf("dat", "db", "mmdb", "metadb")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/licenses/*"
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues =
                true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            JvmTarget.JVM_17
        )
    }
}

dependencies {
    implementation(
        "androidx.core:" +
            "core-ktx:1.17.0"
    )
    implementation(
        "androidx.appcompat:" +
            "appcompat:1.7.1"
    )
    implementation(
        "androidx.recyclerview:" +
            "recyclerview:1.4.0"
    )

    // Jetpack Compose & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    if (
        file(
            "libs/libXray.aar"
        ).isFile
    ) {
        implementation(
            files(
                "libs/libXray.aar"
            )
        )
    }

    testImplementation(
        "junit:junit:4.13.2"
    )
    testImplementation(
        "org.json:json:20250517"
    )
}

