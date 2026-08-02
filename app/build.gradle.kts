import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun commandOutput(vararg command: String): String? =
    runCatching {
        val process = ProcessBuilder(*command)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) output.takeIf(String::isNotBlank) else null
    }.getOrNull()

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

val gitCommitCount = commandOutput("git", "rev-list", "--count", "HEAD")
    ?.toIntOrNull()
    ?.coerceAtLeast(1)
    ?: 1

val gitShortSha = commandOutput("git", "rev-parse", "--short=8", "HEAD")
    ?: "source"

val generatedVersionCode = System.getenv("TRIVOX_VERSION_CODE")
    ?.toIntOrNull()
    ?.coerceAtLeast(1)
    ?: (1000 + gitCommitCount)

val generatedVersionName = System.getenv("TRIVOX_VERSION_NAME")
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: "0.2.$gitCommitCount"

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
        versionCode = generatedVersionCode
        versionName = generatedVersionName

        buildConfigField("String", "GIT_SHA", "\"$gitShortSha\"")
        buildConfigField("int", "BUILD_NUMBER", generatedVersionCode.toString())

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
