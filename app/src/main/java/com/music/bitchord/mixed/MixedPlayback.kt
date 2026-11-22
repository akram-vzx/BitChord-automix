package com.music.bitchord.mixed

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
     * The loop engine's plan for a mix that asks for loops, built from the
     * listener's choice with the same arithmetic as [LoopMix.plan] uses for
     * its own. A side the listener did not loop gets a two-bar loop at the same
     * point, since the engine works on a pair of loops.
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
        val outBars = if (mix.endingLoopBars > 0) mix.endingLoopBars else DEFAULT_LOOP_BARS
        val inBars = if (mix.startingLoopBars > 0) mix.startingLoopBars else DEFAULT_LOOP_BARS

        val outStart = mix.outStartSec.coerceAtLeast(0.0)
        val outEnd = outStart + outBars * 4 * outBeat
        val inStart = mix.inCueSec.coerceAtLeast(0.0)
        val inEnd = inStart + inBars * 4 * inBeat
        // Loops that would run off the end of their song are not loops.
        if (outEnd > outgoingDurationSec - 0.25 || inEnd > incomingDurationSec - 0.25) return null

        val outBpm = 60.0 / outBeat
        val inBpm = 60.0 / inBeat
        val fold = doubleArrayOf(1.0, 2.0, 0.5).minByOrNull { abs(ln(inBpm / it / outBpm)) } ?: 1.0
        val target = inBpm / fold
        val rampBars = ceil(abs(target - outBpm) / LoopMix.MAX_BPM_PER_BAR).toInt()
            .coerceIn(LoopMix.MIN_RAMP_BARS, LoopMix.MAX_RAMP_BARS)
        val inLoopOutBars = inBars / fold
        val totalBars = (ceil((LoopMix.ENTER_BARS + rampBars + LoopMix.EXIT_BARS) / inLoopOutBars) * inLoopOutBars).roundToInt()

        return LoopMix.Plan(
            out = LoopMix.Pick(outStart, outEnd, outBars, outBeat, outBpm, 0.0, LoopMix.Check.MASK),
            inn = LoopMix.Pick(inStart, inEnd, inBars, inBeat, inBpm, 0.0, LoopMix.Check.MASK),
            fold = fold,
            enterBars = LoopMix.ENTER_BARS,
            rampBars = rampBars,
            exitBars = totalBars - LoopMix.ENTER_BARS - rampBars,
            totalBars = totalBars,
        )
    }

    private const val DEFAULT_LOOP_BARS = 2
}
