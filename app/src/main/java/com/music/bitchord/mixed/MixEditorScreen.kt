package com.music.bitchord.mixed

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.SharedTrackAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val GREEN = Color(0xFF1ED760)
private val WINDOW = Color(0xFF26262B)
private val LOW_COLOR = Color(0xFF2A3FE0)
private val MID_COLOR = Color(0xFFC27C2C)
private val HIGH_COLOR = Color(0xFFF3E6CF)
private val GRAPH_HEIGHT = 330.dp
private val BAR_OPTIONS = listOf(1, 2, 4, 8, 16, 32)

/** A stretch of a song's three-band waveform, with each band's own peak so every deck fills its lane. */
private class WaveSlice(val from: Double, val to: Double, val bands: MixWaveform.Bands) {
    private val lowMax = bands.low.maxOrNull()?.takeIf { it > 0f } ?: 1f
    private val midMax = bands.mid.maxOrNull()?.takeIf { it > 0f } ?: 1f
    private val highMax = bands.high.maxOrNull()?.takeIf { it > 0f } ?: 1f

    private fun at(array: FloatArray, peak: Float, t: Double): Float {
        if (t < from || t >= to || array.isEmpty()) return 0f
        val index = ((t - from) / (to - from) * array.size).toInt().coerceIn(0, array.size - 1)
        return (array[index] / peak).coerceIn(0f, 1f)
    }

    fun low(t: Double) = at(bands.low, lowMax, t)
    fun mid(t: Double) = at(bands.mid, midMax, t)
    fun high(t: Double) = at(bands.high, highMax, t)
}

private fun lanesOf(tool: MixTool): List<Lane> = Lane.values().filter { it.tool == tool }

