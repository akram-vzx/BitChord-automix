package com.music.bitchord.mixed

import android.content.Context
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A playlist whose songs are all on the device and whose joins are mixed.
 *
 * A song with no [Song.localUri] has been added but not downloaded yet; it is
 * kept in its place so the playlist reads as the listener built it, and it
 * becomes playable the moment its file lands.
 *
 * [transitions] is keyed by [MixedPlaylists.pairKey] — "this song, then that
 * one" — so a measured or hand-made join belongs to the pair and not to a slot:
 * measuring happens once per song, and a song dragged somewhere else keeps no
 * join with its old neighbours (see [MixedPlaylists.pruned]).
 */
data class MixedPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song> = emptyList(),
    val transitions: Map<String, MixTransition> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val browseId: String get() = MixedPlaylists.PREFIX + id
    val ready: List<Song> get() = songs.filter { it.localUri != null }
    val coverUrl: String? get() = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl

    fun transitionBetween(a: Song, b: Song): MixTransition =
        transitions[MixedPlaylists.pairKey(a, b)] ?: MixTransition.NONE
}

object MixedPlaylists {
    const val PREFIX = "local:mixed:"
    private const val FILE = "bitchord_mixed_playlists"
    private const val KEY = "json"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val _playlists = MutableStateFlow<List<MixedPlaylist>>(emptyList())
    val playlists: StateFlow<List<MixedPlaylist>> = _playlists.asStateFlow()

    fun isMixed(browseId: String?): Boolean = browseId?.startsWith(PREFIX) == true
    fun idOf(browseId: String): String = browseId.removePrefix(PREFIX)
    fun pairKey(a: Song, b: Song): String = "${a.videoId}>${b.videoId}"

    fun get(browseIdOrId: String?): MixedPlaylist? {
        val id = browseIdOrId?.removePrefix(PREFIX) ?: return null
        return _playlists.value.firstOrNull { it.id == id }
    }

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        MixedAnalysis.init(appContext)
        _playlists.value = read(appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
        // Everything not on the device yet — a song just added, a playlist just
        // restored from a backup — is asked for, and finished off as it lands.
        scope.launch {
            Downloads.saved.collect { resolvePending() }
        }
        scope.launch { requestMissing() }
    }

    // ---- playlists -------------------------------------------------------------

    fun create(title: String): MixedPlaylist {
        val playlist = MixedPlaylist(id = UUID.randomUUID().toString(), title = title.trim().ifEmpty { "Mixed playlist" })
        mutate { it + playlist }
        return playlist
    }

    fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim().ifEmpty { it.title }) }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /**
     * Adds [songs] to the end of playlist [id]. Songs already downloaded go in
     * ready; the rest are downloaded first and join the playlist's ready songs
     * when they land. A song already in the playlist is not added twice.
     */
    fun addSongs(id: String, songs: List<Song>) {
        val playlist = get(id) ?: return
        val have = playlist.songs.map { it.videoId }.toSet()
        val fresh = songs.distinctBy { it.videoId }.filter { it.videoId !in have }
        if (fresh.isEmpty()) return
        val prepared = fresh.map { song ->
            if (song.localUri != null) song
            else {
                val saved = Downloads.verifiedSavedUri(song.videoId)
                if (saved != null) song.copy(localUri = saved) else song
            }
        }
        update(id) { it.copy(songs = it.songs + prepared) }
        prepared.filter { it.localUri == null }.forEach { Downloads.enqueue(appContext, it) }
        MixedAnalysis.enqueue(prepared.filter { it.localUri != null })
    }

    fun removeSong(id: String, index: Int) = update(id) { playlist ->
        if (index !in playlist.songs.indices) playlist
        else pruned(playlist.copy(songs = playlist.songs.filterIndexed { i, _ -> i != index }))
    }

    /** Moves one song. Any join that no longer connects two neighbours is dropped with it. */
    fun move(id: String, from: Int, to: Int) = update(id) { playlist ->
        if (from !in playlist.songs.indices || to !in playlist.songs.indices || from == to) playlist
        else {
            val list = playlist.songs.toMutableList()
            list.add(to, list.removeAt(from))
            pruned(playlist.copy(songs = list))
        }
    }

    fun setTransition(id: String, from: Song, to: Song, transition: MixTransition) = update(id) { playlist ->
        val key = pairKey(from, to)
        val next = playlist.transitions.toMutableMap()
        // MIXING is shown but never stored; "no mix" is the absence of an entry.
        if (transition.state == MixState.NONE) next.remove(key) else next[key] = transition
        playlist.copy(transitions = next)
    }

    /**
     * Re-orders by closeness of tempo and key. Returns the new order without
     * applying it — see [applyOrder] — so the page can first say how many
     * hand-made joins that costs.
     */
    suspend fun orderByMixing(id: String): List<Song>? {
        val playlist = get(id) ?: return null
        val songs = playlist.songs
        if (songs.size < 3) return songs
        MixedAnalysis.warm(songs)
        val nodes = songs.map { song ->
            val tempo = MixedAnalysis.tempoOf(song)
            MixOrdering.Node(tempo?.bpm ?: 0.0, tempo?.key)
        }
        val order = MixOrdering.order(nodes)
        return order.map { songs[it] }
    }

    /** Joins that would be lost — hand-made ones whose two songs would stop being neighbours. */
    fun manualLostBy(id: String, newOrder: List<Song>): Int {
        val playlist = get(id) ?: return 0
        val keep = adjacentKeys(newOrder)
        return playlist.transitions.count { (key, t) -> t.state == MixState.MANUAL && key !in keep }
    }

    fun applyOrder(id: String, newOrder: List<Song>) = update(id) { playlist ->
        pruned(playlist.copy(songs = newOrder))
    }

    private fun adjacentKeys(songs: List<Song>): Set<String> =
        songs.zipWithNext { a, b -> pairKey(a, b) }.toSet()

    /** Drops every stored join whose two songs are no longer neighbours, in that order. */
    internal fun pruned(playlist: MixedPlaylist): MixedPlaylist {
        val keep = adjacentKeys(playlist.songs)
        val kept = playlist.transitions.filterKeys { it in keep }
        return if (kept.size == playlist.transitions.size) playlist else playlist.copy(transitions = kept)
    }

    // ---- downloads -------------------------------------------------------------

    /** Gives a downloaded file to every pending song that was waiting for it. */
    private fun resolvePending() {
        val ready = ArrayList<Song>()
        mutate(persist = true) { lists ->
            lists.map { playlist ->
                if (playlist.songs.none { it.localUri == null }) playlist
                else playlist.copy(
                    songs = playlist.songs.map { song ->
                        if (song.localUri != null) song
                        else Downloads.verifiedSavedUri(song.videoId)?.let { uri ->
                            song.copy(localUri = uri).also { ready += it }
                        } ?: song
                    },
                )
            }
        }
        if (ready.isNotEmpty()) MixedAnalysis.enqueue(ready)
    }

    /**
     * Songs whose file is gone — never downloaded here, or a backup restored on
     * a phone that does not have them — are asked for again, and what is left
     * is measured, one song at a time.
     */
    private fun requestMissing() {
        val songs = _playlists.value.flatMap { it.songs }.distinctBy { it.videoId }
        val missing = songs.filter { song -> song.localUri?.let { Downloads.isMissingLocalFile(it) } ?: true }
        if (missing.isNotEmpty()) {
            mutate { lists ->
                lists.map { playlist ->
                    playlist.copy(songs = playlist.songs.map { s -> if (missing.any { it.videoId == s.videoId }) s.copy(localUri = null) else s })
                }
            }
            missing.forEach { Downloads.enqueue(appContext, it.copy(localUri = null)) }
        }
        MixedAnalysis.enqueue(songs.filter { it.localUri != null && missing.none { m -> m.videoId == it.videoId } })
    }

    /** Asks again for the songs of [id] that never arrived. */
    fun retryPending(id: String) {
        get(id)?.songs?.filter { it.localUri == null }?.forEach { Downloads.enqueue(appContext, it) }
    }

    // ---- storage ---------------------------------------------------------------

    private fun update(id: String, block: (MixedPlaylist) -> MixedPlaylist) =
        mutate { lists -> lists.map { if (it.id == id) block(it) else it } }

    private fun mutate(persist: Boolean = true, block: (List<MixedPlaylist>) -> List<MixedPlaylist>) {
        synchronized(lock) {
            _playlists.update(block)
            if (persist && this::appContext.isInitialized) {
                appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, write(_playlists.value)).apply()
            }
        }
    }

    /** For the backup file: everything but the audio, which is downloaded again on the other side. */
    fun exportJson(): String? = _playlists.value.takeIf { it.isNotEmpty() }?.let { write(it) }

    /** Merges a backup's playlists in, replacing any with the same id. Their songs are fetched again. */
    fun importJson(raw: String?) {
        if (raw.isNullOrBlank()) return
        val incoming = read(raw).map { playlist ->
            playlist.copy(songs = playlist.songs.map { it.copy(localUri = null) })
        }
        if (incoming.isEmpty()) return
        mutate { current ->
            val ids = incoming.map { it.id }.toSet()
            current.filterNot { it.id in ids } + incoming
        }
        scope.launch { requestMissing() }
    }

    fun shelfItems(subtitleFor: (Int) -> String): List<ShelfItem> = _playlists.value.map { playlist ->
        ShelfItem(
            title = playlist.title,
            subtitle = subtitleFor(playlist.songs.size),
            thumbnailUrl = playlist.coverUrl,
            videoId = null,
            browseId = playlist.browseId,
        )
    }

    private fun write(lists: List<MixedPlaylist>): String = JSONObject().apply {
        put("v", 1)
        put(
            "playlists",
            JSONArray().also { array ->
                lists.forEach { playlist ->
                    array.put(
                        JSONObject().apply {
                            put("id", playlist.id)
                            put("title", playlist.title)
                            put("created", playlist.createdAt)
                            put("songs", JSONArray().also { s -> playlist.songs.forEach { s.put(songJson(it)) } })
                            put(
                                "transitions",
                                JSONObject().also { t ->
                                    playlist.transitions.forEach { (key, value) ->
                                        if (value.state == MixState.AUTO || value.state == MixState.MANUAL) {
                                            t.put(
                                                key,
                                                JSONObject().apply {
                                                    put("state", value.state.name)
                                                    value.mix?.let { put("mix", it.toJson()) }
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

    private fun read(raw: String?): List<MixedPlaylist> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONObject(raw).optJSONArray("playlists") ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val songs = obj.optJSONArray("songs")?.let { s -> (0 until s.length()).mapNotNull { songFrom(s.optJSONObject(it)) } }.orEmpty()
                val transitions = HashMap<String, MixTransition>()
                obj.optJSONObject("transitions")?.let { t ->
                    t.keys().forEach { key ->
                        val entry = t.optJSONObject(key) ?: return@forEach
                        val state = MixState.values().firstOrNull { it.name == entry.optString("state") } ?: return@forEach
                        val mix = ManualMix.fromJson(entry.optJSONObject("mix")) ?: return@forEach
                        if (state == MixState.AUTO || state == MixState.MANUAL) transitions[key] = MixTransition(state, mix)
                    }
                }
                pruned(MixedPlaylist(id, obj.optString("title", "Mixed playlist"), songs, transitions, obj.optLong("created", 0L)))
            }
        }.getOrDefault(emptyList())
    }

    private fun songJson(song: Song): JSONObject = JSONObject().apply {
        put("videoId", song.videoId)
        put("title", song.title)
        put("artist", song.artist)
        putOpt("thumbnailUrl", song.thumbnailUrl)
        putOpt("durationText", song.durationText)
        putOpt("artistId", song.artistId)
        putOpt("albumId", song.albumId)
        putOpt("albumName", song.albumName)
        putOpt("localUri", song.localUri)
    }

    private fun songFrom(obj: JSONObject?): Song? {
        if (obj == null) return null
        fun text(key: String): String? = if (obj.isNull(key)) null else obj.optString(key).takeIf { it.isNotEmpty() }
        val videoId = text("videoId") ?: return null
        return Song(
            videoId = videoId,
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            thumbnailUrl = text("thumbnailUrl"),
            durationText = text("durationText"),
            artistId = text("artistId"),
            albumId = text("albumId"),
            albumName = text("albumName"),
            localUri = text("localUri"),
        )
    }
}
