package com.bobot.iptvapp.di

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NetworkModule]'s two [okhttp3.OkHttpClient] flavours.
 *
 * The invariant under test is not stylistic: at [HttpLoggingInterceptor.Level.BODY] the logging
 * interceptor buffers the *entire* response body before logging it. On an API call that is a few
 * kilobytes of JSON; on `movie/<user>/<pass>/<id>.mp4` it is the whole film, which killed the
 * process with `OutOfMemoryError` as soon as any VOD item was played in a debug build. Media
 * traffic must therefore never go through a client carrying that interceptor.
 *
 * These tests exercise the module object directly rather than a Hilt test component: the
 * providers are plain functions with no Android dependencies, so a Hilt harness would add setup
 * without testing anything more.
 */
class NetworkModuleTest {

    @Test
    fun `the streaming client carries no body-logging interceptor`() {
        val streaming = NetworkModule.provideStreamingOkHttpClient(NetworkModule.provideOkHttpClient())

        assertFalse(
            "A HttpLoggingInterceptor on the media client buffers whole films into the heap",
            streaming.interceptors.any { it is HttpLoggingInterceptor },
        )
        assertFalse(
            streaming.networkInterceptors.any { it is HttpLoggingInterceptor },
        )
    }

    @Test
    fun `the streaming client shares the API client's connection pool and dispatcher`() {
        // Derived with newBuilder() rather than built from scratch, so the two clients keep one
        // HTTP stack between them. If this ever regresses to a fresh OkHttpClient.Builder(), the
        // app quietly doubles its sockets and threads.
        val api = NetworkModule.provideOkHttpClient()
        val streaming = NetworkModule.provideStreamingOkHttpClient(api)

        assertSame(api.connectionPool, streaming.connectionPool)
        assertSame(api.dispatcher, streaming.dispatcher)
    }

    @Test
    fun `the streaming client keeps the API client's timeouts`() {
        val api = NetworkModule.provideOkHttpClient()
        val streaming = NetworkModule.provideStreamingOkHttpClient(api)

        assertTrue(streaming.connectTimeoutMillis == api.connectTimeoutMillis)
        assertTrue(streaming.readTimeoutMillis == api.readTimeoutMillis)
        assertTrue(streaming.writeTimeoutMillis == api.writeTimeoutMillis)
    }
}
