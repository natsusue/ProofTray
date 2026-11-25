import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class CollectLicenseAssets : Sync() {
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty

    fun notice(source: Any, assetName: String) {
        inputs.file(source).withPathSensitivity(PathSensitivity.RELATIVE)
        from(source) {
            into("licenses")
            rename { assetName }
        }
    }
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").reader().use { load(it) }
}

android {
    namespace = "org.prooftray.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // Installed identity owns the photo database, URI grants and camera pairing preferences.
        applicationId = "org.openbridge.app"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isDebuggable = false
            // Keep the validated JNI/reflection paths intact for this preview release.
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

androidComponents.onVariants { variant ->
    val root = rootProject.layout.projectDirectory
    val ndkNotice = androidComponents.sdkComponents.ndkDirectory.map { it.file("NOTICE.toolchain") }
    val task = tasks.register<CollectLicenseAssets>("collect${variant.name.replaceFirstChar { it.uppercaseChar() }}LicenseAssets") {
        into(assetDirectory)
        notice(root.file("LICENSE"), "ProofTray-Apache-2.0.txt")
        notice(root.file("THIRD_PARTY_NOTICES.md"), "THIRD_PARTY_NOTICES.md")
        notice(root.file("third_party/furble/LICENSE"), "furble-MIT.txt")
        for (name in listOf("COPYRIGHT", "LICENSE.LGPL", "LICENSE.CDDL")) {
            notice(root.file("third_party/libraw/upstream/$name"), "LibRaw-$name.txt")
        }
        notice(root.file("third_party/libtiff/upstream/LICENSE.md"), "libtiff-LICENSE.md")
        notice(ndkNotice, "Android-NDK-NOTICE.txt")
        from(root.dir("licenses")) {
            include("*.txt")
            into("licenses")
        }
    }
    variant.sources.assets?.addGeneratedSourceDirectory(task, CollectLicenseAssets::assetDirectory)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha09")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("no.nordicsemi.android:ble-ktx:2.11.0")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("com.squareup.okio:okio:3.16.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
