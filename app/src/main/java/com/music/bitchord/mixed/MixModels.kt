package com.music.bitchord.mixed

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The life of one transition between two neighbouring songs in a mixed
 * playlist: nothing yet, being built, built by the analyser, or edited by hand.
 * [MIXING] is never written to disk — a build cut short is "no mix" again.
 */
enum class MixState { NONE, MIXING, AUTO, MANUAL }

data class CurvePoint(val x: Float, val y: Float)

/**
 * One automation lane over the length of a blend: x is how far through the
 * blend (0..1), y is the level (0..1). Always at least two points, sorted by x,
 * the first at 0 and the last at 1, so [at] never has to ask whether it has
 * anything to read.
 */
data class Curve(val points: List<CurvePoint>) {

    fun at(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        val list = points
        if (list.isEmpty()) return 1f
        if (t <= list.first().x) return list.first().y
        if (t >= list.last().x) return list.last().y
        for (i in 1 until list.size) {
            val b = list[i]
            if (t <= b.x) {
                val a = list[i - 1]
                val span = b.x - a.x
                if (span <= 1e-6f) return b.y
                return a.y + (b.y - a.y) * ((t - a.x) / span)
            }
        }
        return list.last().y
    }

    /** Moves point [index] to ([x], [y]), keeping it between its neighbours; the two ends keep x = 0 and x = 1. */
    fun moved(index: Int, x: Float, y: Float): Curve {
        if (index !in points.indices) return this
        val last = points.lastIndex
        val minX = if (index == 0) 0f else points[index - 1].x + MIN_GAP
        val maxX = if (index == last) 1f else points[index + 1].x - MIN_GAP
        val nx = when (index) {
            0 -> 0f
            last -> 1f
            else -> x.coerceIn(minX, maxOf(minX, maxX))
        }
        val next = points.toMutableList()
        next[index] = CurvePoint(nx, y.coerceIn(0f, 1f))
        return Curve(next)
    }

    /** Adds a point at [x] — on the line as it stands unless [y] is given — and returns the new curve and the point's index. */
    fun inserted(x: Float, y: Float? = null): Pair<Curve, Int> {
        val t = x.coerceIn(MIN_GAP, 1f - MIN_GAP)
        val near = points.indexOfFirst { kotlin.math.abs(it.x - t) < MIN_GAP }
        if (near >= 0) return this to near
        val index = points.indexOfFirst { it.x > t }.let { if (it < 0) points.size else it }
        val next = points.toMutableList()
        next.add(index, CurvePoint(t, (y ?: at(t)).coerceIn(0f, 1f)))
        return Curve(next) to index
    }

    fun removed(index: Int): Curve {
        if (index <= 0 || index >= points.lastIndex || points.size <= 2) return this
        return Curve(points.filterIndexed { i, _ -> i != index })
    }

    fun toJson(): JSONArray = JSONArray().also { array ->
        points.forEach { array.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
    }

    companion object {
        const val MIN_GAP = 0.02f

        fun of(vararg pairs: Pair<Float, Float>): Curve = Curve(pairs.map { CurvePoint(it.first, it.second) })
        fun constant(value: Float): Curve = of(0f to value, 1f to value)
        fun sampled(steps: Int = 8, f: (Float) -> Float): Curve =
            Curve((0..steps).map { i -> val x = i / steps.toFloat(); CurvePoint(x, f(x).coerceIn(0f, 1f)) })

        fun fromJson(array: JSONArray?): Curve? {
            if (array == null || array.length() < 2) return null
            val points = ArrayList<CurvePoint>()
            for (i in 0 until array.length()) {
                val pair = array.optJSONArray(i) ?: return null
                points += CurvePoint(pair.optDouble(0, 0.0).toFloat(), pair.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f))
            }
            val sorted = points.sortedBy { it.x }
            if (sorted.first().x > 0.001f || sorted.last().x < 0.999f) return null
            return Curve(sorted)
        }
    }
}

/** The five tools of the editor's Custom view, each with its own colour bar and preset list. */
enum class MixTool(val label: String, val argb: Long) {
    VOLUME("Volume", 0xFF3DA5F4),
    EQ("EQ", 0xFFFFC21A),
    FILTER("Filter", 0xFFE070F0),
    EFFECTS("Effects", 0xFFEDEDED),
    LOOPING("Looping", 0xFFFF9F0A),
}

