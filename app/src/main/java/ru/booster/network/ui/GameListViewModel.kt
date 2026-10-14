package ru.booster.network.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.booster.network.core.model.GameInfo
import ru.booster.network.data.repository.GameRepository
import javax.inject.Inject

@HiltViewModel
class GameListViewModel @Inject constructor(
    application: Application,
    private val repository: GameRepository
) : AndroidViewModel(application) {

    private val _games = MutableStateFlow<List<GameInfo>>(emptyList())
    val games: StateFlow<List<GameInfo>> = _games.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { repository.observeGames().collect { _games.value = it } }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val count = repository.syncInstalledApps()
        _message.value = "Scanned installed apps · $count game-like packages indexed"
    }

    fun toggleTrack(game: GameInfo) = viewModelScope.launch {
        repository.setTracked(game.packageName, !game.isTracked)
    }

    /** Add an arbitrary package by name (custom app support). */
    fun addCustom(pkg: String) = viewModelScope.launch {
        repository.addCustomApp(pkg.trim())
            .onSuccess { _message.value = "Added ${pkg.trim()}" }
            .onFailure { _message.value = "Package not found: ${pkg.trim()}" }
    }

    fun consumeMessage() { _message.value = null }
}
