package com.music.bitchord.mixed

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.toMediaItem

/**
 * Plays a mix for the editor on the app's own player — the same engine that
 * will play it in the playlist, so what is heard is what will be played — and
 * leaves the player as it found it.
 *
 * Opening the editor pauses whatever was playing. The first audition keeps
 * the queue, position, shuffle and repeat it replaces, and closing the editor
 * puts them back (paused).
 */
class MixedPreviewHost(private val controller: () -> MediaController?) {

    private class Snapshot(
        val items: List<MediaItem>,
        val index: Int,
        val position: Long,
        val shuffle: Boolean,
        val repeat: Int,
    )

    private var snapshot: Snapshot? = null

    fun enter() {
        controller()?.pause()
    }

    /**
     * Starts the two songs of the join at [positionMs] of the first. The queue
     * and the start position go in one call, so the position is the new queue's
     * and not the old one's, whether or not the first song was already playing.
     */
    fun start(songs: List<Song>, positionMs: Long) {
        val c = controller() ?: return
        if (snapshot == null) {
            snapshot = Snapshot(
                items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) },
                index = c.currentMediaItemIndex,
                position = c.currentPosition,
                shuffle = c.shuffleModeEnabled,
                repeat = c.repeatMode,
            )
        }
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setMediaItems(songs.map { it.toMediaItem() }, 0, positionMs.coerceAtLeast(0L))
        c.prepare()
        c.play()
    }

    fun pause() {
        controller()?.pause()
    }

    fun resume() {
        controller()?.play()
    }

    /** The song now playing, its position in ms, and whether it is running. */
    fun playhead(): Triple<String?, Long, Boolean> {
        val c = controller() ?: return Triple(null, 0L, false)
        return Triple(c.currentMediaItem?.mediaId, c.currentPosition, c.isPlaying)
    }

    /** Puts back what the audition replaced, paused. Does nothing if there was no audition. */
    fun exit() {
        val c = controller()
        val saved = snapshot
        snapshot = null
        if (c == null || saved == null) return
        c.pause()
        c.shuffleModeEnabled = saved.shuffle
        c.repeatMode = saved.repeat
        if (saved.items.isEmpty()) {
            c.clearMediaItems()
        } else {
            c.setMediaItems(saved.items, saved.index.coerceAtLeast(0), saved.position)
            c.prepare()
            c.pause()
        }
    }
}