/** Every automation lane a transition has, and the tool it belongs to. */
enum class Lane(val tool: MixTool, val label: String, val outgoing: Boolean) {
    VOLUME_OUT(MixTool.VOLUME, "Volume · out", true),
    VOLUME_IN(MixTool.VOLUME, "Volume · in", false),
    BASS_OUT(MixTool.EQ, "Bass · out", true),
    BASS_IN(MixTool.EQ, "Bass · in", false),
    LOW_PASS_OUT(MixTool.FILTER, "Low pass · out", true),
    LOW_PASS_IN(MixTool.FILTER, "Low pass · in", false),
    HIGH_PASS_OUT(MixTool.FILTER, "High pass · out", true),
    HIGH_PASS_IN(MixTool.FILTER, "High pass · in", false),
    ECHO_OUT(MixTool.EFFECTS, "Echo · out", true),
}

class ToolPreset(val id: String, val label: String, val apply: (ManualMix) -> ManualMix)

private val OPEN = Curve.constant(1f)
private val SHUT = Curve.constant(0f)
private fun c(vararg p: Pair<Float, Float>) = Curve.of(*p)
private fun eqFall(x: Float) = cos(x * PI.toFloat() / 2f)
private fun eqRise(x: Float) = sin(x * PI.toFloat() / 2f)

/**
 * The preset lists behind each tool's sheet, and the whole-mix chips above
 * them. Each preset is only a set of lane curves, so Customize can start from
 * any of them.
 */
object PresetCatalog {
    const val CUSTOM = "custom"

    private fun vol(id: String, label: String, out: Curve, inn: Curve) =
        ToolPreset(id, label) { it.copy(volumeOut = out, volumeIn = inn, volumeId = id) }

    val volume: List<ToolPreset> = listOf(
        vol("overlap", "Overlap", c(0f to 1f, 0.55f to 0.9f, 1f to 0f), c(0f to 0f, 0.45f to 0.9f, 1f to 1f)),
        vol("fade", "Fade", Curve.sampled { eqFall(it) }, Curve.sampled { eqRise(it) }),
        vol("linear", "Linear fade", c(0f to 1f, 1f to 0f), c(0f to 0f, 1f to 1f)),
        vol("rise", "Rise", c(0f to 1f, 0.7f to 1f, 1f to 0f), c(0f to 0f, 0.7f to 1f, 1f to 1f)),
        vol("blend", "Blend", c(0f to 1f, 0.5f to 0.95f, 1f to 0f), c(0f to 0f, 0.5f to 0.95f, 1f to 1f)),
        vol(
            "wave", "Wave",
            c(0f to 1f, 0.15f to 0.45f, 0.3f to 0.9f, 0.45f to 0.35f, 0.6f to 0.75f, 0.8f to 0.2f, 1f to 0f),
            c(0f to 0f, 0.15f to 0.55f, 0.3f to 0.1f, 0.45f to 0.65f, 0.6f to 0.25f, 0.8f to 0.8f, 1f to 1f),
        ),
        vol("cut", "Cut", c(0f to 1f, 0.49f to 1f, 0.51f to 0f, 1f to 0f), c(0f to 0f, 0.49f to 0f, 0.51f to 1f, 1f to 1f)),
        vol("late_cut", "Late cut", c(0f to 1f, 0.84f to 1f, 0.86f to 0f, 1f to 0f), c(0f to 0f, 0.84f to 0.15f, 0.86f to 1f, 1f to 1f)),
        vol("early_cut", "Early cut", c(0f to 1f, 0.14f to 1f, 0.16f to 0f, 1f to 0f), c(0f to 0.2f, 0.14f to 0.5f, 0.16f to 1f, 1f to 1f)),
        vol("in_first", "Incoming first", c(0f to 1f, 0.5f to 1f, 1f to 0f), c(0f to 0f, 0.35f to 1f, 1f to 1f)),
        vol("out_first", "Outgoing first", c(0f to 1f, 0.4f to 0.05f, 1f to 0f), c(0f to 0f, 0.5f to 0.2f, 1f to 1f)),
        vol("dip", "Dip", c(0f to 1f, 0.35f to 0.35f, 0.5f to 0.15f, 1f to 0f), c(0f to 0f, 0.5f to 0.15f, 0.65f to 0.35f, 1f to 1f)),
    )

