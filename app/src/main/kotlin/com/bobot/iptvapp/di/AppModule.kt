package com.bobot.iptvapp.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Application-level Hilt module.
 *
 * This module is installed in [SingletonComponent], meaning every binding
 * declared here lives for the entire application lifetime (singleton scope).
 *
 * Intended bindings (added by later tasks):
 *  - Task 11 — local repository bindings (Room-backed)
 *
 * No bindings are provided here yet. The annotation scaffolding is in place
 * so that future @Provides / @Binds methods can be added without structural
 * changes to this file.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