/**
 * The editor for one join: the two songs' waveforms with the blend drawn over
 * them, the presets, and — under Custom — the five tools.
 *
 * Dragging the top strip slides the outgoing song under the blend window (where
 * on it the blend starts); dragging the bottom strip slides the incoming song
 * (which part of it comes in). Both pull onto the beats, and onto bar lines
 * when they are close to one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixEditorScreen(
    playlistId: String,
    from: Song,
    to: Song,
    onClose: () -> Unit,
    onPreview: (positionMs: Long, mix: ManualMix) -> Unit,
    onStopPreview: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val playlists by MixedPlaylists.playlists.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val revision by MixedAnalysis.revision.collectAsStateWithLifecycle()

    val stored = playlists.firstOrNull { it.id == playlistId }?.transitionBetween(from, to)
    val analysisA = MixedAnalysis.analysisOf(from.videoId)
    val analysisB = MixedAnalysis.analysisOf(to.videoId)
    val durationA = remember(from.videoId, analysisA) { analysisA?.duration?.takeIf { it > 1.0 } ?: MixedAnalysis.listedSeconds(from) }
    val durationB = remember(to.videoId, analysisB) { analysisB?.duration?.takeIf { it > 1.0 } ?: MixedAnalysis.listedSeconds(to) }
    val gridA = remember(analysisA) { analysisA?.let { BeatGrid(it) } }
    val gridB = remember(analysisB) { analysisB?.let { BeatGrid(it) } }

    var mix by remember { mutableStateOf(stored?.mix) }
    var dirty by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var barsMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var sheetTool by remember { mutableStateOf<MixTool?>(null) }
    var customTool by remember { mutableStateOf<MixTool?>(null) }
    var editingLane by remember { mutableStateOf<Lane?>(null) }

    // Nothing stored (should not happen from the page, but a backup edited by hand might do it): build one.
    LaunchedEffect(analysisA != null && analysisB != null) {
        if (mix == null && analysisA != null && analysisB != null) mix = MixedBuilder.build(from, to)
    }

    fun change(next: ManualMix) {
        val a = analysisA
        val b = analysisB
        var fixed = next
        if (a != null && b != null) fixed = MixedBuilder.withTempo(fixed, a.bpm, b.bpm)
        if (durationA > 0) fixed = MixedBuilder.clamp(fixed, durationA)
        mix = fixed
        dirty = true
    }

    var waveA by remember { mutableStateOf<WaveSlice?>(null) }
    var waveB by remember { mutableStateOf<WaveSlice?>(null) }
    LaunchedEffect(analysisA != null, durationA) {
        val uriA = from.localUri
        if (analysisA == null || durationA <= 0.0 || uriA == null) return@LaunchedEffect
        waveA = withContext(Dispatchers.IO) {
            val start = max(0.0, durationA - WAVE_SECONDS)
            val bins = ((durationA - start) * WAVE_BINS_PER_SECOND).toInt().coerceAtLeast(64)
            runCatching {
                SharedTrackAnalyzer.get(context).mixWaveform(from.videoId, Uri.parse(uriA), durationA, start, durationA, bins)
            }.getOrNull()?.let { WaveSlice(start, durationA, it) }
        }
    }
    LaunchedEffect(analysisB != null, durationB) {
        val uriB = to.localUri
        if (analysisB == null || durationB <= 0.0 || uriB == null) return@LaunchedEffect
        waveB = withContext(Dispatchers.IO) {
            val end = min(durationB, WAVE_SECONDS)
            val bins = (end * WAVE_BINS_PER_SECOND).toInt().coerceAtLeast(64)
            runCatching {
                SharedTrackAnalyzer.get(context).mixWaveform(to.videoId, Uri.parse(uriB), durationB, 0.0, end, bins)
            }.getOrNull()?.let { WaveSlice(0.0, end, it) }
        }
    }
    // While auditioning, edits are heard the next time the engine looks.
    LaunchedEffect(mix, previewing) {
        val m = mix
        if (previewing && m != null) MixedPlayback.setPreview(from.videoId, to.videoId, m)
    }

    BackHandler {
        when {
            sheetTool != null -> sheetTool = null
            editingLane != null -> { editingLane = null; customTool = null }
            dirty -> discard = true
            else -> onClose()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ---- top bar --------------------------------------------------------------
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (dirty) discard = true else onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            Spacer(Modifier.weight(1f))
            Box {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { moreMenu = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Reset to Auto") },
                        onClick = {
                            moreMenu = false
                            scope.launch { MixedBuilder.build(from, to)?.let { change(it) } }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove this transition") },
                        onClick = {
                            moreMenu = false
                            MixedPlaylists.setTransition(playlistId, from, to, MixTransition.NONE)
                            onClose()
                        },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.height(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = mix != null) {
                        mix?.let { MixedPlaylists.setTransition(playlistId, from, to, MixTransition(MixState.MANUAL, it)) }
                        onClose()
                    }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Save mix", color = GREEN, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        }

        val current = mix
        if (current == null || analysisA == null || analysisB == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Measuring the songs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        // ---- the graph ------------------------------------------------------------
        MixGraph(
            mix = current,
            gridA = gridA,
            gridB = gridB,
            durationA = durationA,
            durationB = durationB,
            waveA = waveA,
            waveB = waveB,
            editingLane = editingLane,
            previewing = previewing,
            onChange = { change(it) },
            onSnap = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            onPickLane = { lane, x ->
                customTool = lane.tool
                editingLane = lane
                // The point lands on the line where it was tapped; the line itself does not change shape.
                change(current.withCurve(lane, current.curve(lane).inserted(x).first))
            },
            onTogglePreview = {
                if (previewing) {
                    previewing = false
                    onStopPreview()
                } else {
                    previewing = true
                    onPreview(max(0L, ((current.outStartSec - PREVIEW_LEAD_SECONDS) * 1000).toLong()), current)
                }
            },
            barsMenu = barsMenu,
            onBarsMenu = { barsMenu = it },
        )

        // ---- everything below the graph scrolls ----------------------------------------
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val tool = customTool
            if (tool != null && editingLane != null) {
                LaneEditorBar(
                    tool = tool,
                    lane = editingLane!!,
                    mix = current,
                    onLane = { editingLane = it },
                    onEchoBeats = { change(current.copy(echoBeats = it, effectId = PresetCatalog.CUSTOM, composite = PresetCatalog.CUSTOM)) },
                    onDone = { editingLane = null; customTool = null },
                )
            } else {
                PresetsDivider()
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        PresetChip("Custom", selected = current.composite == PresetCatalog.CUSTOM) {
                            change(current.copy(composite = PresetCatalog.CUSTOM))
                        }
                    }
                    item {
                        PresetChip("Auto", selected = current.composite == "auto", sparkle = true) {
                            scope.launch {
                                MixedBuilder.build(from, to)?.let { auto ->
                                    // The blend's place stays where the listener put it; only the style goes back to Auto's.
                                    change(auto.copy(outStartSec = current.outStartSec, inCueSec = current.inCueSec, bars = current.bars))
                                    mix = mix?.copy(composite = "auto")
                                }
                            }
                        }
                    }
                    items(PresetCatalog.composites, key = { it.id }) { comp ->
                        PresetChip(comp.label, selected = current.composite == comp.id) {
                            change(PresetCatalog.applyComposite(comp.id, current))
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))

                if (current.composite == PresetCatalog.CUSTOM) {
                    MixTool.values().forEach { tool ->
                        ToolRow(tool, PresetCatalog.labelOf(tool, current)) { sheetTool = tool }
                    }
                }
                BeatmatchRow(
                    enabled = current.tempoMatch,
                    outBpm = analysisA.bpm,
                    inBpm = analysisB.bpm,
                    rate = current.incomingRate,
                    onToggle = { change(current.copy(tempoMatch = it)) },
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ---- the tool sheet -----------------------------------------------------------------
    sheetTool?.let { tool ->
        val current = mix
        if (current != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { sheetTool = null }, sheetState = sheetState) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(26.dp).background(Color(tool.argb), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text(tool.label, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                    if (tool != MixTool.LOOPING) {
                        TextButton(onClick = {
                            sheetTool = null
                            customTool = tool
                            editingLane = lanesOf(tool).first()
                        }) { Text("Customize", color = GREEN, fontWeight = FontWeight.SemiBold) }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    val selectedId = PresetCatalog.idOf(tool, current)
                    PresetCatalog.list(tool).forEach { preset ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                change(PresetCatalog.applyTool(tool, preset.id, current))
                                sheetTool = null
                            }.padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(preset.label, fontSize = 18.sp, modifier = Modifier.weight(1f))
                            if (preset.id == selectedId) Icon(Icons.Rounded.Check, contentDescription = null, tint = GREEN)
                        }
                    }
                    if (tool == MixTool.LOOPING) {
                        Text(
                            "Loops work in whole bars (4 beats), so the shortest is 4 beats. A loop turns the blend into a loop mix: the loop repeats while the tempo is brought together.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Discard changes?") },
            text = { Text("The changes to this transition haven't been saved.") },
            confirmButton = { TextButton(onClick = { discard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// graph
// ---------------------------------------------------------------------------------------------

@Composable
private fun MixGraph(
    mix: ManualMix,
    gridA: BeatGrid?,
    gridB: BeatGrid?,
    durationA: Double,
    durationB: Double,
    waveA: WaveSlice?,
    waveB: WaveSlice?,
    editingLane: Lane?,
    previewing: Boolean,
    onChange: (ManualMix) -> Unit,
    onSnap: () -> Unit,
    onPickLane: (Lane, Float) -> Unit,
    onTogglePreview: () -> Unit,
    barsMenu: Boolean,
    onBarsMenu: (Boolean) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentMix by rememberUpdatedState(mix)
    val currentLane by rememberUpdatedState(editingLane)
    val onChangeNow by rememberUpdatedState(onChange)
    val onSnapNow by rememberUpdatedState(onSnap)
    val onPickLaneNow by rememberUpdatedState(onPickLane)
    var dragNode by remember { mutableIntStateOf(-1) }

    // Raw positions while a deck is being dragged; the snapped value is what the mix gets.
    var rawOut by remember { mutableStateOf(0.0) }
    var rawIn by remember { mutableStateOf(0.0) }
    var lastSnapped by remember { mutableStateOf(Double.NaN) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(GRAPH_HEIGHT)
            .onSizeChanged { size = it },
    ) {
        val geometry = GraphGeometry(size.width.toFloat(), size.height.toFloat(), mix)
        val gestures = if (editingLane != null) {
            Modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos ->
                            val lane = currentLane ?: return@detectTapGestures
                            val m = currentMix
                            val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), m)
                            val curve = m.curve(lane)
                            if (g.hitNode(lane, curve, pos, 26.dp.toPx()) >= 0) return@detectTapGestures
                            // A tap near another line moves the editing to that line; near this one, or
                            // anywhere in its half of the graph, puts a new point where it was tapped.
                            val near = nearestLane(g, m, pos, 32.dp.toPx())
                            if (near != null && near != lane) {
                                onPickLaneNow(near, g.xToCurve(pos.x))
                            } else if (near == lane || g.inLaneBand(lane, pos)) {
                                val (next, _) = curve.inserted(g.xToCurve(pos.x), if (near == lane) null else g.yToValue(lane, pos.y))
                                onChangeNow(m.withCurve(lane, next))
                            }
                        },
                        onLongPress = { pos ->
                            val lane = currentLane ?: return@detectTapGestures
                            val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), currentMix)
                            val curve = currentMix.curve(lane)
                            val hit = g.hitNode(lane, curve, pos, 26.dp.toPx())
                            if (hit > 0 && hit < curve.points.lastIndex) onChangeNow(currentMix.withCurve(lane, curve.removed(hit)))
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val lane = currentLane ?: return@detectDragGestures
                            val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), currentMix)
                            dragNode = g.hitNode(lane, currentMix.curve(lane), pos, 30.dp.toPx())
                        },
                        onDragEnd = { dragNode = -1 },
                        onDragCancel = { dragNode = -1 },
                        onDrag = { change, _ ->
                            val lane = currentLane ?: return@detectDragGestures
                            if (dragNode < 0) return@detectDragGestures
                            change.consume()
                            val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), currentMix)
                            val next = currentMix.curve(lane).moved(dragNode, g.xToCurve(change.position.x), g.yToValue(lane, change.position.y))
                            onChangeNow(currentMix.withCurve(lane, next))
                        },
                    )
                }
        } else {
            Modifier.pointerInput(Unit) {
                // Tap a line to edit it: a point appears on it where it was tapped, ready to be moved.
                detectTapGestures(
                    onTap = { pos ->
                        val m = currentMix
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), m)
                        nearestLane(g, m, pos, 32.dp.toPx())?.let { onPickLaneNow(it, g.xToCurve(pos.x)) }
                    },
                )
            }.pointerInput(durationA, durationB) {
                var deck = 0
                detectDragGestures(
                    onDragStart = { pos ->
                        deck = if (pos.y < size.height / 2f) 0 else 1
                        rawOut = currentMix.outStartSec
                        rawIn = currentMix.inCueSec
                        lastSnapped = Double.NaN
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val m = currentMix
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), m)
                        if (g.pxPerSec <= 0f) return@detectDragGestures
                        if (deck == 0) {
                            rawOut -= amount.x / g.pxPerSec
                            val snapped = if (m.tempoMatch) gridA?.let { magnetBar(it, rawOut) } ?: rawOut else rawOut
                            val clamped = snapped.coerceIn(0.0, max(0.0, durationA - m.lengthSec))
                            if (clamped != m.outStartSec) {
                                if (clamped != lastSnapped && snapped != rawOut) onSnapNow()
                                lastSnapped = clamped
                                onChangeNow(m.copy(outStartSec = clamped, composite = m.composite))
                            }
                        } else {
                            rawIn -= amount.x / g.pxPerSec * m.incomingRate
                            val snapped = if (m.tempoMatch) gridB?.let { magnetBar(it, rawIn) } ?: rawIn else rawIn
                            val clamped = snapped.coerceIn(0.0, max(0.0, durationB - m.lengthSec * m.incomingRate - 1.0))
                            if (clamped != m.inCueSec) {
                                if (clamped != lastSnapped && snapped != rawIn) onSnapNow()
                                lastSnapped = clamped
                                onChangeNow(m.copy(inCueSec = clamped, composite = m.composite))
                            }
                        }
                    },
                )
            }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            drawGraph(geometry, mix, gridA, gridB, waveA, waveB, editingLane)
        }

        // The audition button, in the gap between the decks.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(width = 64.dp, height = 36.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF3A3A40))
                .clickable(onClick = onTogglePreview),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = if (previewing) "Stop" else "Preview",
                tint = Color.White,
            )
        }

        // "8 bars", attached to the bottom of the window.
        val barsLabelX = with(androidx.compose.ui.platform.LocalDensity.current) { ((geometry.left + geometry.right) / 2f).toDp() }
        Box(Modifier.align(Alignment.BottomStart).padding(start = (barsLabelX - 44.dp).coerceAtLeast(0.dp))) {
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF2E2E33)).clickable { onBarsMenu(true) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${mix.bars} bars", fontSize = 15.sp)
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = barsMenu, onDismissRequest = { onBarsMenu(false) }) {
                BAR_OPTIONS.forEach { bars ->
                    DropdownMenuItem(
                        text = { Text("$bars bars") },
                        onClick = {
                            onBarsMenu(false)
                            onChangeNow(currentMix.copy(bars = bars, composite = currentMix.composite))
                        },
                    )
                }
            }
        }
    }
}

/** Where everything sits in the graph, for drawing it and for finding what a finger touched. */
private class GraphGeometry(val width: Float, val height: Float, val mix: ManualMix) {
    val left = width * 0.17f
    val right = width * 0.83f
    val pxPerSec: Float = if (mix.lengthSec > 0) (right - left) / mix.lengthSec.toFloat() else 0f
    val gap = height * 0.12f
    val deckHeight = (height - gap - height * 0.1f) / 2f
    val topDeckTop = 0f
    val bottomDeckTop = deckHeight + gap

