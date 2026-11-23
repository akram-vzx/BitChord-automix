package com.music.bitchord.mixed

import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.smart.ECHO_REPEATS
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MAX_ECHO_SECONDS
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.roundToInt

/** A join the player has found for the song now playing and the one after it. */
class MixedJoin(val playlistId: String, val transition: MixTransition)

/**
 * The player's side of mixed playlists: which join belongs to the pair about
 * to play, and what the engine should do for it.
 *
 * Only the playlist the listener started from is consulted, and only for two
 * songs that sit next to each other in it, in that order — everything else
 * (every other playlist, Downloads, Local Music, a queue shuffled out of
 * order) plays exactly as it did before.
 */
object MixedPlayback {
    @Volatile private var active: String? = null
    private var releaseBoost: (() -> Unit)? = null

    /** Called by the mixed playlist's page when it starts playing, and with null by anything that ends that. */
    @Synchronized
    fun activate(playlistId: String?) {
        if (active == playlistId) return
        active = playlistId
        releaseBoost?.invoke()
        // Playing a mixed playlist runs the analyser at full effort, so the next
        // songs are measured in time; it is given back when playback moves on.
        releaseBoost = if (playlistId != null) AutomixEffort.boost() else null
    }

    fun activeId(): String? = active

    @Volatile private var preview: Triple<String, String, ManualMix>? = null

    /** While the editor is auditioning a mix that is not saved yet, the player uses it for that pair instead of the stored one. */
    fun setPreview(from: String?, to: String?, mix: ManualMix?) {
        preview = if (from != null && to != null && mix != null) Triple(from, to, mix) else null
    }

    /** The join between two media ids, or null when this pair is not a neighbouring pair of the active mixed playlist. */
    fun joinFor(currentMediaId: String, nextMediaId: String): MixedJoin? {
        val id = active ?: return null
        preview?.let { if (it.first == currentMediaId && it.second == nextMediaId) return MixedJoin(id, MixTransition(MixState.MANUAL, it.third)) }
        val playlist = MixedPlaylists.get(id) ?: return null
        val songs = playlist.songs
        for (i in 0 until songs.size - 1) {
            if (songs[i].videoId == currentMediaId && songs[i + 1].videoId == nextMediaId) {
                return MixedJoin(id, playlist.transitionBetween(songs[i], songs[i + 1]))
            }
        }
        return null
    }

    /** Whether [mediaId] is a song of the playlist being played — for deciding to boost its analysis. */
    fun isActiveSong(mediaId: String): Boolean =
        active?.let { MixedPlaylists.get(it) }?.songs?.any { it.videoId == mediaId } == true

    /**
     * The engine's plan for [mix]. The times are the listener's, so the plan is
     * trusted as it stands apart from being kept inside the two songs.
     */
    fun planFor(
        mix: ManualMix,
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        outgoingDurationSec: Double,
    ): TransitionPlan {
        val start = mix.outStartSec.coerceIn(0.0, max(0.0, outgoingDurationSec - 1.0))
        val length = min(mix.lengthSec, max(0.5, outgoingDurationSec - start))
        val tempo = MixTempo.match(outgoing.bpm, incoming.bpm, enabled = mix.tempoMatch)
        val rate = if (mix.tempoMatch) mix.incomingRate.coerceIn(0.5, 2.0) else 1.0
        val cue = max(0.0, mix.inCueSec)
        val echoSeconds = if (mix.hasEcho) min(mix.echoBeats * mix.beatSec, MAX_ECHO_SECONDS) else 0.0
        val echoFits = echoSeconds > 0.0 && outgoingDurationSec - (start + length) >= echoSeconds * ECHO_REPEATS
        return TransitionPlan(
            shouldStart = false,
            markerVisible = true,
            blocked = false,
            reason = "mixed playlist",
            transitionStart = start,
            transitionEnd = start + length,
            fadeSeconds = length,
            transitionStyle = TransitionStyle.DJ_BLEND,
            incomingCueTime = cue,
            incomingHandoffTime = cue + length * rate,
            incomingPlaybackRate = rate,
            outgoingPlaybackRate = 1.0,
            handoffStartSeconds = 0.0,
            handoffDuration = length,
            pickupSeconds = cue,
            transitionBeats = mix.bars * 4,
            bassSwap = false,
            filterSweep = 0.0,
            outgoingBpm = outgoing.bpm,
            incomingBpm = if (mix.tempoMatch && tempo.matched) tempo.alignedIncomingBpm else incoming.bpm,
            beatSeconds = mix.beatSec,
            phaseLock = mix.tempoMatch && tempo.matched,
            echoSeconds = if (echoFits) echoSeconds else 0.0,
        )
    }

