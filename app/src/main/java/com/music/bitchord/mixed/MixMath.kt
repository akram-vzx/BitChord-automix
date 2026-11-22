package com.music.bitchord.mixed

import com.music.bitchord.playback.smart.MAX_INCOMING_RATE
import com.music.bitchord.playback.smart.MIN_INCOMING_RATE
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** A position on the Camelot wheel: 1..12 and A (minor) or B (major). */
data class CamelotKey(val number: Int, val minor: Boolean) {
    val label: String get() = "$number${if (minor) 'A' else 'B'}"
}

object Camelot {
    // Indexed by pitch class, C = 0.
    private val MINOR_NUMBER = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
    private val MAJOR_NUMBER = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)

    /** Reads the analyser's key names — "A♭ minor", "C♯ major", and the plain-ASCII spellings of them. */
    fun parse(name: String?): CamelotKey? {
        val text = name?.trim().orEmpty()
        if (text.isEmpty()) return null
        val base = when (text[0].uppercaseChar()) {
            'C' -> 0
            'D' -> 2
            'E' -> 4
            'F' -> 5
            'G' -> 7
            'A' -> 9
            'B' -> 11
            else -> return null
        }
        val rest = text.substring(1)
        val shift = when {
            rest.startsWith("♯") || rest.startsWith("#") -> 1
            rest.startsWith("♭") || rest.startsWith("b") -> -1
            else -> 0
        }
        val lower = rest.lowercase()
        val minor = when {
            lower.contains("min") -> true
            lower.contains("maj") -> false
            lower.trimEnd().endsWith("m") -> true
            else -> false
        }
        val pitch = ((base + shift) % 12 + 12) % 12
        return CamelotKey(if (minor) MINOR_NUMBER[pitch] else MAJOR_NUMBER[pitch], minor)
    }

    /** Steps around the wheel, 0..6. */
    fun wheelSteps(a: CamelotKey, b: CamelotKey): Int {
        val d = abs(a.number - b.number)
        return minOf(d, 12 - d)
    }

    /**
     * How far apart two keys sound when mixed: 0 for the same key, 1 for a
     * neighbour or the relative major/minor, rising from there.
     */
    fun distance(a: CamelotKey?, b: CamelotKey?): Double {
        if (a == null || b == null) return UNKNOWN_KEY_COST
        val steps = wheelSteps(a, b)
        return if (a.minor == b.minor) steps.toDouble() else steps + 1.0
    }

    /** Whether two keys are a harmonic mix: same, a step either way, or relative. */
    fun compatible(a: CamelotKey?, b: CamelotKey?): Boolean {
        if (a == null || b == null) return false
        return distance(a, b) <= 1.0
    }

    /** A badge hue per wheel number, so neighbouring keys read as neighbouring colours. */
    fun hue(key: CamelotKey): Float = ((key.number - 1) * 30f + 140f) % 360f

    const val UNKNOWN_KEY_COST = 3.0
}

/** What it takes to bring the incoming song's tempo onto the outgoing one's. */
data class TempoMatch(
    /** The speed to play the incoming song at. 1.0 when nothing needs doing. */
    val rate: Double,
    /** The two grids line up after [rate], by an octave or not. */
    val matched: Boolean,
    /** The incoming BPM after [rate] and any octave jump, i.e. the tempo the blend runs at. */
    val alignedIncomingBpm: Double,
)

object MixTempo {
    /** Within this much the tempi already agree; stretching for it only costs sound quality. */
    const val DEAD_ZONE = 0.02

    /**
     * Speeds the incoming song up or slows it down to the outgoing song's
     * tempo — except when the two already agree, or one is double or half the
     * other, which is the same tempo on a 2:1 or 1:2 grid.
     */
    fun match(outBpm: Double, inBpm: Double, enabled: Boolean = true): TempoMatch {
        if (!(outBpm.isFinite() && inBpm.isFinite()) || outBpm <= 0.0 || inBpm <= 0.0) {
            return TempoMatch(1.0, false, inBpm.takeIf { it.isFinite() && it > 0 } ?: 0.0)
        }
        val octave = bestOctave(outBpm, inBpm)
        val aligned = inBpm * 2.0.pow(-octave)
        val residual = aligned / outBpm
        if (abs(residual - 1.0) < DEAD_ZONE) return TempoMatch(1.0, true, aligned)
        if (!enabled) return TempoMatch(1.0, false, aligned)
        val rate = 1.0 / residual
        if (rate < MIN_INCOMING_RATE || rate > MAX_INCOMING_RATE) return TempoMatch(1.0, false, aligned)
        return TempoMatch(rate, true, outBpm)
    }

