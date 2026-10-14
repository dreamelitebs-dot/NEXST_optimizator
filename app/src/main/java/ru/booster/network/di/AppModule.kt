package ru.booster.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.booster.network.data.local.BoosterDatabase
import ru.booster.network.data.local.GameDao
import ru.booster.network.vpn.TunnelConfigHolder
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BoosterDatabase =
        BoosterDatabase.get(context)

    @Provides
    fun provideGameDao(db: BoosterDatabase): GameDao = db.gameDao()

    /** Single shared holder — the same INSTANCE the VpnService falls back to. */
    @Provides @Singleton
    fun provideTunnelConfigHolder(): TunnelConfigHolder = TunnelConfigHolder.INSTANCE
}
