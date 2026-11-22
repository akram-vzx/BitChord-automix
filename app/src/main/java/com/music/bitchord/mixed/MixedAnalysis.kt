package com.music.bitchord.mixed

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.SharedTrackAnalyzer
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * How hard the analyser is allowed to work right now.
 *
 * Songs added to a mixed playlist are measured one at a time at the lowest
 * effort, so adding a hundred does not make the phone hot or the app stutter.
 * Opening the mix editor or playing a mixed playlist takes a boost, which lifts
 * it to full effort until the boost is given back. With neither, the listener's
 * own Automix performance setting applies as it always did.
 */
object AutomixEffort {
    @Volatile private var background = false
    private val boosts = AtomicInteger(0)

    fun effective(): AutomixPerformanceMode = when {
        boosts.get() > 0 -> AutomixPerformanceMode.PERFORMANCE
        background -> AutomixPerformanceMode.EFFICIENT
        else -> AppSettings.automixPerformanceMode.value
    }

    internal fun setBackground(on: Boolean) {
        background = on
    }

    /** Raises the effort until the returned function is called. Safe to call it twice. */
    fun boost(): () -> Unit {
        boosts.incrementAndGet()
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) boosts.decrementAndGet() }
    }
}

/** What the playlist page shows for a song once it has been measured. */
data class SongTempo(val bpm: Double, val key: CamelotKey?, val keyName: String)

/**
 * Measures the songs of mixed playlists, patiently, and remembers the answers.
 *
 * The measuring itself is the existing [com.music.bitchord.playback.smart.TrackAnalyzer],
 * which already stores each result on disk by song id — so a song moved to
 * another place in a playlist, removed and re-added, or put in a second
 * playlist is never measured twice. This object only decides the *order* and
 * the *effort*, and keeps a memory copy so a row can be drawn without touching
 * the disk.
 */
object MixedAnalysis {
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { AnalysisStore { appContext.filesDir } }
    private val memory = ConcurrentHashMap<String, TrackAnalysis>()
    private val gaveUp = ConcurrentHashMap.newKeySet<String>()

    private val lock = Any()
    private val queue = LinkedHashMap<String, Song>()
    private var worker: Job? = null

    private val _revision = MutableStateFlow(0)

    /** Bumps whenever a song's measurements land, so pages know to look again. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _working = MutableStateFlow<Set<String>>(emptySet())

    /** Song ids being measured or waiting to be. */
    val working: StateFlow<Set<String>> = _working.asStateFlow()

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    /** From memory only — never the disk — so it is safe to call while drawing. */
    fun analysisOf(videoId: String): TrackAnalysis? = memory[videoId]?.takeIf { it.isUsable }

    fun tempoOf(song: Song): SongTempo? {
        val analysis = analysisOf(song.videoId) ?: return null
        return SongTempo(analysis.bpm, Camelot.parse(analysis.key), analysis.key)
    }

    /** Reads whatever is already stored for [songs] into memory. Call off the main thread. */
    suspend fun warm(songs: List<Song>) = withContext(Dispatchers.IO) {
        var changed = false
        songs.forEach { song ->
            if (memory.containsKey(song.videoId)) return@forEach
            val stored = runCatching { store.load(song.videoId) }.getOrNull()
            if (stored != null && stored.isUsable) {
                memory[song.videoId] = stored
                changed = true
            }
        }
        if (changed) _revision.value = _revision.value + 1
    }

    /** True when [videoId] has usable measurements, in memory or stored. Off the main thread. */
    suspend fun isMeasured(song: Song): Boolean {
        if (memory[song.videoId]?.isUsable == true) return true
        warm(listOf(song))
        return memory[song.videoId]?.isUsable == true
    }

    /** Queues [songs] behind whatever is already waiting. Songs not on the device yet are ignored. */
    fun enqueue(songs: List<Song>) {
        add(songs, atFront = false)
    }

    /**
     * Moves [songs] to the head of the line — the editor or the player needs
     * them now — and asks for them again if they had been given up on.
     */
    fun prioritise(songs: List<Song>) {
        songs.forEach { gaveUp.remove(it.videoId) }
        add(songs, atFront = true)
    }

    private fun add(songs: List<Song>, atFront: Boolean) {
        if (!this::appContext.isInitialized) return
        val wanted = songs.filter { it.localUri != null && memory[it.videoId]?.isUsable != true }
        if (wanted.isEmpty()) return
        synchronized(lock) {
            if (atFront) {
                val rest = LinkedHashMap(queue)
                queue.clear()
                wanted.forEach { queue[it.videoId] = it }
                rest.forEach { (id, song) -> if (id !in queue) queue[id] = song }
            } else {
                wanted.forEach { if (it.videoId !in queue) queue[it.videoId] = it }
            }
            _working.value = _working.value + wanted.map { it.videoId }
            if (worker?.isActive != true) worker = scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    val first = queue.entries.firstOrNull() ?: return@synchronized null
                    queue.remove(first.key)
                    first.value
                } ?: break
                try {
                    measure(next)
                } finally {
                    _working.value = _working.value - next.videoId
                }
            }
        } finally {
            AutomixEffort.setBackground(false)
        }
    }

    private suspend fun measure(song: Song) {
        val id = song.videoId
        if (id in gaveUp) return
        if (memory[id]?.isUsable == true) return
        warm(listOf(song))
        if (memory[id]?.isUsable == true) return
        val localUri = song.localUri ?: return

        AutomixEffort.setBackground(true)
        val analyzer = SharedTrackAnalyzer.get(appContext)
        val duration = durationSeconds(song)
        analyzer.request(id, Uri.parse(localUri), duration)

        val began = SystemClock.elapsedRealtime()
        var seenRunning = false
        while (SystemClock.elapsedRealtime() - began < MAX_WAIT_MS) {
            delay(POLL_MS)
            val running = analyzer.isAnalysing(id)
            if (running) seenRunning = true
            if (!running && analyzer.isAnalysed(id)) break
            if (!running && !seenRunning && SystemClock.elapsedRealtime() - began > START_GRACE_MS) break
        }
        val result = analyzer.analysisFor(id)
        if (result.isUsable) {
            memory[id] = result
            _revision.value = _revision.value + 1
        } else {
            gaveUp.add(id)
        }
    }

    /** The length the song's row states, 0 when it states none. Never touches the disk, so it is safe while drawing. */
    fun listedSeconds(song: Song): Double = parseClock(song.durationText) ?: 0.0

    /** Seconds, from the song's own duration text when it has one, else from the file. */
    fun durationSeconds(song: Song): Double {
        parseClock(song.durationText)?.let { return it }
        val uri = song.localUri ?: return 0.0
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, Uri.parse(uri))
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            } finally {
                retriever.release()
            }
        }.getOrDefault(0.0)
    }

    private fun parseClock(text: String?): Double? {
        val parts = text?.trim()?.split(":")?.map { it.toIntOrNull() ?: return null } ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0) { total, part -> total * 60 + part }.toDouble().takeIf { it > 0 }
    }

    private const val POLL_MS = 400L
    private const val START_GRACE_MS = 8_000L
    private const val MAX_WAIT_MS = 12 * 60_000L
}
