plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.x Compose compiler plugin — replaces composeOptions.kotlinCompilerExtensionVersion
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.bobot.iptvapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bobot.iptvapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Controls which CatalogDataSource implementation Hilt injects at runtime.
        // true  → FakeXtreamSource (in-memory mock, default — no real account required)
        // false → RemoteXtreamSource (live Xtream Codes API, requires Task 8 + onboarding)
        // See docs/MOCK_DATA.md for the full switch-over guide.
        buildConfigField("boolean", "USE_MOCK_DATA", "false")

        // Export Room schemas for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    signingConfigs {
        // Explicit, portable declaration of the standard AGP-managed debug keystore.
        // These values (path, alias, passwords) are Android's own well-known, publicly
        // documented debug-keystore defaults — NOT a secret — normally applied implicitly
        // by AGP when a build type has no explicit signingConfig. Declaring it explicitly
        // here (Task 24) makes the signing setup discoverable/documented rather than
        // implicit "magic", and lets `release` deliberately reuse it below.
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // ── ⚠️ V1 SIGNING DECISION — PERSONAL / SIDE-LOAD USE ONLY ──────────
            // Play Store distribution is explicitly OUT OF SCOPE for V1 (see approved
            // brief) and this personal project has no CI pipeline or dedicated
            // production keystore. Rather than leaving `release` unsigned (which would
            // make `./gradlew assembleRelease` produce an APK nobody can install), we
            // deliberately reuse the SAME debug signing config for `release` too.
            //
            // This makes `./gradlew assembleRelease` produce a genuinely installable,
            // minified/shrunk, side-loadable APK out of the box — no manual keystore
            // generation required by the end user.
            //
            // This is NOT suitable for any real public distribution: the debug keystore
            // is a shared, non-secret, well-known key. Before any future Play Store
            // submission or public release, this MUST be replaced with a real,
            // privately-held production keystore (`keytool -genkeypair ...`) and a
            // proper signing config (ideally sourced from a local `keystore.properties`
            // file or CI secrets, never committed to source control).
            // See ADR-006 for the full rationale and alternatives considered.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.tv.foundation.ExperimentalTvFoundationApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}

dependencies {
    // ── Compose BOM (governs all androidx.compose.* versions) ────────────
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI + Material3
    implementation(libs.bundles.compose.core)
    implementation(libs.activity.compose)

    // Compose tooling (debug only)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // ── Navigation ───────────────────────────────────────────────────────
    implementation(libs.navigation.compose)

    // ── Lifecycle / ViewModel ────────────────────────────────────────────
    implementation(libs.bundles.lifecycle)

    // ── Compose for TV ───────────────────────────────────────────────────
    implementation(libs.bundles.tv)

    // ── Hilt ─────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── Media3 / ExoPlayer + FFmpeg ──────────────────────────────────────
    implementation(libs.bundles.media3)
    // FFmpeg decoder extension (media3-exoplayer-ffmpeg) is DELIBERATELY NOT added here.
    // Task 12 verified it is not published on Maven Central / Google Maven for any
    // media3 version — it must be built locally from the media3 source tree
    // (libraries/decoder_ffmpeg + build_ffmpeg.sh) and published to mavenLocal(), or
    // included as a composite/local Gradle module. See gradle/libs.versions.toml
    // (Media3 section) and ADR-004 for the full investigation and rationale, and
    // com.bobot.iptvapp.player.ExoPlayerManager for how DefaultRenderersFactory is
    // already configured (EXTENSION_RENDERER_MODE_ON) so the extension is picked up
    // automatically the moment it is added to the classpath — no code change needed.

    // ── Image loading ────────────────────────────────────────────────────
    implementation(libs.coil.compose)

    // ── Room ─────────────────────────────────────────────────────────────
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    // ── DataStore ────────────────────────────────────────────────────────
    implementation(libs.datastore.preferences)

    // ── Network ──────────────────────────────────────────────────────────
    implementation(libs.bundles.network)

    // ── Coroutines ───────────────────────────────────────────────────────
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // ── Unit tests ───────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)

    // ── Instrumented tests ───────────────────────────────────────────────
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.turbine)
    // Room migration testing (MigrationTestHelper) — no migrations exist yet (schema
    // version = 1), but the dependency is added ahead of time for Lot 2 follow-up work.
    androidTestImplementation(libs.room.testing)
}
