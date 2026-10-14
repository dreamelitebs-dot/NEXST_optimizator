package ru.booster.network

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ru.booster.network.service.GameModeService

@HiltAndroidApp
class BoosterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GameModeService.ensureChannels(this)
    }
}
