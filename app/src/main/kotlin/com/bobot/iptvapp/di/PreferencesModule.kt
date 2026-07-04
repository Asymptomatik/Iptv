package com.bobot.iptvapp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.preferences.DataStoreAppPreferencesStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Top-level extension property — creates the DataStore file "iptv_prefs.preferences_pb"
// in the app's private files directory. The delegate guarantees at most one DataStore
// instance per Context across the process lifetime, preventing data corruption from
// concurrent multi-instance writes.
private val Context.iptvPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "iptv_prefs",
)

/**
 * Hilt module that provides the shared [DataStore]<[Preferences]> singleton and
 * binds [AppPreferencesStore] to its DataStore-backed implementation.
 *
 * ## Single shared DataStore
 * One DataStore file ("iptv_prefs") is shared by all preference consumers:
 *  - [com.bobot.iptvapp.data.preferences.DataStoreCredentialsProvider] — keys prefixed "credentials_"
 *  - [com.bobot.iptvapp.data.preferences.DataStoreAppPreferencesStore] — keys prefixed "pref_"
 *
 * A single DataStore instance is simpler to manage (one file, one coroutine scope, one
 * serialisation/deserialisation path) and avoids the overhead of multiple concurrent
 * file handles. Key naming conventions enforce logical separation.
 *
 * ## Why not @Provides for AppPreferencesStore?
 * [DataStoreAppPreferencesStore] has an `@Inject constructor`, so Dagger can build it
 * automatically. Using `@Binds` is more efficient than `@Provides` here because Dagger
 * generates a direct alias rather than an intermediate factory method call.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    // ── @Binds ────────────────────────────────────────────────────────────────

    @Binds
    @Singleton
    abstract fun bindAppPreferencesStore(impl: DataStoreAppPreferencesStore): AppPreferencesStore

    companion object {

        // ── @Provides ─────────────────────────────────────────────────────────

        /**
         * Provides the single [DataStore]<[Preferences]> instance for the entire app.
         *
         * The [preferencesDataStore] extension delegate (declared above as a top-level
         * property) ensures only one DataStore is opened per [Context].
         */
        @Provides
        @Singleton
        fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            context.iptvPrefsDataStore
    }
}
