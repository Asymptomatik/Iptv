package com.bobot.iptvapp.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the IO [CoroutineDispatcher] — suited to file, database, and network I/O.
 *
 * Usage in a constructor:
 * ```kotlin
 * class MyRepo @Inject constructor(
 *     @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
 * )
 * ```
 *
 * In unit tests, pass a [kotlinx.coroutines.test.UnconfinedTestDispatcher] or
 * [kotlinx.coroutines.test.StandardTestDispatcher] directly via constructor injection
 * rather than relying on the Hilt binding.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for the Default (CPU-bound) [CoroutineDispatcher].
 *
 * Use for CPU-intensive transformations (sorting large lists, image processing, etc.).
 * Prefer [IoDispatcher] for all I/O operations.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Qualifier for the application-scoped [CoroutineScope].
 *
 * The application scope outlives any Activity, ViewModel, or screen lifecycle.
 * Use it for long-running background coroutines that must not be cancelled when the
 * UI is destroyed — such as observing credentials changes to invalidate caches.
 *
 * Uses [SupervisorJob] so that a failing child coroutine does not cancel the scope
 * or its siblings.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt module providing injectable [CoroutineDispatcher] and [CoroutineScope] instances.
 *
 * Injecting dispatchers and scopes instead of referencing [Dispatchers.IO] or creating
 * `CoroutineScope(...)` directly allows unit tests to substitute deterministic
 * [kotlinx.coroutines.test.TestDispatcher] / [kotlinx.coroutines.test.TestScope] instances
 * without modifying production classes.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    /** Provides [Dispatchers.IO] bound to [IoDispatcher]. */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Provides [Dispatchers.Default] bound to [DefaultDispatcher]. */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provides a singleton [CoroutineScope] that lives for the entire process lifetime.
     *
     * Built on [SupervisorJob] + [Dispatchers.Default]. All child coroutines launched in
     * this scope are independent: a failure in one does not cancel the scope or siblings.
     *
     * Typical consumers:
     *  - [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl] — observes credentials
     *    to invalidate content caches.
     *  - [com.bobot.iptvapp.data.source.RemoteXtreamSource] — observes credentials to
     *    clear the per-URL Retrofit API proxy cache.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)
}
