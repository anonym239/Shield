package dev.shield.ui.common

import dev.shield.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Mirrors the "Keine Benachrichtigungen" setting into [Notifications.quiet]. */
@Singleton
class QuietSync @Inject constructor(private val settings: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            settings.settings.map { it.quietMode }.distinctUntilChanged().collect { Notifications.quiet = it }
        }
    }
}
