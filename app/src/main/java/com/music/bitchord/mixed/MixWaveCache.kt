package com.music.bitchord.mixed

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.SharedTrackAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * A stretch of a song's three-band waveform. Values are the analyser's own, 0..1
 * per band, so they are drawn as they come — the same as the Now Playing overview.
 */
class WaveSlice(val from: Double, val to: Double, val bands: MixWaveform.Bands) {
    val binSeconds: Double get() = (to - from) / bands.low.size.coerceAtLeast(1)

    /** The loudest of band [array] over [t0, t1): what one column of the screen should show of the bins under it. */
    private fun peak(array: FloatArray, t0: Double, t1: Double): Float {
        if (array.isEmpty() || t1 <= from || t0 >= to) return 0f
        val bin0 = ((max(t0, from) - from) / binSeconds).toInt().coerceIn(0, array.size - 1)
        val bin1 = ((min(t1, to) - from) / binSeconds).toInt().coerceIn(bin0, array.size - 1)
        var best = 0f
        for (i in bin0..bin1) if (array[i] > best) best = array[i]
        return best.coerceIn(0f, 1f)
    }

    fun low(t0: Double, t1: Double) = peak(bands.low, t0, t1)
    fun mid(t0: Double, t1: Double) = peak(bands.mid, t0, t1)
    fun high(t0: Double, t1: Double) = peak(bands.high, t0, t1)
}

/**
 * Waveforms the editor has already decoded, kept for as long as the app is.
 * Opening a mix, leaving it and coming back — or dragging the blend a little
 * way along its song — should not decode the same minute of audio twice.
 */
object MixWaveCache {
    private const val BINS_PER_SECOND = 16
    private const val MAX_ENTRIES = 24

    private val slices = ConcurrentHashMap<String, WaveSlice>()
    private val order = ArrayDeque<String>()

    /**
     * The slice of [song] that covers [needFrom]..[needTo] — widened to a stretch
     * whose edges only move in steps, so a drag does not change the key on every
     * frame. Decodes it on a background thread if it is not here already.
     */
    suspend fun load(context: Context, song: Song, durationSec: Double, needFrom: Double, needTo: Double): WaveSlice? {
        val uri = song.localUri ?: return null
        if (durationSec <= 0.0) return null
        val from = (kotlin.math.floor(max(0.0, needFrom - MARGIN) / STEP) * STEP).coerceAtLeast(0.0)
        val to = min(durationSec, kotlin.math.ceil((needTo + MARGIN) / STEP) * STEP)
        if (to - from < 1.0) return null
        val key = "${song.videoId}|${from.toInt()}|${to.toInt()}"
        slices[key]?.let { touch(key); return it }
        val bins = ((to - from) * BINS_PER_SECOND).toInt().coerceAtLeast(64)
        val slice = withContext(Dispatchers.IO) {
            runCatching {
                SharedTrackAnalyzer.get(context).mixWaveform(song.videoId, Uri.parse(uri), durationSec, from, to, bins)
            }.getOrNull()
        }?.let { WaveSlice(from, to, it) } ?: return null
        synchronized(order) {
            slices[key] = slice
            order.remove(key)
            order.addLast(key)
            while (order.size > MAX_ENTRIES) slices.remove(order.removeFirst())
        }
        return slice
    }

    /** Whether [slice] already holds everything from [needFrom] to [needTo]. */
    fun covers(slice: WaveSlice?, needFrom: Double, needTo: Double, durationSec: Double): Boolean {
        if (slice == null) return false
        return slice.from <= max(0.0, needFrom) + 0.01 && slice.to >= min(durationSec, needTo) - 0.01
    }

    private fun touch(key: String) = synchronized(order) {
        order.remove(key)
        order.addLast(key)
    }

    private const val STEP = 20.0
    private const val MARGIN = 30.0
}
