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
    // The Kotlin srcDir must resolve to a concrete File so the Kotlin compile
    // task picks up the generated bindings even before they exist on disk.
    sourceSets {
        named("main") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi").get().asFile)
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
//
// Three tasks compose the Rust → Android pipeline:
//   1. hostBuild       — `cargo build --release -p oppolink-protocol` for the
//                        host triple. Produces a .dylib/.so that UniFFI's
//                        bindgen can introspect with the same architecture
//                        it's running on.
//   2. uniffiBindgen   — runs `cargo run --bin uniffi-bindgen` against the
//                        host library and writes Kotlin into
//                        build/generated/uniffi/.
//   3. cargoNdkBuild   — `cargo ndk … build --release` for arm64-v8a +
//                        armeabi-v7a. Writes the Android .so files into
//                        src/main/jniLibs/<abi>/.
//
// Splitting host vs Android keeps the bindgen step cross-arch safe; UniFFI
// 0.28's `--library` mode requires the binary to load the .so/.dylib at
// runtime, which fails when the host and target architectures differ.

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

/** Host-triple shared library used by `uniffi-bindgen` (and host-only tests). */
val hostLibFile: java.io.File =
    rustWorkspaceDir.get().resolve("target/release").let { releaseDir ->
        val os = org.gradle.internal.os.OperatingSystem.current()
        val name = when {
            os.isMacOsX -> "lib$rustCrateLib.dylib"
            os.isLinux -> "lib$rustCrateLib.so"
            os.isWindows -> "$rustCrateLib.dll"
            else -> error("Unsupported host OS for UniFFI bindgen: $os")
        }
        releaseDir.resolve(name)
    }

val hostBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build the host-target release library for UniFFI bindgen."

    workingDir = rustWorkspaceDir.get()
    inputs.dir(rustWorkspaceDir.get().resolve(rustCrateName).resolve("src"))
    inputs.file(rustWorkspaceDir.get().resolve(rustCrateName).resolve("Cargo.toml"))
    inputs.file(rustWorkspaceDir.get().resolve("Cargo.toml"))
    outputs.file(hostLibFile)

    commandLine("cargo", "build", "--release", "-p", rustCrateName)
}

val uniffiBindgen by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate Kotlin bindings from the host $rustCrateName library."
    dependsOn(hostBuild)

    workingDir = rustWorkspaceDir.get()
    inputs.file(hostLibFile)
    outputs.dir(uniffiOutDir)

    commandLine(
        "cargo", "run", "-p", rustCrateName, "--bin", "uniffi-bindgen", "--",
        "generate", "--library", hostLibFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiOutDir.get().asFile.absolutePath,
    )
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile $rustCrateName for Android ABIs via cargo-ndk."

    // Same Cargo target/ directory as `hostBuild`; serialize to avoid lockfile
    // contention without forcing a real dependency edge.
    mustRunAfter(hostBuild)

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

// preBuild fires before AGP's variant tasks (jniLibs packaging, etc.).
tasks.named("preBuild").configure { dependsOn(cargoNdkBuild, uniffiBindgen) }

// preBuild only fires before the Android variant tasks; Kotlin's compileXxxKotlin
// is wired separately and can race the bindgen. Bind the Kotlin compile tasks
// explicitly to guarantee generated sources exist before they are read.
afterEvaluate {
    tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
        .configureEach { dependsOn(uniffiBindgen) }
}
