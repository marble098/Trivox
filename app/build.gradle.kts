import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val requestedAbis = providers.gradleProperty("trivoxAbis")
    .orElse("arm64-v8a,armeabi-v7a,x86_64")
    .get()
    .split(',')
    .map(String::trim)
    .filter(supportedAbis::contains)
    .distinct()
    .ifEmpty { listOf("arm64-v8a", "armeabi-v7a") }

val generatedVersionCode = providers.environmentVariable("TRIVOX_VERSION_CODE")
    .orElse(providers.gradleProperty("trivoxVersionCode"))
    .orElse("10000")
    .get()
    .toIntOrNull()
    ?.coerceAtLeast(1)
    ?: 10000

val generatedVersionName = providers.environmentVariable("TRIVOX_VERSION_NAME")
    .orElse(providers.gradleProperty("trivoxVersionName"))
    .orElse("1.0.0")
    .get()
    .trim()
    .ifBlank { "1.0.0" }

android {
    namespace = "com.trivox.client"
    compileSdk = 36

    defaultConfig {
        applicationId = providers.gradleProperty("trivoxPackage").orElse("com.trivox.client").get()
        minSdk = 26
        targetSdk = 36
        versionCode = generatedVersionCode
        versionName = generatedVersionName
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "XRAY_VERSION", "\"26.7.28\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
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

    kotlin {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        noCompress += setOf("dat")
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/*.kotlin_module",
            "META-INF/licenses/**"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    if (file("libs/libXray.aar").isFile) {
        implementation(files("libs/libXray.aar"))
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

tasks.register("verifyXrayAar") {
    doLast {
        val aar = file("libs/libXray.aar")
        check(aar.isFile && aar.length() > 0L) {
            "Missing app/libs/libXray.aar. The cloud workflow must prepare Xray 26.7.28 before building."
        }
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    dependsOn("verifyXrayAar")
}