    fun bandTop(lane: Lane) = if (lane.outgoing) topDeckTop else bottomDeckTop
    private val pad = 10f
    fun laneY(lane: Lane, value: Float): Float = bandTop(lane) + pad + (1f - value) * (deckHeight - 2 * pad)
    fun curveX(x: Float): Float = left + x * (right - left)
    fun xToCurve(px: Float): Float = ((px - left) / (right - left)).coerceIn(0f, 1f)
    fun yToValue(lane: Lane, py: Float): Float = (1f - (py - bandTop(lane) - pad) / (deckHeight - 2 * pad)).coerceIn(0f, 1f)
    fun inLaneBand(lane: Lane, pos: Offset): Boolean = pos.y in bandTop(lane)..(bandTop(lane) + deckHeight) && pos.x in left..right

    fun hitNode(lane: Lane, curve: Curve, pos: Offset, radius: Float): Int {
        var best = -1
        var bestDistance = radius
        curve.points.forEachIndexed { i, p ->
            val d = (Offset(curveX(p.x), laneY(lane, p.y)) - pos).getDistance()
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best
    }
}

/** Whether a lane is at rest, and so not drawn: bass and filters fully open, the echo send at nothing. */
private fun isAtRest(lane: Lane, curve: Curve): Boolean = when (lane) {
    Lane.ECHO_OUT -> curve.points.all { it.y <= 0.05f }
    Lane.VOLUME_OUT, Lane.VOLUME_IN -> false
    else -> curve.points.all { it.y >= 0.95f }
}

/** The drawn lane whose line passes closest to [pos], when one passes within [radius]. */
private fun nearestLane(g: GraphGeometry, mix: ManualMix, pos: Offset, radius: Float): Lane? {
    if (pos.x < g.left - radius || pos.x > g.right + radius) return null
    val fraction = g.xToCurve(pos.x)
    var best: Lane? = null
    var bestDistance = radius
    for (lane in Lane.values()) {
        val curve = mix.curve(lane)
        if (isAtRest(lane, curve)) continue
        val distance = abs(g.laneY(lane, curve.at(fraction)) - pos.y)
        if (distance < bestDistance) {
            bestDistance = distance
            best = lane
        }
    }
    return best
}

/** Pulls [seconds] onto a bar line when it is within half a beat of one, else onto a beat when within a third of one. */
private fun magnetBar(grid: BeatGrid, seconds: Double): Double {
    val bar = grid.snapToBar(seconds)
    if (abs(bar - seconds) <= grid.beatSec * 0.5) return bar
    return grid.magnet(seconds, grid.beatSec * 0.33)
}

private fun DrawScope.drawGraph(
    g: GraphGeometry,
    mix: ManualMix,
    gridA: BeatGrid?,
    gridB: BeatGrid?,
    waveA: WaveSlice?,
    waveB: WaveSlice?,
    editingLane: Lane?,
) {
    if (g.width <= 0f) return
    val step = 3.dp.toPx()
    val rate = mix.incomingRate

    // Waveforms. The window is full strength, what lies outside it is dimmed.
    fun deck(top: Float, timeAt: (Float) -> Double, wave: WaveSlice?, grid: BeatGrid?, windowStartSec: Double, toWall: (Double) -> Float) {
        val cy = top + g.deckHeight / 2f
        val half = g.deckHeight / 2f * 0.92f
        if (wave != null) {
            var x = 0f
            while (x < g.width) {
                val t = timeAt(x)
                val alpha = if (x in g.left..g.right) 1f else 0.4f
                val low = wave.low(t)
                val mid = wave.mid(t)
                val high = wave.high(t)
                if (low > 0f || mid > 0f || high > 0f) {
                    drawLine(LOW_COLOR.copy(alpha = alpha), Offset(x, cy - low * half), Offset(x, cy + low * half), strokeWidth = step * 0.7f)
                    drawLine(MID_COLOR.copy(alpha = alpha), Offset(x, cy - mid * half * 0.8f), Offset(x, cy + mid * half * 0.8f), strokeWidth = step * 0.7f)
                    drawLine(HIGH_COLOR.copy(alpha = alpha), Offset(x, cy - high * half * 0.45f), Offset(x, cy + high * half * 0.45f), strokeWidth = step * 0.7f)
                }
                x += step
            }
        } else {
            drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, cy), Offset(g.width, cy), strokeWidth = 2f)
        }
        // Beat lines inside the window.
        if (grid != null) {
            val first = (windowStartSec / grid.beatSec).toLong() - 1
            var n = first
            while (true) {
                val t = n * grid.beatSec
                val x = toWall(t)
                if (x > g.right) break
                if (x >= g.left) {
                    val bar = n % 4L == 0L
                    drawLine(Color.White.copy(alpha = if (bar) 0.22f else 0.09f), Offset(x, top), Offset(x, top + g.deckHeight), strokeWidth = if (bar) 2f else 1f)
                }
                n++
            }
        }
    }

    // The window itself behind the waveforms' bright part.
    drawRoundRect(
        WINDOW,
        topLeft = Offset(g.left, 0f),
        size = Size(g.right - g.left, g.height - g.height * 0.1f),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )

    deck(
        top = g.topDeckTop,
        timeAt = { x -> mix.outStartSec + (x - g.left) / g.pxPerSec },
        wave = waveA,
        grid = gridA,
        windowStartSec = mix.outStartSec,
        toWall = { t -> g.left + ((t - mix.outStartSec) * g.pxPerSec).toFloat() },
    )
    deck(
        top = g.bottomDeckTop,
        timeAt = { x -> mix.inCueSec + (x - g.left) / g.pxPerSec * rate },
        wave = waveB,
        grid = gridB,
        windowStartSec = mix.inCueSec,
        toWall = { t -> g.left + ((t - mix.inCueSec) / rate * g.pxPerSec).toFloat() },
    )

    // Loop brackets.
    if (mix.endingLoopBars > 0) {
        val len = (mix.endingLoopBars * 4 * mix.beatSec * g.pxPerSec).toFloat()
        drawRoundRect(
            Color(0xFFFF9F0A),
            topLeft = Offset(g.left, g.topDeckTop + 2f),
            size = Size(min(len, g.right - g.left), g.deckHeight - 4f),
            cornerRadius = CornerRadius(6.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
    if (mix.startingLoopBars > 0) {
        val beat = gridB?.beatSec ?: mix.beatSec
        val len = (mix.startingLoopBars * 4 * beat / rate * g.pxPerSec).toFloat()
        drawRoundRect(
            Color(0xFFFF9F0A),
            topLeft = Offset(g.left, g.bottomDeckTop + 2f),
            size = Size(min(len, g.right - g.left), g.deckHeight - 4f),
            cornerRadius = CornerRadius(6.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
    }

    // Window border.
    drawRoundRect(
        Color.White.copy(alpha = 0.28f),
        topLeft = Offset(g.left, 0f),
        size = Size(g.right - g.left, g.height - g.height * 0.1f),
        cornerRadius = CornerRadius(18.dp.toPx()),
        style = Stroke(width = 1.5.dp.toPx()),
    )

    // Lanes. The volume lanes are always there; the others only when they do something.
    Lane.values().forEach { lane ->
        val curve = mix.curve(lane)
        val flat = when (lane) {
            Lane.ECHO_OUT -> curve.points.all { it.y <= 0.05f }
            Lane.VOLUME_OUT, Lane.VOLUME_IN -> false
            else -> curve.points.all { it.y >= 0.95f }
        }
        val editing = lane == editingLane
        if (flat && !editing) return@forEach
        val color = Color(lane.tool.argb)
        val path = Path()
        curve.points.forEachIndexed { i, p ->
            val x = g.curveX(p.x)
            val y = g.laneY(lane, p.y)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color.copy(alpha = if (editingLane == null || editing) 1f else 0.3f),
            style = Stroke(width = if (editing) 3.5.dp.toPx() else 2.5.dp.toPx()),
        )
        if (editing) {
            curve.points.forEach { p ->
                val c = Offset(g.curveX(p.x), g.laneY(lane, p.y))
                drawCircle(Color.Black, radius = 9.dp.toPx(), center = c)
                drawCircle(color, radius = 9.dp.toPx(), center = c, style = Stroke(width = 2.5.dp.toPx()))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// controls
// ---------------------------------------------------------------------------------------------

@Composable
private fun PresetsDivider() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text("Presets", Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp)
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, sparkle: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color.White else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sparkle) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 18.sp, color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToolRow(tool: MixTool, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(24.dp).background(Color(tool.argb), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(14.dp))
        Text(tool.label, fontSize = 22.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BeatmatchRow(enabled: Boolean, outBpm: Double, inBpm: Double, rate: Double, onToggle: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Beatmatching", fontSize = 22.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = Color.White),
            )
        }
        val note = when {
            !enabled -> "Off: dragging doesn't snap to the beat, and the next song plays at its own tempo."
            abs(rate - 1.0) < 0.001 && MixTempo.match(outBpm, inBpm).matched ->
                "Dragging snaps to the beat. Tempos already agree (${outBpm.roundToInt()} and ${inBpm.roundToInt()} BPM, same or double/half), so nothing is stretched."
            abs(rate - 1.0) < 0.001 ->
                "Too far apart to match (${outBpm.roundToInt()} → ${inBpm.roundToInt()} BPM). The next song plays at its own tempo."
            else -> {
                val percent = (rate - 1.0) * 100.0
                "The next song is ${if (percent >= 0) "sped up" else "slowed down"} ${"%.1f".format(abs(percent))}% to ${outBpm.roundToInt()} BPM during the blend, then returns to normal."
            }
        }
        Text(note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LaneEditorBar(
    tool: MixTool,
    lane: Lane,
    mix: ManualMix,
    onLane: (Lane) -> Unit,
    onEchoBeats: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(24.dp).background(Color(tool.argb), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            Text("Customize ${tool.label}", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done", color = GREEN, fontWeight = FontWeight.Bold) }
        }
        val lanes = lanesOf(tool)
        if (lanes.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lanes, key = { it.name }) { l ->
                    PresetChip(l.label, selected = l == lane) { onLane(l) }
                }
            }
        }
        if (tool == MixTool.EFFECTS) {
            Text("Echo length", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(0.5f, 1f, 2f, 4f), key = { it }) { beats ->
                    PresetChip("${if (beats < 1f) "½" else beats.toInt().toString()} beat", selected = mix.echoBeats == beats) { onEchoBeats(beats) }
                }
            }
        }
        Text(
            "Tap the line to add a point, drag a point to move it, press and hold a point to remove it.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val WAVE_SECONDS = 150.0
private const val WAVE_BINS_PER_SECOND = 8
private const val PREVIEW_LEAD_SECONDS = 6.0