    /**
     * The loop engine's plan for a mix with a loop: the outgoing song's
     * [ManualMix.loopBeats] beats from the start of the blend repeat all the way
     * through it, while the incoming song plays straight on from its cue.
     *
     * The engine's clock counts in beats, so a loop of 1, 2, 4 or 8 of them is
     * just a [LoopMix.Pick] of that many beats; and the blend becomes one
     * continuous tempo ramp from the outgoing song's tempo to the incoming one's
     * (no loop of its own to enter, no exit to settle in). That ramp is also why a
     * loop always lines the two songs up, whatever the Beatmatching switch says:
     * both decks run on a single grid.
     */
    fun loopPlanFor(
        mix: ManualMix,
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        outgoingDurationSec: Double,
        incomingDurationSec: Double,
    ): LoopMix.Plan? {
        if (!mix.usesLoops) return null
        val outBeat = mix.beatSec
        val inBeat = BeatGrid(incoming).beatSec
        if (outBeat <= 0.0 || inBeat <= 0.0) return null

        val bars = mix.bars.coerceAtLeast(1)
        val totalBeats = bars * 4
        val outStart = mix.outStartSec.coerceAtLeast(0.0)
        val outEnd = outStart + mix.loopBeats * outBeat
        val inStart = mix.inCueSec.coerceAtLeast(0.0)

        val outBpm = 60.0 / outBeat
        val inBpm = 60.0 / inBeat
        val fold = doubleArrayOf(1.0, 2.0, 0.5).minByOrNull { abs(ln(inBpm / it / outBpm)) } ?: 1.0
        val inBeats = (totalBeats * fold).roundToInt().coerceAtLeast(1)
        val inEnd = inStart + inBeats * inBeat
        // A loop or a blend that would run off the end of its song is not played.
        if (outEnd > outgoingDurationSec - 0.25 || inEnd + LOOP_TAIL_SECONDS > incomingDurationSec) return null

        return LoopMix.Plan(
            out = LoopMix.Pick(outStart, outEnd, (mix.loopBeats / 4).coerceAtLeast(1), outBeat, outBpm, 0.0, LoopMix.Check.MASK, mix.loopBeats),
            inn = LoopMix.Pick(inStart, inEnd, (inBeats / 4).coerceAtLeast(1), inBeat, inBpm, 0.0, LoopMix.Check.MASK, inBeats),
            fold = fold,
            enterBars = 0,
            rampBars = bars,
            exitBars = 0,
            totalBars = bars,
        )
    }

    /**
     * The renderer's gain, bass and high-pass envelopes drawn from the mix's own
     * lanes, so a loop mix follows the same Volume, EQ and Filter the editor
     * shows. Each lane is read at sixteen points across the blend, placed on the
     * clock's beats so they stay right through the tempo ramp.
     *
     * The renderer has no low-pass or echo, so those two lanes are silent in a
     * loop mix.
     */
    fun shapeFor(mix: ManualMix): (LoopMixRenderer, LoopMix.Clock) -> Unit = { renderer, clock ->
        val steps = 16
        val beats = clock.beats
        fun timeAt(fraction: Float): Double {
            val beat = (fraction * beats).toDouble()
            val index = beat.toInt().coerceIn(0, beats - 1)
            val into = beat - index
            return clock.beatTimes[index] + (clock.beatTimes[index + 1] - clock.beatTimes[index]) * into
        }
        val times = DoubleArray(steps + 1) { timeAt(it / steps.toFloat()) }
        fun sampled(curve: Curve, f: (Float) -> Float) = FloatArray(steps + 1) { f(curve.at(it / steps.toFloat())) }
        val handover = LoopMix.HANDOVER_SEC
        val cut = LoopMix.BASS_CUT_DB.toFloat()
        val highPassSpan = (LoopMix.HIGH_PASS_TO_HZ / LoopMix.HIGH_PASS_FROM_HZ)
        fun highPass(open: Float): Float = (LoopMix.HIGH_PASS_FROM_HZ * highPassSpan.pow((1f - open).toDouble())).toFloat()

        // Outgoing: rises out of the song's own sound over the handover, follows the lane, and is silent at the release.
        val outGain = sampled(mix.volumeOut) { it }
        renderer.setEnvelope(
            LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
            doubleArrayOf(-handover) + times + doubleArrayOf(clock.releaseAt + 0.04),
            floatArrayOf(0f) + outGain + floatArrayOf(0f),
        )
        renderer.setEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN, times, sampled(mix.volumeIn) { it })
        renderer.setEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB, times, sampled(mix.bassOut) { (1f - it) * cut })
        renderer.setEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB, times, sampled(mix.bassIn) { (1f - it) * cut })
        renderer.setEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ, times, sampled(mix.hpOut) { highPass(it) }, exponential = true)
        renderer.setEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_PASS_HZ, times, sampled(mix.hpIn) { highPass(it) }, exponential = true)
    }

    private const val LOOP_TAIL_SECONDS = 3.0
}
