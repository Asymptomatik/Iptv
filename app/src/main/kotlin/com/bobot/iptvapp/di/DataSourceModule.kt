package com.bobot.iptvapp.di

import com.bobot.iptvapp.BuildConfig
import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.RemoteXtreamSource
import com.bobot.iptvapp.data.source.fake.FakeXtreamSource
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that selects the active [CatalogDataSource] implementation.
 *
 * The selection is driven by [BuildConfig.USE_MOCK_DATA] (set in `app/build.gradle.kts`):
 *
 * | Flag  | Implementation      | Use case                                            |
 * |-------|---------------------|-----------------------------------------------------|
 * | true  | [FakeXtreamSource]  | Development / demo — in-memory, no account needed  |
 * | false | [RemoteXtreamSource]| Production — requires real Xtream Codes credentials |
 *
 * ## dagger.Lazy wrapping
 * Both implementations are wrapped in [dagger.Lazy] so that Dagger constructs **only**
 * the selected implementation. Without [Lazy], Dagger would eagerly construct both
 * [FakeXtreamSource] and [RemoteXtreamSource] regardless of the flag — wasting resources
 * and potentially triggering side effects in the unselected implementation.
 *
 * ## Switching to the real source
 * 1. Ensure [RemoteXtreamSource] is fully implemented (Task 8 — done).
 * 2. Set `buildConfigField("boolean", "USE_MOCK_DATA", "false")` in `app/build.gradle.kts`.
 * 3. Configure server credentials via the onboarding screen (Task 14). Credentials are
 *    persisted by [com.bobot.iptvapp.data.preferences.DataStoreCredentialsProvider] (Task 9).
 *
 * See `docs/MOCK_DATA.md` for detailed instructions.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {

    @Provides
    @Singleton
    fun provideCatalogDataSource(
        fake: Lazy<FakeXtreamSource>,
        real: Lazy<RemoteXtreamSource>,
    ): CatalogDataSource = if (BuildConfig.USE_MOCK_DATA) fake.get() else real.get()
}