    private fun eq(id: String, label: String, out: Curve, inn: Curve) =
        ToolPreset(id, label) { it.copy(bassOut = out, bassIn = inn, eqId = id) }

    val eq: List<ToolPreset> = listOf(
        eq("none", "None", OPEN, OPEN),
        eq("center", "Center bass swap", c(0f to 1f, 0.46f to 1f, 0.54f to 0f, 1f to 0f), c(0f to 0f, 0.46f to 0f, 0.54f to 1f, 1f to 1f)),
        eq("end", "End bass swap", c(0f to 1f, 0.8f to 1f, 0.9f to 0f, 1f to 0f), c(0f to 0f, 0.8f to 0f, 0.9f to 1f, 1f to 1f)),
        eq("start", "Start bass swap", c(0f to 1f, 0.1f to 1f, 0.2f to 0f, 1f to 0f), c(0f to 0f, 0.1f to 0f, 0.2f to 1f, 1f to 1f)),
        eq("three_band", "3 band fade", c(0f to 1f, 1f to 0f), c(0f to 0f, 1f to 1f)),
        eq("quick_cut", "Quick bass cut", c(0f to 1f, 0.08f to 0f, 1f to 0f), OPEN),
        eq("slow_cut", "Slow bass cut", c(0f to 1f, 0.9f to 0f, 1f to 0f), OPEN),
        eq("bass_out", "Bass out", c(0f to 1f, 0.5f to 0f, 1f to 0f), c(0f to 0f, 0.5f to 0.2f, 1f to 1f)),
        eq("bass_in", "Bass in", OPEN, c(0f to 0f, 0.6f to 0f, 0.7f to 1f, 1f to 1f)),
        eq("kill_both", "Bass drop", c(0f to 1f, 0.3f to 0f, 0.7f to 0f, 0.71f to 0f, 1f to 0f), c(0f to 0f, 0.7f to 0f, 0.72f to 1f, 1f to 1f)),
        eq("in_clean", "Clean entry", OPEN, c(0f to 0f, 0.5f to 0f, 0.55f to 1f, 1f to 1f)),
    )

    private fun filter(id: String, label: String, lpOut: Curve, lpIn: Curve, hpOut: Curve, hpIn: Curve) =
        ToolPreset(id, label) {
            it.copy(lpOut = lpOut, lpIn = lpIn, hpOut = hpOut, hpIn = hpIn, filterId = id)
        }

    private val lpOutSweep = c(0f to 1f, 0.2f to 1f, 1f to 0.05f)
    private val lpInSweep = c(0f to 0.05f, 0.8f to 1f, 1f to 1f)
    private val hpOutSweep = c(0f to 1f, 0.2f to 1f, 1f to 0.05f)
    private val hpInSweep = c(0f to 0.05f, 0.8f to 1f, 1f to 1f)

    val filter: List<ToolPreset> = listOf(
        filter("none", "None", OPEN, OPEN, OPEN, OPEN),
        filter("lp_out", "Low pass filter out", lpOutSweep, OPEN, OPEN, OPEN),
        filter("lp_in", "Low pass filter in", OPEN, lpInSweep, OPEN, OPEN),
        filter("lp_in_lp_out", "Low pass filter in low pass filter out", lpOutSweep, lpInSweep, OPEN, OPEN),
        filter("lp_in_hp_out", "Low pass filter in high pass filter out", OPEN, lpInSweep, hpOutSweep, OPEN),
        filter("hp_in", "High pass filter in", OPEN, OPEN, OPEN, hpInSweep),
        filter("hp_out", "High pass filter out", OPEN, OPEN, hpOutSweep, OPEN),
        filter("hp_in_hp_out", "High pass filter in high pass filter out", OPEN, OPEN, hpOutSweep, hpInSweep),
        filter("hp_in_lp_out", "High pass filter in low pass filter out", lpOutSweep, OPEN, OPEN, hpInSweep),
        filter("gentle_lp_out", "Gentle low pass out", c(0f to 1f, 0.5f to 0.8f, 1f to 0.3f), OPEN, OPEN, OPEN),
        filter("quick_lp_out", "Quick low pass out", c(0f to 1f, 0.7f to 1f, 0.8f to 0.08f, 1f to 0.05f), OPEN, OPEN, OPEN),
        filter("narrow", "Telephone (narrow band)", c(0f to 1f, 0.5f to 0.35f, 1f to 0.1f), OPEN, c(0f to 1f, 0.5f to 0.5f, 1f to 0.2f), OPEN),
    )

