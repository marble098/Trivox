// TRIVOX_V19_NATIVE_WIREGUARD_LEAK_GUARD
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supportedAbis = listOf(
    "arm64-v8a",
    "armeabi-v7a",
    "x86_64"
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

        ndk {
            abiFilters +=
                requestedAbis
        }
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
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17
        targetCompatibility =
            JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = false
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
                "META-INF/LICENSE.txt"
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
    coreLibraryDesugaring(
        "com.android.tools:desugar_jdk_libs:2.0.3"
    )
    implementation(
        "com.wireguard.android:tunnel:1.0.20260102"
    )

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
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
