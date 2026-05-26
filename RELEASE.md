# Release process

OppoLink ships signed APKs to GitHub Releases on every `v*` tag and a
matching F-Droid build metadata file at `metadata/link.oppolink.yml`.

## Versioning

[Semantic Versioning](https://semver.org/) for the application:

- `MAJOR.MINOR.PATCH` (e.g. `0.1.0`, `1.2.3`)
- `MAJOR` bumps on a `PROTOCOL_VERSION` change that breaks wire compat.
  Sprint 4 D13 took us from wire-v1 to wire-v2 mid-`0.x` because we are
  pre-1.0; once we cut `1.0.0` the SemVer guarantee tightens.
- `MINOR` bumps on a new user-visible feature.
- `PATCH` bumps on bug-fix-only releases.

`versionCode` is monotonic; F-Droid requires a strict increase on every
release. The convention is `MAJOR * 10_000 + MINOR * 100 + PATCH`, so
`0.1.0` → `100`, `1.2.3` → `10_203`. Currently we ship `versionCode = 1`
for the initial `0.1.0` since we're pre-launch.

## Local signed build

One-time keystore generation (kept offline, **never** committed):

```bash
keytool -genkey -v \
  -keystore oppolink-release.jks \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -alias oppolink
```

Then copy `signing.properties.example` to `signing.properties` and fill
in the values. The Gradle script at `android/app/build.gradle.kts` reads
that file at config time.

```bash
cd android
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

If `signing.properties` is absent the release build falls back to the
debug signing config (so CI smoke builds still produce an APK).

## CI release flow

1. Bump `versionName` + `versionCode` in
   `android/app/build.gradle.kts`.
2. Append a `## vX.Y.Z` section to `CHANGELOG.md`.
3. Tag locally + push:
   ```bash
   git tag -a vX.Y.Z -m "vX.Y.Z"
   git push origin vX.Y.Z
   ```
4. `.github/workflows/release.yml` picks up the tag, decodes the
   keystore from `RELEASE_KEYSTORE_B64`, runs `assembleRelease` with
   the env-var-backed signing config, uploads the APK as an Actions
   artifact, and `gh release create`s a release.

### Required GitHub secrets (org-level on `wienerlabs`)

| Name | Contents |
| --- | --- |
| `RELEASE_KEYSTORE_B64` | `base64 oppolink-release.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | usually `oppolink` |
| `RELEASE_KEY_PASSWORD` | key password |

These are organization secrets so the `wienerlabs` Actions runner can
read them; `kh0ra`-owned forks of the repo will not see them.

## F-Droid submission

The `metadata/link.oppolink.yml` file is the canonical record of how
F-Droid should build OppoLink. To get it into the official catalogue:

1. Fork [`fdroiddata`](https://gitlab.com/fdroid/fdroiddata).
2. Copy `metadata/link.oppolink.yml` into `metadata/` in the fork.
3. Run `fdroid lint link.oppolink` then `fdroid build link.oppolink:0.1.0`
   locally to verify.
4. Open a merge request against `fdroiddata`.
5. F-Droid's CI rebuilds the APK from source on its own infrastructure -
   our GitHub-signed APK is **not** what users get from F-Droid; F-Droid
   re-signs with its own keys.

## Play Store

Deferred pending trademark counsel - see DISCLAIMER.md. The package
namespace `link.oppolink` is trademark-safe but the human-readable
"OppoLink" name needs a once-over before listing.

## Sprint 4 D14 deferral

Battery profiling (`<5 %/hour active call on Reno 11`) is a hardware
test, not a release artifact. The goal lands in this version's "known
limitations" until measured on Tier 1 hardware. Once we have a number
it goes into the release notes.
