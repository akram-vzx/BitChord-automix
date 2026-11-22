package com.music.bitchord.mixed

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "No mix" → "Mixing" → "Auto" step.
 *
 * Runs on its own scope, so leaving the page does not abandon a join half
 * built. Both songs are moved to the front of the analyser's line first — the
 * listener asked for this one — and the join is only written if the two songs
 * still sit next to each other when it is done and nobody has made one by hand
 * in the meantime.
 */
object MixedBuilds {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _inProgress = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the joins being built, as [key] makes them. */
    val inProgress: StateFlow<Set<String>> = _inProgress.asStateFlow()

    private val _problems = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** A line for the listener when a join could not be built. */
    val problems: SharedFlow<String> = _problems.asSharedFlow()

    fun key(playlistId: String, a: Song, b: Song) = "$playlistId|${MixedPlaylists.pairKey(a, b)}"

    fun start(playlistId: String, a: Song, b: Song) {
        val key = key(playlistId, a, b)
        if (key in _inProgress.value) return
        _inProgress.value = _inProgress.value + key
        scope.launch {
            try {
                if (a.localUri == null || b.localUri == null) {
                    _problems.tryEmit("Both songs have to finish downloading first.")
                    return@launch
                }
                MixedAnalysis.prioritise(listOf(a, b))
                val release = AutomixEffort.boost()
                val measured = try {
                    awaitMeasured(a, b)
                } finally {
                    release()
                }
                if (!measured) {
                    _problems.tryEmit("Couldn't measure “${if (MixedAnalysis.analysisOf(a.videoId) == null) a.title else b.title}”.")
                    return@launch
                }
                val mix = MixedBuilder.build(a, b)
                if (mix == null) {
                    _problems.tryEmit("Couldn't build a mix for these two songs.")
                    return@launch
                }
                val playlist = MixedPlaylists.get(playlistId) ?: return@launch
                val stillNeighbours = playlist.songs.zipWithNext().any { it.first.videoId == a.videoId && it.second.videoId == b.videoId }
                if (!stillNeighbours) return@launch
                if (playlist.transitionBetween(a, b).state == MixState.MANUAL) return@launch
                MixedPlaylists.setTransition(playlistId, a, b, MixTransition(MixState.AUTO, mix))
            } finally {
                _inProgress.value = _inProgress.value - key
            }
        }
    }

    private suspend fun awaitMeasured(a: Song, b: Song): Boolean {
        var waited = 0L
        while (waited < MAX_WAIT_MS) {
            val both = MixedAnalysis.isMeasured(a) && MixedAnalysis.isMeasured(b)
            if (both) return true
            val working = MixedAnalysis.working.value
            val someoneWorking = a.videoId in working || b.videoId in working
            if (!someoneWorking && waited > GRACE_MS) return false
            delay(POLL_MS)
            waited += POLL_MS
        }
        return false
    }

    private const val POLL_MS = 500L
    private const val GRACE_MS = 3_000L
    private const val MAX_WAIT_MS = 15 * 60_000L
}