    private fun fx(id: String, label: String, echo: Curve, beats: Float = 1f) =
        ToolPreset(id, label) { it.copy(echoOut = echo, echoBeats = beats, effectId = id) }

    val effects: List<ToolPreset> = listOf(
        fx("none", "None", SHUT),
        fx("echo_out", "Echo out", c(0f to 0f, 0.4f to 0f, 0.7f to 0.85f, 1f to 0.6f)),
        fx("echo_34", "Echo ¾ out end", c(0f to 0f, 0.72f to 0f, 0.78f to 0.9f, 1f to 0.6f)),
        fx("echo_half", "Echo ½ out", c(0f to 0f, 0.45f to 0f, 0.52f to 0.9f, 1f to 0.5f)),
        fx("echo_burst", "Echo burst", c(0f to 0f, 0.46f to 0f, 0.5f to 1f, 0.6f to 0f, 1f to 0f)),
        fx("echo_long", "Long echo (wash out)", c(0f to 0f, 0.35f to 0f, 0.6f to 0.9f, 1f to 0.8f), 2f),
        fx("echo_quarter", "Fast echo", c(0f to 0f, 0.5f to 0f, 0.7f to 0.8f, 1f to 0.5f), 0.5f),
    )

    /** The last beats of the outgoing song repeat through the whole blend: 1, 2, 4 or 8 of them. */
    val looping: List<ToolPreset> = buildList {
        add(ToolPreset("none", "None") { it.copy(loopBeats = 0) })
        for (beats in intArrayOf(1, 2, 4, 8)) {
            add(ToolPreset("beats-$beats", "$beats beat loop") { it.copy(loopBeats = beats) })
        }
    }

    fun loopingIdOf(mix: ManualMix): String = if (mix.loopBeats > 0) "beats-${mix.loopBeats}" else "none"

    fun list(tool: MixTool): List<ToolPreset> = when (tool) {
        MixTool.VOLUME -> volume
        MixTool.EQ -> eq
        MixTool.FILTER -> filter
        MixTool.EFFECTS -> effects
        MixTool.LOOPING -> looping
    }

    fun idOf(tool: MixTool, mix: ManualMix): String = when (tool) {
        MixTool.VOLUME -> mix.volumeId
        MixTool.EQ -> mix.eqId
        MixTool.FILTER -> mix.filterId
        MixTool.EFFECTS -> mix.effectId
        MixTool.LOOPING -> loopingIdOf(mix)
    }

    fun labelOf(tool: MixTool, mix: ManualMix): String {
        val id = idOf(tool, mix)
        return list(tool).firstOrNull { it.id == id }?.label ?: "Custom"
    }

    fun applyTool(tool: MixTool, id: String, mix: ManualMix): ManualMix {
        val preset = list(tool).firstOrNull { it.id == id } ?: return mix
        return preset.apply(mix).copy(composite = CUSTOM)
    }

    /** The whole-mix chips. "auto" is handled by the editor, which re-runs the analyser's own plan. */
    class Composite(val id: String, val label: String, val volume: String, val eq: String, val filter: String, val effect: String, val loop: String = "none")

    val composites: List<Composite> = listOf(
        Composite("fade", "Fade", "fade", "none", "none", "none"),
        Composite("rise", "Rise", "rise", "end", "none", "none"),
        Composite("blend", "Blend", "blend", "center", "none", "none"),
        Composite("wave", "Wave", "wave", "none", "none", "none"),
        Composite("cut", "Cut", "cut", "center", "none", "none"),
        Composite("echo", "Echo", "late_cut", "end", "none", "echo_34"),
        Composite("sweep", "Sweep", "blend", "center", "lp_in_hp_out", "none"),
        Composite("loop", "Loop", "fade", "center", "none", "none", "beats-4"),
    )

