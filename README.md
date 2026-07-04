# IptvApp

Native Android IPTV client for Xtream Codes providers, built with **Kotlin + Jetpack Compose + Compose for TV**. Single codebase, dual form factor: runs as a phone/tablet app (touch, launcher icon) and as an Android TV app (D-pad navigation, Leanback launcher row) at the same time.

Netflix-style dark UI: Home screen with "Continue Watching", "My List", Live TV, Movies and Series rows, full catalog browsing, EPG (electronic program guide), a Media3/ExoPlayer-based player, search, favorites, and multi-profile support. Everything is **100% local** — no backend, no Firebase, no cloud account, no analytics. All data (profiles, favorites, watch progress, Xtream credentials) is persisted on-device only (Room + DataStore).

This is a V1 delivery. Play Store distribution, DRM/Widevine, parental controls, and PC/iOS ports are explicitly out of scope (see [Known limitations](#known-limitations-v1) below).

---

## Development status — read this first

This entire codebase (24 tasks, each independently implemented and code-reviewed) was built by an AI-orchestrated development pipeline running in an environment **without any JDK, Android SDK, Gradle, emulator, or physical device available**. Every task was implemented and verified by careful manual code review only — no compile, no unit test run, no instrumentation test run, and no install/launch was ever actually performed during development.

**This means the build instructions below have never been executed.** This README describes the first real compile-and-test opportunity this project will get. If you are the first person running `./gradlew` against this repository, please treat the very first build as a verification pass, not an assumed-working artifact, and report anything that doesn't match what's documented here.

---

## Prerequisites

- **JDK 17** (matches `sourceCompatibility` / `targetCompatibility` / `jvmTarget` set in `app/build.gradle.kts`)
- **Android Studio** (recommended — any recent version compatible with AGP/Kotlin 2.x and Compose) or a standalone install of the Android SDK + command-line tools if you prefer to use the Gradle wrapper directly
- **Android SDK** with:
  - `compileSdk = 35`
  - `minSdk = 24`
  - `targetSdk = 35`
  (exact values confirmed in `app/build.gradle.kts`)
- An internet connection for the first Gradle sync (dependency resolution from Google's Maven repo and Maven Central)

No physical device or emulator is required to *build* the app, but you will need one (phone/tablet and/or Android TV device or TV emulator) to actually *install and run* it — see [Installation](#installation) below.

---

## Building

From the repository root (`C:\Users\bobot\Documents\Iptv`):

```bash
# Debug build (debuggable, applicationId suffixed with .debug, signed with the
# standard AGP debug keystore)
./gradlew assembleDebug

# Release build (minified + resource-shrunk via R8, signed — see "Release signing" below)
./gradlew assembleRelease
```

On Windows, use `gradlew.bat` instead of `./gradlew` if you are not in a POSIX-style shell (e.g. plain `cmd.exe` or PowerShell without a Unix-like wrapper):

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

### Output APK locations

Given the build type names and `applicationIdSuffix` configured in `app/build.gradle.kts` (no product flavors are defined), Android Gradle Plugin produces:

| Build type | Output path |
|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

### Release signing — important read

`app/build.gradle.kts` declares an explicit `signingConfigs { debug { ... } }` block pointing at the standard, AGP-managed debug keystore (`~/.android/debug.keystore`, alias `androiddebugkey`, password `android` — Android's own well-known, publicly documented debug-keystore defaults, not a secret). This makes explicit what AGP already does implicitly when no signing config is set.

**The `release` build type deliberately reuses this same debug signing config.** This is a conscious, documented V1 trade-off, not an oversight:

- Play Store distribution is explicitly out of scope for V1 (see brief).
- No production keystore or CI signing pipeline exists for this personal project.
- No `keytool`/JDK was available in the automated development environment to generate one.
- Leaving `release` unsigned would produce a non-installable APK — a broken deliverable.

The result: `./gradlew assembleRelease` produces a genuinely installable, R8-minified/shrunk APK out of the box, suitable for **personal side-loading only**. It is **not** suitable for Play Store submission or any public distribution — see `ADR-006` in the project's Second Brain vault (`30 - Projets/IptvApp/ADR/ADR-006 — Signature release avec le keystore debug (V1, usage personnel).md`) for the full rationale, and the inline comment directly above the `release` block in `app/build.gradle.kts`.

Before any future Play Store submission or public release, replace the `release` signing config with a real, privately-held production keystore (`keytool -genkeypair ...`), kept out of source control (e.g. via a local `keystore.properties` file or CI secrets — the `.gitignore` already anticipates this with `release.jks` / `release.keystore` entries).

---

## Running the test suite

```bash
./gradlew test
```

This runs the full JVM unit test suite accumulated across Tasks 1–23 (ViewModels, mappers, repositories, use cases, DataStore/Room layers, etc., using JUnit, MockK, Turbine, and `kotlinx-coroutines-test`).

**Be aware**: because no JDK/Gradle was available during automated development, `./gradlew test` has never actually been executed against this codebase. This will be the first real compile-and-test run. It is strongly recommended you run it before trusting the build, and before running the (smaller) instrumentation test suite:

```bash
./gradlew connectedAndroidTest
```

(requires a connected device or running emulator).

---

## Installation

### Phone / tablet

Two options:

**Option A — sideload via file transfer**
1. Copy the APK (`app-debug.apk` or `app-release.apk`) to the device (USB, cloud drive, email, etc.).
2. On the device, enable **"Install from unknown sources"** for the app you use to open the APK (Settings → Apps → Special access → Install unknown apps), if not already enabled.
3. Open the APK file on the device and confirm installation.

**Option B — install via adb (recommended if you have the SDK platform-tools installed)**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# or
adb install -r app/build/outputs/apk/release/app-release.apk
```
(`-r` allows reinstalling over an existing install of the same package.)

**Success signals**: the app icon appears on the phone/tablet home screen or app drawer (this is enabled by the `LAUNCHER` intent-filter on `MainActivity` in `AndroidManifest.xml`), and the app launches without crashing.

### Android TV

Most Android TV devices have no touchscreen and no file browser, so **Wi-Fi ADB sideload** is the standard installation path:

1. On the Android TV device, enable Developer Options (Settings → Device Preferences → About → click "Build" 7 times) if not already enabled.
2. In Developer Options, enable **"USB debugging"** and **"Network debugging"** (naming varies slightly by TV OEM/Android version, e.g. "ADB debugging" / "Wireless debugging").
3. Find the TV's IP address (Settings → Network & Internet, or Device Preferences → About → Status).
4. From a computer on the same network, with `adb` (Android SDK platform-tools) installed:
   ```bash
   adb connect <tv-ip-address>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   If `adb connect` fails, double-check the TV and computer are on the same network/subnet, and that network/wireless debugging is actually enabled on the TV (some OEMs require re-enabling it after each TV reboot).
5. Optional sanity check before installing:
   ```bash
   adb devices
   ```
   should list the TV's IP:port as a connected device.

**Success signals**: the app appears as a row/tile in the Android TV home screen's **Leanback launcher** (this is enabled by the `LEANBACK_LAUNCHER` intent-filter on `MainActivity`, combined with the `android.software.leanback` `uses-feature` declared with `required="false"` in `AndroidManifest.xml`), and the app launches and is navigable with the TV remote's D-pad without crashing.

### Why the same APK installs on both form factors

`app/src/main/AndroidManifest.xml` declares:
- Both `android.software.leanback` and `android.hardware.touchscreen` as `uses-feature` with `required="false"` — this tells the Play Store / package installer that the app does not strictly require either a touchscreen or Leanback/TV features, so a single APK is installable on both device types.
- Two intent-filters on `MainActivity`: one with `category.LAUNCHER` (phone/tablet home screen) and one with `category.LEANBACK_LAUNCHER` (Android TV home screen).

No manifest changes were needed for Task 24 — this configuration was already correct as of earlier tasks.

---

## Connecting a real Xtream Codes account (switching off the mock data)

By default, the app ships with `FakeXtreamSource`, an in-memory mock catalog (`USE_MOCK_DATA = true` in `app/build.gradle.kts`) so it is fully usable and demoable without any real IPTV subscription.

To connect a real Xtream Codes provider account, flip the `USE_MOCK_DATA` build config flag to `false` and enter your server URL, username, and password in the app's onboarding screen. Credentials are stored locally via DataStore and are never hard-coded or transmitted anywhere except to your own Xtream server.

Full details — how the mock catalog is structured, exactly which file/flag to change, and step-by-step onboarding instructions — are documented in **[`docs/MOCK_DATA.md`](docs/MOCK_DATA.md)**. Read that file before attempting to connect a real account.

---

## Known limitations (V1)

Per the approved brief, the following are explicitly out of scope for this V1 delivery:

- **No Play Store distribution.** The app is designed to be installable via a single dual-form-factor APK, but is not published or submission-ready (see [Release signing](#release-signing--important-read) above).
- **No DRM / Widevine support.** Only unencrypted streams supported by Media3/ExoPlayer (HLS, MPEG-TS, MP4, and VLC-like codecs where the FFmpeg extension is available — see `ADR-004`) will play.
- **No parental controls.** Any profile can access any content; there is no PIN-lock or content-rating filter in V1.
- **No cloud sync / no Firebase.** Profiles, favorites, and watch progress are local to each device only; there is no cross-device sync. Reinstalling the app or switching devices loses this local state.
- **No PC / iOS / Flutter port.** This is an Android-only native codebase (Kotlin + Compose), not a cross-platform framework.
- **Credentials stored in plaintext locally.** The Xtream Codes password is stored unencrypted in DataStore Preferences, protected only by Android's per-app sandboxing (not by app-level encryption). This is a deliberate, documented V1 trade-off — see `ADR-003` ("Persistance locale Room + DataStore (V1, credentials en clair)") for the full rationale, and `ADR-005` for the related cleartext-HTTP and backup-exclusion decisions (`android:usesCleartextTraffic="true"`, `android:allowBackup="false"`).

---

## Project structure at a glance

- Single Gradle module: `:app` (see `settings.gradle.kts`, project name `IptvApp`).
- `app/build.gradle.kts` — build configuration, dependency versions resolved via `gradle/libs.versions.toml`.
- `app/proguard-rules.pro` — R8 rules for the release build (kotlinx.serialization DTOs/routes, Retrofit; Room/Hilt/Media3/OkHttp rely on their own bundled consumer rules).
- `app/src/main/kotlin/com/bobot/iptvapp/` — application source, organized by layer (`data/`, `di/`, `navigation/`, `player/`, `ui/`, etc.).
- `docs/MOCK_DATA.md` — mock-to-real Xtream Codes switch-over guide.
