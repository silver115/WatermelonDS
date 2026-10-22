package me.magnum.melonds.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.magnum.melonds.domain.model.appupdate.AppUpdate
import me.magnum.melonds.domain.repositories.UpdatesRepository
import javax.inject.Singleton

/**
 * Pokéwalker builds are published by the fork's own "pokewalker-latest" release,
 * so the upstream update check (which would offer a different app) is turned off.
 */
@Module
@InstallIn(SingletonComponent::class)
object GitHubPokewalkerModule {

    @Provides
    @Singleton
    fun provideUpdatesRepository(): UpdatesRepository = object : UpdatesRepository {
        override suspend fun checkNewUpdate(): Result<AppUpdate?> = Result.success(null)
        override fun skipUpdate(update: AppUpdate) {}
        override fun notifyUpdateDownloaded(update: AppUpdate) {}
    }
}
