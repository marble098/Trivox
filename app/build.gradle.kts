import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

val signingPropsFile = rootProject.file("signing.properties")

val signingProps = Properties().apply {
    if (signingPropsFile.isFile) {
        signingPropsFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.trivox.client"
    compileSdk = 36

    defaultConfig {
        applicationId = providers
            .gradleProperty("trivoxPackage")
            .orElse("com.trivox.client")
            .get()

        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += requestedAbis
        }
    }

    signingConfigs {
        if (signingPropsFile.isFile) {
            create("release") {
                storeFile = file(
                    signingProps.getProperty("storeFile")
                )

                storePassword =
                    signingProps.getProperty("storePassword")

                keyAlias =
                    signingProps.getProperty("keyAlias")

                keyPassword =
                    signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig =
                if (signingPropsFile.isFile) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*requestedAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    packaging {
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
            isReturnDefaultValues = true
        }
    }
}

/*
 * Kotlin 2.x compiler configuration.
 * This replaces the removed:
 * kotlinOptions.jvmTarget = "17"
 */
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    if (file("libs/libXray.aar").isFile) {
        implementation(files("libs/libXray.aar"))
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
