plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "link.oppolink.protocol"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }

    // UniFFI-generated Kotlin lands in build/generated/uniffi; the cargoNdkBuild task
    // writes .so files into src/main/jniLibs/<abi>/.
    sourceSets {
        named("main") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi"))
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

dependencies {
    // JNA is required at runtime by UniFFI's Kotlin runtime. The @aar variant ships
    // the bundled JNI shared library that JNA needs on Android.
    api("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit.ext)
    androidTestImplementation(libs.androidx.espresso.core)
}

// ─── Rust → JNI libs + UniFFI Kotlin bindings ────────────────────────────────
// This task pair wires `rust/oppolink-protocol` into the Android build:
//   1. cargoNdkBuild → cross-compiles the Rust crate for arm64-v8a + armeabi-v7a,
//      drops .so files into src/main/jniLibs/<abi>/.
//   2. uniffiBindgen → runs the `uniffi-bindgen` binary against the built .so to
//      emit Kotlin bindings into build/generated/uniffi/.
// Both tasks become dependencies of preBuild so a regular `assembleDebug` is enough.

val rustWorkspaceProp = providers.gradleProperty("oppolink.rustWorkspace")
val rustWorkspaceDir = rustWorkspaceProp.map { rootProject.projectDir.resolve(it) }
val rustCrateName = "oppolink-protocol"
val rustCrateLib = rustCrateName.replace('-', '_')

data class AndroidAbi(val abi: String, val rustTarget: String)

val supportedAbis = listOf(
    AndroidAbi("arm64-v8a", "aarch64-linux-android"),
    AndroidAbi("armeabi-v7a", "armv7-linux-androideabi"),
)

val jniLibsRoot = layout.projectDirectory.dir("src/main/jniLibs")
val uniffiOutDir = layout.buildDirectory.dir("generated/uniffi")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile $rustCrateName for Android ABIs via cargo-ndk."

    workingDir = rustWorkspaceDir.get()
    inputs.dir(rustWorkspaceDir.get().resolve(rustCrateName).resolve("src"))
    inputs.file(rustWorkspaceDir.get().resolve(rustCrateName).resolve("Cargo.toml"))
    inputs.file(rustWorkspaceDir.get().resolve("Cargo.toml"))
    outputs.dir(jniLibsRoot)

    val abiArgs = supportedAbis.flatMap { listOf("-t", it.rustTarget) }
    val cmd = mutableListOf("cargo", "ndk")
    cmd.addAll(abiArgs)
    cmd.addAll(listOf("-o", jniLibsRoot.asFile.absolutePath, "build", "--release", "-p", rustCrateName))
    commandLine(cmd)
}

val uniffiBindgen by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate Kotlin bindings from the compiled $rustCrateName library."
    dependsOn(cargoNdkBuild)

    workingDir = rustWorkspaceDir.get()
    val anyAbi = supportedAbis.first().abi
    val libFile = jniLibsRoot.dir(anyAbi).file("lib$rustCrateLib.so").asFile
    inputs.file(libFile)
    outputs.dir(uniffiOutDir)

    commandLine(
        "cargo", "run", "-p", rustCrateName, "--bin", "uniffi-bindgen", "--",
        "generate", "--library", libFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiOutDir.get().asFile.absolutePath,
    )
}

tasks.named("preBuild").configure { dependsOn(uniffiBindgen) }
