# `:core-protocol`

UniFFI bridge between the Rust core (`rust/oppolink-protocol`) and the Kotlin
side of the Android app. This module produces a typed Kotlin API and ships the
compiled `.so` files for `arm64-v8a` and `armeabi-v7a`.

## Boundary
- **In**: nothing - this is a leaf module.
- **Out**: Kotlin facade calling into Rust. Other modules depend on this one to
  invoke handshake / framing / AEAD primitives without touching JNI directly.

## Build wiring
- `cargoNdkBuild` cross-compiles the Rust crate into `src/main/jniLibs/<abi>/`.
- `uniffiBindgen` reads the compiled library and writes Kotlin bindings into
  `build/generated/uniffi/`.
- Both tasks hook into `preBuild`, so `./gradlew :app:assembleDebug` is enough.

## Usage from Kotlin
```kotlin
import uniffi.oppolink_protocol.greet

val message = greet("world") // → "Hello from Rust, world!"
```

## Notes
- JNA `@aar` artifact is required at runtime - it ships the native JNI helper
  that UniFFI's Kotlin runtime calls into.
- We use UniFFI proc-macros (no `.udl` file). Rust source is the source of
  truth; Kotlin is regenerated on every build.
