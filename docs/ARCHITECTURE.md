# Architecture

Companion to [README.md](../README.md). This document focuses on **how the
modules talk to each other**, the audio threading model, and the build
pipeline. Wire format and security live in their own docs.

## Module dependency graph

```
                ┌──────────────┐
                │     :app     │
                └──────┬───────┘
        ┌──────────────┼───────────────┬────────────────────┐
        ▼              ▼               ▼                    ▼
 :core-bluetooth  :core-audio   :coloros-compat       :core-protocol
        │              │                                    ▲
        └──────────────┴────────────────────────────────────┘
                          (Rust core via UniFFI)
                                    │
                          rust/oppolink-protocol
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                                           ▼
      rust/oppolink-codec                          rust/oppolink-jitter
```

`:core-protocol` is the only module that pulls in the Rust core. Everyone else
either depends on `:core-protocol` (`api` so transitive types stay visible) or
is leaf (`:coloros-compat`).

## Build pipeline

1. **Rust cross-compile.** `:core-protocol:cargoNdkBuild` runs
   `cargo ndk -t arm64-v8a -t armeabi-v7a -o src/main/jniLibs build --release
   -p oppolink-protocol`. Output: `liboppolink_protocol.so` per ABI.
2. **UniFFI bindgen.** `:core-protocol:uniffiBindgen` runs
   `cargo run -p oppolink-protocol --bin uniffi-bindgen -- generate --library
   <so> --language kotlin --out-dir build/generated/uniffi`. Output: a single
   `uniffi/oppolink_protocol/oppolink_protocol.kt` file.
3. **Compose / Kotlin compile.** AGP picks up the generated Kotlin from the
   added source set, and the `.so` files from `src/main/jniLibs/`.
4. **Package.** APK includes both ABIs and the JNA aar runtime.

## Threading model (Sprint 2+)

```
 ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
 │   capture thread │    │      TX thread   │    │      RX thread   │
 │  URGENT_AUDIO    │───▶│   write L2CAP    │    │   read L2CAP     │
 │ AudioRecord.read │    │  socket          │    │   socket         │
 │ Opus encode      │    └──────────────────┘    └────────┬─────────┘
 │ push SPSC ring   │                                     │
 └────────┬─────────┘                                     ▼
          │                                       ┌──────────────────┐
          ▼                                       │  jitter buffer   │
   (lock-free SPSC)                               │  (Rust)          │
                                                  └────────┬─────────┘
                                                           │ 20 ms tick
                                                           ▼
                                                  ┌──────────────────┐
                                                  │  playback thread │
                                                  │  URGENT_AUDIO    │
                                                  │ Opus decode      │
                                                  │ AudioTrack.write │
                                                  └──────────────────┘
```

Each box is a dedicated `Thread` (not a coroutine). Inter-thread queues are
either lock-free SPSC ring buffers (Rust side) or `LinkedBlockingQueue`s where
contention is rare (control plane).

## Why no `rust-android-gradle` plugin

The community plugin saves a handful of lines but adds an out-of-tree
maintenance dependency we cannot easily fork. Two `Exec` tasks give us:

- Explicit input/output declarations Gradle can cache against.
- Trivial debugging - the exact `cargo ndk` and `uniffi-bindgen` command lines
  appear in the build log.
- Zero risk of breaking on a future AGP upgrade.

The cost is ~30 lines of `build.gradle.kts` in `:core-protocol`. Worth it.

## Out-of-scope for Sprint 1 D1

- BLE advertising / scanning (D2).
- L2CAP socket lifecycle (D3, D4).
- Audio capture/playback (Sprint 2).
- ColorOS deep links (Sprint 3 D10).
- Encryption (Sprint 4 D13).

These are documented here only so the module split is justified by the
upcoming work - not to suggest D1 includes any of it.
