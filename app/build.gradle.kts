plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
<<<<<<< HEAD
    namespace = "org.psyhackers.mashia"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.psyhackers.mashIA"
        minSdk = 24
        targetSdk = 34
        versionCode = 47
        versionName = "1.2.44"

        // Native (whisper.cpp scaffolding)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DUSE_WHISPERCPP=ON")
            }
        }
=======
    namespace = "com.rophiroth.mashia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rophiroth.mashia"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
>>>>>>> 066957aeb982b01080f077862cfa8d4e3bbbf5ec
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

<<<<<<< HEAD
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

=======
>>>>>>> 066957aeb982b01080f077862cfa8d4e3bbbf5ec
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

<<<<<<< HEAD
kotlinOptions {
    jvmTarget = "17"
}
=======
    kotlinOptions {
        jvmTarget = "17"
    }
>>>>>>> 066957aeb982b01080f077862cfa8d4e3bbbf5ec
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Navigation + Fragments + Lifecycle
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // UI lists
    implementation("androidx.recyclerview:recyclerview:1.3.2")

<<<<<<< HEAD
    // Images (avatar)
    implementation("io.coil-kt:coil:2.6.0")

    // ML Kit Language ID (on-device)
    implementation("com.google.mlkit:language-id:17.0.5")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Firebase (BOM + Auth). Esto compila aun si no hay google-services.json
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
}

// Ensure Kotlin uses JDK 17 toolchain even if a newer JDK is installed globally
kotlin {
    jvmToolchain(17)
}

// Aplica el plugin de Google Services solo si existe google-services.json
val hasGoogleServices = File(projectDir, "google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
=======
    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.2.0")
>>>>>>> 066957aeb982b01080f077862cfa8d4e3bbbf5ec
}
