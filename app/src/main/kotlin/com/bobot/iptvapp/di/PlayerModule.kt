package com.bobot.iptvapp.di

import com.bobot.iptvapp.player.ExoPlayerManager
import com.bobot.iptvapp.player.PlayerManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding [PlayerManager] to its Media3-backed implementation.
 *
 * ## Scope
 * `@Singleton` — one [PlayerManager] (and, transitively, one lazily-created `ExoPlayer`
 * instance) shared for the entire application process lifetime. Only one screen plays
 * video at a time in this app, so a narrower scope tied to the player screen's lifecycle
 * would add Hilt custom-scope complexity without a corresponding benefit; see
 * [ExoPlayerManager] KDoc for the full rationale and its [PlayerManager.release] /
 * lazy re-creation contract.
 *
 * ## Dependencies not provided here
 * [com.bobot.iptvapp.player.IptvMediaSourceFactory] is injected directly via its
 * `@Inject constructor` (it only needs the shared `OkHttpClient` already provided by
 * [NetworkModule]) — no `@Provides` entry is needed for it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {

    @Binds
    @Singleton
    abstract fun bindPlayerManager(impl: ExoPlayerManager): PlayerManager
}
