package com.music.bitchord.mixed

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Beat positions of a measured song, for snapping a dragged transition onto the grid. */
class BeatGrid(private val analysis: TrackAnalysis) {
    val beatSec: Double = when {
        analysis.beatInterval in 0.2..2.0 -> analysis.beatInterval
        analysis.bpm > 0 -> 60.0 / analysis.bpm
        else -> 0.5
    }

    /** Where the first beat falls, so the grid is anchored to the music, not to second zero. */
    private val origin: Double = analysis.firstBeat.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    fun snap(seconds: Double): Double {
        if (seconds <= origin) return origin
        val beats = ((seconds - origin) / beatSec).roundToInt()
        return origin + beats * beatSec
    }

    /** The nearest bar line — a downbeat when the analysis found them, else every fourth beat. */
    fun snapToBar(seconds: Double): Double {
        val downbeats = analysis.downbeats
        if (downbeats.isNotEmpty()) return downbeats.minByOrNull { abs(it - seconds) } ?: snap(seconds)
        val bar = beatSec * 4
        if (seconds <= origin) return origin
        return origin + ((seconds - origin) / bar).roundToInt() * bar
    }

    /** Within [thresholdSec] of a beat: pull onto it. Further than that: leave it be. This is the "magnet". */
    fun magnet(seconds: Double, thresholdSec: Double): Double {
        val snapped = snap(seconds)
        return if (abs(snapped - seconds) <= thresholdSec) snapped else seconds
    }
}

/** Turns what the analyser would do for a pair of songs into a [ManualMix] the editor can open. */
object MixedBuilder {

    /** Null when either song has not been measured yet. */
    suspend fun build(a: Song, b: Song): ManualMix? = withContext(Dispatchers.Default) {
        val first = MixedAnalysis.analysisOf(a.videoId) ?: return@withContext null
        val second = MixedAnalysis.analysisOf(b.videoId) ?: return@withContext null
        val durationA = first.duration.takeIf { it > 1.0 } ?: MixedAnalysis.durationSeconds(a)
        val durationB = second.duration.takeIf { it > 1.0 } ?: MixedAnalysis.durationSeconds(b)
        if (durationA <= 1.0) return@withContext null
        val plan = runCatching {
            planTransition(
                analysis = first,
                nextAnalysis = second,
                currentTrack = TransitionTrackInfo(a.videoId, (durationA * 1000).toLong(), a.title, a.artist, a.albumName.orEmpty(), a.albumId.orEmpty()),
                nextTrack = TransitionTrackInfo(b.videoId, (durationB * 1000).toLong(), b.title, b.artist, b.albumName.orEmpty(), b.albumId.orEmpty()),
                currentTime = 0.0,
                duration = durationA,
                fadeSeconds = 8.0,
                mode = CrossfadeMode.SMART,
                advanced = true,
            )
        }.getOrNull()
        fromPlan(plan, first, second, durationA)
    }

    /** The plan if it is a usable blend, otherwise a plain short fade over the end of the song. */
    fun fromPlan(plan: TransitionPlan?, a: TrackAnalysis, b: TrackAnalysis, durationA: Double): ManualMix {
        val grid = BeatGrid(a)
        val beat = plan?.beatSeconds?.takeIf { it in 0.2..2.0 } ?: grid.beatSec
        val tempo = MixTempo.match(a.bpm, b.bpm)
        val usable = plan != null && !plan.blocked && plan.transitionEnd > plan.transitionStart + 0.5 &&
            plan.transitionStyle != TransitionStyle.GAPLESS

        var mix: ManualMix
        if (usable && plan != null) {
            val bars = (plan.fadeSeconds / (4 * beat)).roundToInt().coerceIn(1, 16)
            mix = ManualMix(
                bars = bars,
                outStartSec = plan.transitionStart.coerceIn(0.0, max(0.0, durationA - 1.0)),
                inCueSec = max(0.0, plan.incomingCueTime),
                beatSec = beat,
                tempoMatch = true,
                incomingRate = if (plan.phaseLock) plan.incomingPlaybackRate else tempo.rate,
            )
            mix = PresetCatalog.applyTool(MixTool.VOLUME, "fade", mix)
            mix = PresetCatalog.applyTool(MixTool.EQ, if (plan.bassSwap) "center" else "none", mix)
            mix = PresetCatalog.applyTool(
                MixTool.FILTER,
                if (plan.transitionStyle == TransitionStyle.DJ_FILTER) "lp_in_hp_out" else "none",
                mix,
            )
            mix = PresetCatalog.applyTool(MixTool.EFFECTS, if (plan.echoSeconds > 0.0) "echo_34" else "none", mix)
        } else {
            val bars = 2
            val length = bars * 4 * beat
            val start = grid.snapToBar(max(0.0, durationA - length - 2.0)).coerceIn(0.0, max(0.0, durationA - length))
            mix = ManualMix(
                bars = bars,
                outStartSec = start,
                inCueSec = max(0.0, b.pickupTime ?: b.audibleStartTime ?: 0.0),
                beatSec = beat,
                tempoMatch = true,
                incomingRate = tempo.rate,
            )
            mix = PresetCatalog.applyTool(MixTool.VOLUME, "fade", mix)
            mix = PresetCatalog.applyTool(MixTool.EQ, "none", mix)
        }
        return mix.copy(composite = "auto")
    }

    /** Re-derives the tempo rate once the beatmatching switch or the songs' tempi change. */
    fun withTempo(mix: ManualMix, outBpm: Double, inBpm: Double): ManualMix {
        val match = MixTempo.match(outBpm, inBpm, enabled = mix.tempoMatch)
        return mix.copy(incomingRate = match.rate)
    }

    /** Keeps a blend inside the outgoing song: it cannot start before 0 or run past the end. */
    fun clamp(mix: ManualMix, durationA: Double): ManualMix {
        val length = mix.lengthSec
        val start = min(max(0.0, mix.outStartSec), max(0.0, durationA - length))
        return if (start == mix.outStartSec) mix else mix.copy(outStartSec = start)
    }
}
