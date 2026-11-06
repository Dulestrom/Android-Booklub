plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.dulestrom.booklub"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.dulestrom.booklub"
        // Android SRS BR-09: the current major version and the four before it.
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.0"
        // The Rust core is built for these two only, so 32-bit phones must not install the app.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The Rust library is already stripped of debug information.
        jniLibs.keepDebugSymbols += "**/libbooklub_mobile.so"
    }
}

dependencies {
    // Compose 1.12 needs compileSdk 37, which Ubuntu does not package yet. 1.11 builds against 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Scanning the pairing code (plan P10): CameraX and ZXing, with no Google Play services.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.zxing:core:3.5.4")
    // The generated Kotlin bindings call the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // The bridge test loads the Rust library built for this computer, through desktop JNA.
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.java.dev.jna:jna:5.19.1")
}

tasks.withType<Test>().configureEach {
    systemProperty("jna.library.path", rootProject.file("rust/target/release").absolutePath)
}
