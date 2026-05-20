// Trivial entry point so `cargo run --bin uniffi-bindgen` invokes UniFFI's
// command-line binding generator. The Gradle `:core-protocol:uniffiBindgen`
// task drives it.
fn main() {
    uniffi::uniffi_bindgen_main()
}