    fun applyComposite(id: String, mix: ManualMix): ManualMix {
        val comp = composites.firstOrNull { it.id == id } ?: return mix
        var next = mix
        next = applyTool(MixTool.VOLUME, comp.volume, next)
        next = applyTool(MixTool.EQ, comp.eq, next)
        next = applyTool(MixTool.FILTER, comp.filter, next)
        next = applyTool(MixTool.EFFECTS, comp.effect, next)
        next = applyTool(MixTool.LOOPING, comp.loop, next)
        return next.copy(composite = id)
    }
}

/**
 * Everything the player needs to render one hand-made (or analyser-made)
 * transition, in plain seconds so it survives the analysis being refined later.
 *
 * Times are on each song's own timeline: [outStartSec] is where on the
 * outgoing song the blend opens, [inCueSec] is where on the incoming song it
 * starts playing from. The blend lasts [bars] bars of the outgoing song's beat
 * ([beatSec]).
 *
 * Every lane runs 0..1 across the blend. Volume lanes are gain. Bass lanes are
 * how much low end is left (1 = all). Low-pass and high-pass lanes are how open
 * that filter is (1 = off, nothing filtered). The echo lane is how much of the
 * outgoing song is sent into the echo.
 *
 * [loopBeats] > 0 hands the transition to the loop engine: the outgoing song's
 * [loopBeats] beats from [outStartSec] repeat for the whole blend while the
 * incoming song comes in on the same lanes.
 */
