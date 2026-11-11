plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.revgiler.messenger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.revgiler.messenger"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.12"
    }

    // Один ключ для всех сборок: его SHA-1 прописан в Firebase, без него не работает вход через Google.
    // В репозитории ключ лежит только зашифрованным; GitHub Actions расшифровывает его секретом SIGNING_KEY_PASS.
    val keystore = file("app.keystore")
    signingConfigs {
        if (keystore.exists()) {
            create("app") {
                storeFile = keystore
                storePassword = "app12345"
                keyAlias = "app"
                keyPassword = "app12345"
            }
        }
    }

    // Две версии одного приложения:
    //  github — для своего круга: APK из Releases и самообновление;
    //  play   — для Google Play: без самообновления и без «особых» разрешений (их Play не пропускает).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "PLAY", "false")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "PLAY", "true")
        }
    }

    buildTypes {
        val app = signingConfigs.findByName("app")
        debug {
            if (app != null) signingConfig = app
        }
        release {
            isMinifyEnabled = false
            signingConfig = app ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")

    // Звонки: готовая сборка WebRTC (та же библиотека, что в Chrome)
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    // Вход через Google (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")
}
