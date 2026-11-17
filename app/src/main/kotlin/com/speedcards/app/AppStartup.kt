package com.speedcards.app

import com.speedcards.app.prefs.UserPrefs
import com.speedcards.data.io.StarterDecks
import com.speedcards.data.repo.CollectionInitializer
import com.speedcards.data.template.RenderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Runs one-time collection setup; screens await [ready] before touching data. */
@Singleton
class AppStartup @Inject constructor(
    private val initializer: CollectionInitializer,
    private val starterDecks: StarterDecks,
    private val renderSettings: RenderSettings,
    private val prefs: UserPrefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Deferred<Unit>? = null

    @Synchronized
    fun begin(): Deferred<Unit> = job ?: scope.async {
        // Keep card display options (Chinese script, tone colours) in step with settings.
        scope.launch { prefs.displaySettings.collect { renderSettings.options.value = it } }
        initializer.ensureDefaults()
        // The starter decks are required; install them (or anything new in them) before showing decks.
        runCatching { starterDecks.ensureInstalled() }
        Unit
    }.also { job = it }

    suspend fun ready() = begin().await()
}