    /** −1, 0 or 1: how many octaves down the incoming tempo has to move to sit nearest the outgoing one. */
    fun bestOctave(outBpm: Double, inBpm: Double): Int =
        (-1..1).minByOrNull { abs(ln(inBpm * 2.0.pow(-it) / outBpm)) } ?: 0

    /** The percentage gap between two tempi once octave differences are ignored. */
    fun gapPercent(a: Double, b: Double): Double {
        if (a <= 0.0 || b <= 0.0) return Double.NaN
        val octave = bestOctave(a, b)
        return abs(b * 2.0.pow(-octave) / a - 1.0) * 100.0
    }
}

/** Orders a playlist so every song follows the one with the closest tempo and key. */
object MixOrdering {
    data class Node(val bpm: Double, val key: CamelotKey?)

    private const val UNKNOWN_BPM_COST = 6.0
    private const val BPM_WEIGHT = 0.5
    private const val KEY_WEIGHT = 2.0

    fun cost(a: Node, b: Node): Double {
        val gap = MixTempo.gapPercent(a.bpm, b.bpm)
        val tempo = if (gap.isNaN()) UNKNOWN_BPM_COST else gap * BPM_WEIGHT
        return tempo + Camelot.distance(a.key, b.key) * KEY_WEIGHT
    }

    /** The new order as indices into [nodes]. Songs with nothing known sort where they cost least. */
    fun order(nodes: List<Node>): List<Int> {
        val n = nodes.size
        if (n <= 2) return nodes.indices.toList()
        val cost = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else cost(nodes[i], nodes[j]) } }

        val starts = if (n <= 60) (0 until n).toList() else (0 until 24).map { it * n / 24 }
        var best: IntArray? = null
        var bestCost = Double.MAX_VALUE
        for (start in starts) {
            val path = nearestFrom(start, cost)
            improve(path, cost)
            val total = pathCost(path, cost)
            if (total < bestCost - 1e-9) {
                bestCost = total
                best = path
            }
        }
        return (best ?: IntArray(n) { it }).toList()
    }

    fun pathCost(path: IntArray, cost: Array<DoubleArray>): Double {
        var total = 0.0
        for (i in 0 until path.size - 1) total += cost[path[i]][path[i + 1]]
        return total
    }

    private fun nearestFrom(start: Int, cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        val used = BooleanArray(n)
        val path = IntArray(n)
        path[0] = start
        used[start] = true
        for (step in 1 until n) {
            val last = path[step - 1]
            var pick = -1
            var pickCost = Double.MAX_VALUE
            for (j in 0 until n) {
                if (!used[j] && cost[last][j] < pickCost) {
                    pickCost = cost[last][j]
                    pick = j
                }
            }
            path[step] = pick
            used[pick] = true
        }
        return path
    }

    /** 2-opt on an open path: reverse a stretch whenever that shortens the two joins it changes. */
    private fun improve(path: IntArray, cost: Array<DoubleArray>) {
        val n = path.size
        var improved = true
        var passes = 0
        while (improved && passes < 40) {
            improved = false
            passes++
            for (i in 0 until n - 1) {
                for (j in i + 1 until n) {
                    val before = if (i > 0) cost[path[i - 1]][path[i]] else 0.0
                    val after = if (j < n - 1) cost[path[j]][path[j + 1]] else 0.0
                    val newBefore = if (i > 0) cost[path[i - 1]][path[j]] else 0.0
                    val newAfter = if (j < n - 1) cost[path[i]][path[j + 1]] else 0.0
                    if (newBefore + newAfter < before + after - 1e-9) {
                        reverse(path, i, j)
                        improved = true
                    }
                }
            }
        }
    }

    private fun reverse(path: IntArray, from: Int, to: Int) {
        var a = from
        var b = to
        while (a < b) {
            val t = path[a]; path[a] = path[b]; path[b] = t
            a++; b--
        }
    }

    /** Rounds for display: "104 BPM". */
    fun bpmLabel(bpm: Double): String = "${bpm.roundToInt()} BPM"
}