data class ManualMix(
    val bars: Int = 4,
    val outStartSec: Double = 0.0,
    val inCueSec: Double = 0.0,
    val beatSec: Double = 0.5,
    val tempoMatch: Boolean = true,
    val incomingRate: Double = 1.0,
    val volumeOut: Curve = Curve.sampled { cos(it * PI.toFloat() / 2f) },
    val volumeIn: Curve = Curve.sampled { sin(it * PI.toFloat() / 2f) },
    val bassOut: Curve = OPEN,
    val bassIn: Curve = OPEN,
    val lpOut: Curve = OPEN,
    val lpIn: Curve = OPEN,
    val hpOut: Curve = OPEN,
    val hpIn: Curve = OPEN,
    val echoOut: Curve = SHUT,
    val echoBeats: Float = 1f,
    val composite: String = "auto",
    val volumeId: String = "fade",
    val eqId: String = "none",
    val filterId: String = "none",
    val effectId: String = "none",
    /** 0 for none, else how many beats of the outgoing song, from [outStartSec], repeat through the blend. */
    val loopBeats: Int = 0,
) {
    val lengthSec: Double get() = bars * 4 * beatSec
    val usesLoops: Boolean get() = loopBeats > 0
    val hasEcho: Boolean get() = echoOut.points.any { it.y > 0.05f }

    fun curve(lane: Lane): Curve = when (lane) {
        Lane.VOLUME_OUT -> volumeOut
        Lane.VOLUME_IN -> volumeIn
        Lane.BASS_OUT -> bassOut
        Lane.BASS_IN -> bassIn
        Lane.LOW_PASS_OUT -> lpOut
        Lane.LOW_PASS_IN -> lpIn
        Lane.HIGH_PASS_OUT -> hpOut
        Lane.HIGH_PASS_IN -> hpIn
        Lane.ECHO_OUT -> echoOut
    }

    /** Replaces one lane by hand. The tool it belongs to — and the whole-mix chip — read as Custom from then on. */
    fun withCurve(lane: Lane, curve: Curve): ManualMix {
        val edited = when (lane) {
            Lane.VOLUME_OUT -> copy(volumeOut = curve, volumeId = PresetCatalog.CUSTOM)
            Lane.VOLUME_IN -> copy(volumeIn = curve, volumeId = PresetCatalog.CUSTOM)
            Lane.BASS_OUT -> copy(bassOut = curve, eqId = PresetCatalog.CUSTOM)
            Lane.BASS_IN -> copy(bassIn = curve, eqId = PresetCatalog.CUSTOM)
            Lane.LOW_PASS_OUT -> copy(lpOut = curve, filterId = PresetCatalog.CUSTOM)
            Lane.LOW_PASS_IN -> copy(lpIn = curve, filterId = PresetCatalog.CUSTOM)
            Lane.HIGH_PASS_OUT -> copy(hpOut = curve, filterId = PresetCatalog.CUSTOM)
            Lane.HIGH_PASS_IN -> copy(hpIn = curve, filterId = PresetCatalog.CUSTOM)
            Lane.ECHO_OUT -> copy(echoOut = curve, effectId = PresetCatalog.CUSTOM)
        }
        return edited.copy(composite = PresetCatalog.CUSTOM)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("bars", bars)
        put("outStart", outStartSec)
        put("inCue", inCueSec)
        put("beat", beatSec)
        put("tempoMatch", tempoMatch)
        put("rate", incomingRate)
        put("volOut", volumeOut.toJson())
        put("volIn", volumeIn.toJson())
        put("bassOut", bassOut.toJson())
        put("bassIn", bassIn.toJson())
        put("lpOut", lpOut.toJson())
        put("lpIn", lpIn.toJson())
        put("hpOut", hpOut.toJson())
        put("hpIn", hpIn.toJson())
        put("echo", echoOut.toJson())
        put("echoBeats", echoBeats.toDouble())
        put("composite", composite)
        put("volId", volumeId)
        put("eqId", eqId)
        put("filtId", filterId)
        put("fxId", effectId)
        put("loopBeats", loopBeats)
    }

    companion object {
        fun fromJson(obj: JSONObject?): ManualMix? {
            if (obj == null) return null
            val beat = obj.optDouble("beat", 0.5)
            if (!beat.isFinite() || beat <= 0.0) return null
            val d = ManualMix()
            return ManualMix(
                bars = obj.optInt("bars", d.bars).coerceIn(1, 64),
                outStartSec = obj.optDouble("outStart", 0.0).coerceAtLeast(0.0),
                inCueSec = obj.optDouble("inCue", 0.0).coerceAtLeast(0.0),
                beatSec = beat,
                tempoMatch = obj.optBoolean("tempoMatch", true),
                incomingRate = obj.optDouble("rate", 1.0).takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
                volumeOut = Curve.fromJson(obj.optJSONArray("volOut")) ?: d.volumeOut,
                volumeIn = Curve.fromJson(obj.optJSONArray("volIn")) ?: d.volumeIn,
                bassOut = Curve.fromJson(obj.optJSONArray("bassOut")) ?: d.bassOut,
                bassIn = Curve.fromJson(obj.optJSONArray("bassIn")) ?: d.bassIn,
                lpOut = Curve.fromJson(obj.optJSONArray("lpOut")) ?: d.lpOut,
                lpIn = Curve.fromJson(obj.optJSONArray("lpIn")) ?: d.lpIn,
                hpOut = Curve.fromJson(obj.optJSONArray("hpOut")) ?: d.hpOut,
                hpIn = Curve.fromJson(obj.optJSONArray("hpIn")) ?: d.hpIn,
                echoOut = Curve.fromJson(obj.optJSONArray("echo")) ?: d.echoOut,
                echoBeats = obj.optDouble("echoBeats", 1.0).toFloat().coerceIn(0.25f, 4f),
                composite = obj.optString("composite", PresetCatalog.CUSTOM),
                volumeId = obj.optString("volId", PresetCatalog.CUSTOM),
                eqId = obj.optString("eqId", PresetCatalog.CUSTOM),
                filterId = obj.optString("filtId", PresetCatalog.CUSTOM),
                effectId = obj.optString("fxId", PresetCatalog.CUSTOM),
                loopBeats = obj.optInt("loopBeats", 0).takeIf { it in intArrayOf(1, 2, 4, 8) } ?: 0,
            )
        }
    }
}

/** A transition as the playlist stores it. [mix] is null exactly while [state] is NONE or MIXING. */
data class MixTransition(val state: MixState, val mix: ManualMix? = null) {
    companion object {
        val NONE = MixTransition(MixState.NONE, null)
    }
}
