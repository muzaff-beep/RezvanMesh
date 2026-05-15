plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.rezvani.mesh"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rezvani.mesh"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("String", "VERSION_NAME", "\"${versionName}\"")
        buildConfigField("int", "VERSION_CODE", "${versionCode}")
        buildConfigField("String", "BUILD_VARIANT", "\"civilian\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: project.rootProject.file("keystore.jks"))
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "rezvan"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    // ---- git info for provenance banner ----
    val gitSha = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()

    val gitBranch = providers.exec {
        commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
    }.standardOutput.asText.get().trim()

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "DEBUG_LOOPBACK", "false")
            buildConfigField("boolean", "DEBUG_INJECT_PEERS", "false")
            buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
            buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
            buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["debuggable"] = "false"
            buildConfigField("boolean", "DEBUG_LOOPBACK", "true")
            buildConfigField("boolean", "DEBUG_INJECT_PEERS", "true")
            buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
            buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
            buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "**/attach_hotspot_windows.dll"
            excludes += "META-INF/licenses/**"
            excludes += "META-INF/AL2.0"
            excludes += "META-INF/LGPL2.1"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        disable += "MissingTranslation"
        disable += "ExtraTranslation"
        checkReleaseBuilds = false
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room with SQLCipher

    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Local Broadcast Manager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
