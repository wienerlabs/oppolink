plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "link.oppolink"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "link.oppolink"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Match the ABIs we cross-compile for in :core-protocol.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Sprint 4 D15 - release signing. Reads `signing.properties` at the
    // repo root for local dev, or the matching env vars in CI. Either
    // path is optional; when neither is present the release build falls
    // back to the debug signing config so unit / smoke builds still
    // succeed without secrets.
    signingConfigs {
        create("release") {
            val signingProps = rootProject.file("../signing.properties")
            when {
                signingProps.exists() -> {
                    val props = java.util.Properties().apply {
                        signingProps.inputStream().use { load(it) }
                    }
                    storeFile = file(props.getProperty("RELEASE_STORE_FILE"))
                    storePassword = props.getProperty("RELEASE_STORE_PASSWORD")
                    keyAlias = props.getProperty("RELEASE_KEY_ALIAS")
                    keyPassword = props.getProperty("RELEASE_KEY_PASSWORD")
                }
                System.getenv("RELEASE_STORE_FILE") != null -> {
                    storeFile = file(System.getenv("RELEASE_STORE_FILE"))
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sprint 4 D15 - only attach the release signing config when
            // the credentials are actually present. Otherwise fall back
            // to debug signing so CI smoke builds and local `gradle help`
            // don't choke on missing files.
            val rs = signingConfigs.getByName("release")
            signingConfig = if (rs.storeFile != null) rs else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        // Sprint 4 polish - Settings screen reads BuildConfig.VERSION_NAME
        // / VERSION_CODE so the displayed version stays in lockstep with
        // the Gradle config without a manual constant.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-bluetooth"))
    implementation(project(":core-audio"))
    implementation(project(":coloros-compat"))

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
