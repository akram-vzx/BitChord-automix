package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.playback.AudioCache

/**
 * One [TrackAnalyzer] for the whole process.
 *
 * The playback service used to own the only one. Mixed playlists measure songs
 * while nothing is playing — and while the service may not even be running — so
 * both now go through here, which means the models are loaded once and a song
 * the service is measuring is never measured a second time by the playlist.
 *
 * Whoever calls [release] (the service, when it is destroyed) drops the shared
 * instance; the next [get] builds a fresh one.
 */
object SharedTrackAnalyzer {
    private var instance: TrackAnalyzer? = null

    @Synchronized
    fun get(context: Context): TrackAnalyzer =
        instance ?: TrackAnalyzer(context.applicationContext, AudioCache).also { instance = it }

    @Synchronized
    fun release() {
        instance?.release()
        instance = null
    }
}
