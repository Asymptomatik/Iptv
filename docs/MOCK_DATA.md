# Mock Data Layer

This document explains how the fake data source works, how to switch to a real Xtream Codes account, and where credentials are entered in the app.

---

## How the mock works

The app ships with `FakeXtreamSource`, an in-memory implementation of `CatalogDataSource` that returns a realistic, Netflix-style catalog without needing a real Xtream Codes server.

The catalog includes:

| Content type | Categories | Items                                      |
|--------------|------------|--------------------------------------------|
| Live TV      | 4          | 10 channels (with/without logos, with/without EPG) |
| Movies       | 4          | 10 movies (including one without a poster) |
| Series       | 3          | 4 series (1 to 3 seasons, varied episodes) |

EPG data is generated relative to the current wall-clock time so the "now playing" slot is always visible in the UI.

A 50 ms artificial delay is added to each call to exercise loading states and shimmer placeholders during development. Adjust or remove `SIMULATED_DELAY_MS` in `FakeXtreamSource` if needed.

---

## The `USE_MOCK_DATA` flag

The active data source is selected by a BuildConfig boolean field defined in `app/build.gradle.kts`:

```kotlin
// app/build.gradle.kts — defaultConfig block
buildConfigField("boolean", "USE_MOCK_DATA", "true")
```

| Value   | Data source         | Notes                                                  |
|---------|---------------------|--------------------------------------------------------|
| `true`  | `FakeXtreamSource`  | Default. In-memory mock. No account needed.           |
| `false` | `RemoteXtreamSource`| Live Xtream Codes API. Requires Task 8 + onboarding.  |

The Hilt module `DataSourceModule` reads this flag at compile time and provides the correct implementation throughout the app via the `CatalogDataSource` interface.

---

## How to connect a real Xtream Codes account

Follow these steps once you have a real Xtream Codes provider URL and credentials:

### Step 1 — Complete `RemoteXtreamSource` (Task 8)

`RemoteXtreamSource` (`app/src/main/kotlin/com/bobot/iptvapp/data/source/RemoteXtreamSource.kt`) is currently a stub whose methods throw `NotImplementedError`. Task 8 must implement:

1. Credential and server URL retrieval from DataStore.
2. API calls via `XtreamApiFactory.create(serverUrl)`.
3. DTO-to-domain mapping using the mappers in `data.remote.mapper`.
4. Error handling (auth failures, IO errors) translated to `CatalogException`.

### Step 2 — Flip the flag

In `app/build.gradle.kts`, change:

```kotlin
buildConfigField("boolean", "USE_MOCK_DATA", "true")
```

to:

```kotlin
buildConfigField("boolean", "USE_MOCK_DATA", "false")
```

Sync Gradle and rebuild. The DI graph now injects `RemoteXtreamSource` wherever `CatalogDataSource` is requested.

### Step 3 — Enter credentials in the app

Launch the app and navigate to the **onboarding screen** (Task 14). Enter:

- **Server URL** — the base URL of your Xtream Codes provider (e.g. `http://provider.example.com:8080`)
- **Username** — your Xtream Codes username
- **Password** — your Xtream Codes password

Credentials are stored in DataStore (Task 9) and read by `RemoteXtreamSource` at runtime. They are never hard-coded or committed to source control.

---

## Edge cases covered by the mock

The fake catalog deliberately exercises the following edge cases defined in the approved brief:

- `Channel.logoUrl = null` — Sky Sports Main Event has no logo; the UI must show a placeholder icon.
- `Channel.epgChannelId = null` — Netflix Channel has no EPG mapping; `getShortEpg` returns an empty list.
- `Movie.posterUrl = null` — Forrest Gump has no poster; the UI must show a placeholder image.
- Series with a single season (Squid Game) and up to three seasons (Game of Thrones).
- Episodes with varied durations (39 min to 66 min).

---

## Key files

| File | Role |
|------|------|
| `data/source/CatalogDataSource.kt` | Interface — contract for both mock and real source |
| `data/source/CatalogException.kt` | Sealed exception hierarchy for domain-level failures |
| `data/source/fake/FakeXtreamSource.kt` | In-memory mock implementation |
| `data/source/RemoteXtreamSource.kt` | Real network source stub (Task 8) |
| `di/DataSourceModule.kt` | Hilt module that selects the active implementation |
| `app/build.gradle.kts` | Defines `USE_MOCK_DATA` BuildConfig field |
