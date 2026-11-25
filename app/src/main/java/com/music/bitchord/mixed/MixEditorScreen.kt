@file:OptIn(ExperimentalFoundationApi::class)

package com.music.bitchord.mixed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.WaveColors
import com.music.bitchord.ui.player.rememberWaveColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val GREEN = Color(0xFF1ED760)
private val WINDOW = Color(0xFF26262B)
private val GRAPH_HEIGHT = 330.dp
private val BAR_OPTIONS = listOf(1, 2, 4, 8, 16, 32)
private val CONTROL_WIDTH = 112.dp
private val CONTROL_HEIGHT = 36.dp

private fun lanesOf(tool: MixTool): List<Lane> = Lane.values().filter { it.tool == tool }

private enum class Transport { STOPPED, PLAYING, PAUSED }


/**
 * The editor for one join: the two songs' waveforms with the blend drawn over
 * them, the presets, and — under Custom — the five tools.
 *
 * Dragging the top strip slides the outgoing song under the blend window (where
 * on it the blend starts); dragging the bottom strip slides the incoming song
 * (which part of it comes in). Both pull onto the beat grid when Beatmatching is
 * on, and onto bar lines when the bar magnet is. A tap or drag in the gap
 * between the decks moves the cursor.
 *
 * The play button auditions the blend over what the waveforms show: from their
 * left edge, to their right. Tapping it again pauses and resumes; holding it
 * starts over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixEditorScreen(
    playlistId: String,
    from: Song,
    to: Song,
    onClose: () -> Unit,
    startPreview: (positionMs: Long, mix: ManualMix) -> Unit,
    pausePreview: () -> Unit,
    resumePreview: () -> Unit,
    /** The song now playing, its position in ms, and whether it is running — for the moving cursor. */
    playhead: () -> Triple<String?, Long, Boolean>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val playlists by MixedPlaylists.playlists.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val revision by MixedAnalysis.revision.collectAsStateWithLifecycle()
    val loopNote by MixedPlayback.loopNote.collectAsStateWithLifecycle()

    val stored = playlists.firstOrNull { it.id == playlistId }?.transitionBetween(from, to)
    val analysisA = MixedAnalysis.analysisOf(from.videoId)
    val analysisB = MixedAnalysis.analysisOf(to.videoId)
    val durationA = remember(from.videoId, analysisA) { analysisA?.duration?.takeIf { it > 1.0 } ?: MixedAnalysis.listedSeconds(from) }
    val durationB = remember(to.videoId, analysisB) { analysisB?.duration?.takeIf { it > 1.0 } ?: MixedAnalysis.listedSeconds(to) }
    val gridA = remember(analysisA) { analysisA?.let { BeatGrid(it) } }
    val gridB = remember(analysisB) { analysisB?.let { BeatGrid(it) } }
    val colorsA = rememberWaveColors(from.thumbnailUrl)
    val colorsB = rememberWaveColors(to.thumbnailUrl)

    var mix by remember { mutableStateOf(stored?.mix) }
    var dirty by remember { mutableStateOf(false) }
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

    // ---- the waveforms ---------------------------------------------------------------------------
    // Decoded once, kept in MixWaveCache for as long as the app lives, and decoded again only when the
    // blend is moved far enough along its song that what is on screen is no longer in the slice.
    var waveA by remember { mutableStateOf<WaveSlice?>(null) }
    var waveB by remember { mutableStateOf<WaveSlice?>(null) }
    var triedA by remember { mutableStateOf(false) }
    var triedB by remember { mutableStateOf(false) }
    val outKey = ((mix?.outStartSec ?: 0.0) / 10).toInt()
    val inKey = ((mix?.inCueSec ?: 0.0) / 10).toInt()
    val barsKey = mix?.bars ?: 0
    LaunchedEffect(analysisA != null, durationA, outKey, barsKey, mix != null) {
        val m = mix ?: return@LaunchedEffect
        if (analysisA == null || durationA <= 0.0) return@LaunchedEffect
        val lead = m.lengthSec * VIEW_LEAD_FRACTION
        val needFrom = m.outStartSec - lead - 4.0
        val needTo = m.outStartSec + m.lengthSec + lead + 4.0
        if (!MixWaveCache.covers(waveA, needFrom, needTo, durationA)) {
            MixWaveCache.load(context, from, durationA, needFrom, needTo)?.let { waveA = it }
        }
        triedA = true
    }
    LaunchedEffect(analysisB != null, durationB, inKey, barsKey, mix != null) {
        val m = mix ?: return@LaunchedEffect
        if (analysisB == null || durationB <= 0.0) return@LaunchedEffect
        val lead = m.lengthSec * VIEW_LEAD_FRACTION * m.incomingRate
        val needFrom = m.inCueSec - lead - 4.0
        val needTo = m.inCueSec + m.lengthSec * m.incomingRate + lead + 4.0
        if (!MixWaveCache.covers(waveB, needFrom, needTo, durationB)) {
            MixWaveCache.load(context, to, durationB, needFrom, needTo)?.let { waveB = it }
        }
        triedB = true
    }
    val loading = mix != null && analysisA != null && analysisB != null && !(triedA && triedB)

    // ---- the audition ------------------------------------------------------------------------------
    // Seconds from the start of the blend; NaN is "at the start of what the waveforms show".
    var transport by remember { mutableStateOf(Transport.STOPPED) }
    var cursorT by remember { mutableStateOf(Double.NaN) }
    var resumeT by remember { mutableStateOf<Double?>(null) }
    var startedAtMs by remember { mutableStateOf(0L) }
    var settled by remember { mutableStateOf(true) }
    val mixNow by rememberUpdatedState(mix)

    fun leadOf(m: ManualMix) = m.lengthSec * VIEW_LEAD_FRACTION

    fun startFrom(requestedT: Double) {
        val m = mix ?: return
        // The song can only be started before the blend: the engine arms it from there.
        val t = requestedT.coerceIn(-leadOf(m), -MIN_PRE_BLEND_SECONDS)
        val positionSec = max(0.0, m.outStartSec + t)
        cursorT = positionSec - m.outStartSec
        resumeT = null
        startedAtMs = (positionSec * 1000).toLong()
        settled = false
        transport = Transport.PLAYING
        startPreview(startedAtMs, m)
    }

    fun togglePlay() {
        val m = mix ?: return
        when (transport) {
            Transport.STOPPED -> startFrom(resumeT ?: -leadOf(m))
            Transport.PLAYING -> { pausePreview(); transport = Transport.PAUSED }
            Transport.PAUSED -> { resumePreview(); transport = Transport.PLAYING }
        }
    }

    fun restart() {
        val m = mix ?: return
        resumeT = null
        startFrom(-leadOf(m))
    }

    fun scrub(t: Double) {
        val m = mix ?: return
        val clamped = t.coerceIn(-leadOf(m), m.lengthSec + leadOf(m))
        if (transport == Transport.PLAYING) {
            startFrom(clamped)
        } else {
            if (transport == Transport.PAUSED) pausePreview()
            transport = Transport.STOPPED
            cursorT = clamped
            resumeT = clamped
        }
    }

    // While auditioning, edits are what the engine arms next.
    LaunchedEffect(mix) {
        val m = mix
        if (transport != Transport.STOPPED && m != null) MixedPlayback.setPreview(from.videoId, to.videoId, m)
    }

    // The cursor follows the playing song. A loop mix is rendered apart from the player, whose own position
    // no longer says where the blend is, so once the blend has begun the cursor runs on the clock instead.
    LaunchedEffect(transport) {
        if (transport != Transport.PLAYING) return@LaunchedEffect
        var wallRef = 0L
        var haveRef = false
        val began = SystemClock.elapsedRealtime()
        var lastNow = began
        while (true) {
            val m = mixNow ?: break
            val (id, position, playing) = playhead()
            val now = SystemClock.elapsedRealtime()
            if (!settled) {
                // Until the player reports the new queue, the old one's position is not the cursor's business.
                val arrived = (id == from.videoId && abs(position - startedAtMs) < 2500L) || id == to.videoId
                if (arrived || now - began > 3000L) settled = true else { delay(33); continue }
            }
            when (id) {
                from.videoId -> {
                    val into = position / 1000.0 - m.outStartSec
                    if (m.usesLoops && into >= 0.0) {
                        if (!haveRef) {
                            wallRef = now - (into * 1000).toLong()
                            haveRef = true
                        }
                        if (!playing) wallRef += now - lastNow
                        cursorT = (now - wallRef) / 1000.0
                    } else {
                        haveRef = false
                        cursorT = into
                    }
                }
                to.videoId -> cursorT = (position / 1000.0 - m.inCueSec) / m.incomingRate.coerceAtLeast(0.1)
                else -> { transport = Transport.STOPPED; cursorT = Double.NaN; break }
            }
            // The right edge of what the waveforms show: the audition is over.
            if (cursorT >= m.lengthSec + leadOf(m)) {
                pausePreview()
                transport = Transport.STOPPED
                cursorT = Double.NaN
                resumeT = null
                break
            }
            lastNow = now
            delay(33)
        }
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
            colorsA = colorsA,
            colorsB = colorsB,
            editingLane = editingLane,
            playing = transport == Transport.PLAYING,
            loading = loading,
            cursorSeconds = (if (cursorT.isNaN()) -leadOf(current) else cursorT).toFloat(),
            onChange = { change(it) },
            onSnap = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            onPickLane = { lane, x ->
                customTool = lane.tool
                editingLane = lane
                // The point lands on the line where it was tapped; the line itself does not change shape.
                change(current.withCurve(lane, current.curve(lane).inserted(x).first))
            },
            onTogglePlay = { togglePlay() },
            onRestart = { restart() },
            onScrub = { scrub(it) },
            barsMenu = barsMenu,
            onBarsMenu = { barsMenu = it },
        )

        // ---- everything below the graph scrolls ----------------------------------------
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                        if (tool == MixTool.LOOPING && current.usesLoops && loopNote != null) {
                            Text(
                                "The loop can't play here: $loopNote. This blend plays without it.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                            )
                        }
                    }
                }
                BeatmatchRow(
                    mix = current,
                    outBpm = analysisA.bpm,
                    inBpm = analysisB.bpm,
                    onBeatmatch = { change(current.copy(tempoMatch = it)) },
                    onBarMagnet = { change(current.copy(barMagnet = it)) },
                )
                Spacer(Modifier.height(32.dp))
            }
            }
            // Until the waveforms are drawn there is nothing to edit.
            if (loading) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        },
                )
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
                            "The first 1, 2, 4 or 8 beats of the blend on the outgoing song repeat all the way through it, so the song is that much longer in the mix. A loop locks the two songs to one tempo, and the renderer has no low pass or echo, so those two lines are silent while one plays.",
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
    colorsA: WaveColors,
    colorsB: WaveColors,
    editingLane: Lane?,
    playing: Boolean,
    loading: Boolean,
    cursorSeconds: Float,
    onChange: (ManualMix) -> Unit,
    onSnap: () -> Unit,
    onPickLane: (Lane, Float) -> Unit,
    onTogglePlay: () -> Unit,
    onRestart: () -> Unit,
    onScrub: (Double) -> Unit,
    barsMenu: Boolean,
    onBarsMenu: (Boolean) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentMix by rememberUpdatedState(mix)
    val currentLane by rememberUpdatedState(editingLane)
    val onChangeNow by rememberUpdatedState(onChange)
    val onSnapNow by rememberUpdatedState(onSnap)
    val onPickLaneNow by rememberUpdatedState(onPickLane)
    val onScrubNow by rememberUpdatedState(onScrub)
    var dragNode by remember { mutableIntStateOf(-1) }

    // Raw positions while a deck is being dragged; the snapped value is what the mix gets.
    var rawOut by remember { mutableStateOf(0.0) }
    var rawIn by remember { mutableStateOf(0.0) }
    var lastSnapped by remember { mutableStateOf(Double.NaN) }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxWidth()
            .height(GRAPH_HEIGHT)
            .onSizeChanged { size = it },
    ) {
        val geometry = GraphGeometry(size.width.toFloat(), size.height.toFloat(), mix)

        // The gap between the decks moves the cursor, whatever else is going on.
        val scrubbing = Modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), currentMix)
                        if (g.inGap(pos.y)) onScrubNow(((pos.x - g.left) / g.pxPerSec).toDouble())
                    },
                )
            }
            .pointerInput(Unit) {
                var active = false
                detectDragGestures(
                    onDragStart = { pos ->
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), currentMix)
                        active = g.inGap(pos.y)
                    },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                    onDrag = { change, _ ->
                        if (!active) return@detectDragGestures
                        change.consume()
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), currentMix)
                        if (g.pxPerSec > 0f) onScrubNow(((change.position.x - g.left) / g.pxPerSec).toDouble())
                    },
                )
            }

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
                var deck = -1
                detectDragGestures(
                    onDragStart = { pos ->
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), currentMix)
                        deck = when {
                            g.inGap(pos.y) -> -1
                            pos.y < size.height / 2f -> 0
                            else -> 1
                        }
                        rawOut = currentMix.outStartSec
                        rawIn = currentMix.inCueSec
                        lastSnapped = Double.NaN
                    },
                    onDrag = { change, amount ->
                        if (deck < 0) return@detectDragGestures
                        change.consume()
                        val m = currentMix
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), m)
                        if (g.pxPerSec <= 0f) return@detectDragGestures
                        if (deck == 0) {
                            rawOut -= amount.x / g.pxPerSec
                            val snapped = gridA?.let { snapTime(it, rawOut, m.tempoMatch, m.barMagnet) } ?: rawOut
                            val clamped = snapped.coerceIn(0.0, max(0.0, durationA - m.lengthSec))
                            if (clamped != m.outStartSec) {
                                if (clamped != lastSnapped && snapped != rawOut) onSnapNow()
                                lastSnapped = clamped
                                onChangeNow(m.copy(outStartSec = clamped))
                            }
                        } else {
                            rawIn -= amount.x / g.pxPerSec * m.incomingRate
                            val snapped = gridB?.let { snapTime(it, rawIn, m.tempoMatch, m.barMagnet) } ?: rawIn
                            val clamped = snapped.coerceIn(0.0, max(0.0, durationB - m.lengthSec * m.incomingRate - 1.0))
                            if (clamped != m.inCueSec) {
                                if (clamped != lastSnapped && snapped != rawIn) onSnapNow()
                                lastSnapped = clamped
                                onChangeNow(m.copy(inCueSec = clamped))
                            }
                        }
                    },
                )
            }
        }

        Canvas(Modifier.fillMaxSize().then(scrubbing).then(gestures)) {
            drawGraph(geometry, mix, gridA, gridB, waveA, waveB, colorsA, colorsB, editingLane)
            drawCursor(geometry, cursorSeconds)
            if (loading) drawRect(Color.Black.copy(alpha = 0.55f))
        }

        // The audition button: in the gap between the decks, or — while a line is being edited, so it is
        // not in the way of the line — down where the bars label is. It turns while the waveforms load.
        val playButton: @Composable (Modifier) -> Unit = { modifier ->
            Box(
                modifier
                    .size(width = CONTROL_WIDTH, height = CONTROL_HEIGHT)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF3A3A40))
                    .combinedClickable(enabled = !loading, onClick = onTogglePlay, onLongClick = onRestart),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
            }
        }
        if (editingLane == null) {
            val gapCentreDp = with(density) { geometry.gapCentre.toDp() }
            playButton(Modifier.align(Alignment.TopCenter).offset(y = gapCentreDp - CONTROL_HEIGHT / 2))
        }

        // "8 bars", attached to the bottom of the window — or the play button, in its place, while a line is edited.
        val centreDp = with(density) { ((geometry.left + geometry.right) / 2f).toDp() }
        Box(Modifier.align(Alignment.BottomStart).padding(start = (centreDp - CONTROL_WIDTH / 2).coerceAtLeast(0.dp))) {
            if (editingLane != null) {
                playButton(Modifier)
            } else {
                Row(
                    Modifier
                        .size(width = CONTROL_WIDTH, height = CONTROL_HEIGHT)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2E2E33))
                        .clickable { onBarsMenu(true) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${mix.bars} bars", fontSize = 15.sp, color = Color.White)
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = barsMenu, onDismissRequest = { onBarsMenu(false) }) {
                    BAR_OPTIONS.forEach { bars ->
                        DropdownMenuItem(
                            text = { Text("$bars bars") },
                            onClick = {
                                onBarsMenu(false)
                                onChangeNow(currentMix.copy(bars = bars))
                            },
                        )
                    }
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

    /** The middle of the gap between the decks, where the play button sits. */
    val gapCentre = deckHeight + gap / 2f

    fun bandTop(lane: Lane) = if (lane.outgoing) topDeckTop else bottomDeckTop
    private val pad = 10f
    fun laneY(lane: Lane, value: Float): Float = bandTop(lane) + pad + (1f - value) * (deckHeight - 2 * pad)
    fun curveX(x: Float): Float = left + x * (right - left)
    fun xToCurve(px: Float): Float = ((px - left) / (right - left)).coerceIn(0f, 1f)
    fun yToValue(lane: Lane, py: Float): Float = (1f - (py - bandTop(lane) - pad) / (deckHeight - 2 * pad)).coerceIn(0f, 1f)
    fun inGap(y: Float): Boolean = y in (deckHeight - 6f)..(bottomDeckTop + 6f)
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

/**
 * Where a dragged position lands: on the nearest bar line when the bar magnet is on, else on a beat when
 * Beatmatching is. With both on, a bar line wins when it is within half a beat and the beat grid otherwise.
 */
private fun snapTime(grid: BeatGrid, seconds: Double, beatMagnet: Boolean, barMagnet: Boolean): Double {
    if (barMagnet) {
        val bar = grid.snapToBar(seconds)
        if (abs(bar - seconds) <= grid.beatSec * (if (beatMagnet) 0.5 else 2.0)) return bar
    }
    return if (beatMagnet) grid.magnet(seconds, grid.beatSec * 0.33) else seconds
}

private fun DrawScope.drawGraph(
    g: GraphGeometry,
    mix: ManualMix,
    gridA: BeatGrid?,
    gridB: BeatGrid?,
    waveA: WaveSlice?,
    waveB: WaveSlice?,
    colorsA: WaveColors,
    colorsB: WaveColors,
    editingLane: Lane?,
) {
    if (g.width <= 0f) return
    val columnStep = 2.dp.toPx()
    val rate = mix.incomingRate
    val loopLen = mix.loopBeats * mix.beatSec

    // The window itself, behind the waveforms' bright part.
    drawRoundRect(
        WINDOW,
        topLeft = Offset(g.left, 0f),
        size = Size(g.right - g.left, g.height - g.height * 0.1f),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )

    // A deck's waveform, one bar per column of the screen: each bar is the loudest of the bins under it, so
    // a long blend packs more waveform into the same width instead of drawing thicker, jumping bars.
    fun deck(
        top: Float,
        wave: WaveSlice?,
        colors: WaveColors,
        grid: BeatGrid?,
        timeAt: (Float) -> Double,
        secondsPerColumn: Double,
        windowStartSec: Double,
        toWall: (Double) -> Float,
    ) {
        val cy = top + g.deckHeight / 2f
        val half = g.deckHeight / 2f * 0.86f
        if (wave != null) {
            var x = 0f
            while (x < g.width) {
                val t0 = timeAt(x)
                val t1 = t0 + secondsPerColumn
                val alpha = if (x in g.left..g.right) 1f else 0.4f
                val low = wave.low(t0, t1) * half
                val mid = wave.mid(t0, t1) * half
                val high = wave.high(t0, t1) * half
                val w = columnStep + 0.6f
                if (low > 0f) drawRect(colors.low.copy(alpha = alpha), Offset(x, cy - low), Size(w, low * 2f))
                if (mid > 0f) drawRect(colors.mid.copy(alpha = alpha), Offset(x, cy - mid), Size(w, mid * 2f))
                if (high > 0f) drawRect(colors.high.copy(alpha = alpha), Offset(x, cy - high), Size(w, high * 2f))
                x += columnStep
            }
        } else {
            drawLine(colors.mid.copy(alpha = 0.35f), Offset(0f, cy), Offset(g.width, cy), strokeWidth = 1.dp.toPx())
        }
        // Beat lines inside the window.
        if (grid != null) {
            var n = (windowStartSec / grid.beatSec).toLong() - 1
            while (true) {
                val x = toWall(n * grid.beatSec)
                if (x > g.right) break
                if (x >= g.left) {
                    val bar = n % 4L == 0L
                    drawLine(Color.White.copy(alpha = if (bar) 0.14f else 0.06f), Offset(x, top), Offset(x, top + g.deckHeight), strokeWidth = 1.dp.toPx())
                }
                n++
            }
        }
    }

    deck(
        top = g.topDeckTop,
        wave = waveA,
        colors = colorsA,
        grid = gridA,
        // With a loop, the blend shows the looped beats copied one after another: the outgoing song is that much longer.
        timeAt = { x ->
            val into = ((x - g.left) / g.pxPerSec).toDouble()
            if (loopLen > 0.0 && into >= 0.0) mix.outStartSec + into % loopLen else mix.outStartSec + into
        },
        secondsPerColumn = columnStep / g.pxPerSec.toDouble(),
        windowStartSec = mix.outStartSec,
        toWall = { t -> g.left + ((t - mix.outStartSec) * g.pxPerSec).toFloat() },
    )
    deck(
        top = g.bottomDeckTop,
        wave = waveB,
        colors = colorsB,
        grid = gridB,
        timeAt = { x -> mix.inCueSec + ((x - g.left) / g.pxPerSec).toDouble() * rate },
        secondsPerColumn = columnStep / g.pxPerSec.toDouble() * rate,
        windowStartSec = mix.inCueSec,
        toWall = { t -> g.left + ((t - mix.inCueSec) / rate * g.pxPerSec).toFloat() },
    )

    // Where each copy of the loop begins.
    if (loopLen > 0.0) {
        var k = 0
        while (k < 256) {
            val x = g.left + (k * loopLen * g.pxPerSec).toFloat()
            if (x >= g.right) break
            drawLine(
                Color(0xFFFF9F0A),
                Offset(x, g.topDeckTop),
                Offset(x, g.topDeckTop + g.deckHeight),
                strokeWidth = if (k == 0) 3.dp.toPx() else 1.5.dp.toPx(),
            )
            k++
        }
    }

    // Lanes. The volume lanes are always there; the others only when they do something.
    Lane.values().forEach { lane ->
        val curve = mix.curve(lane)
        val editing = lane == editingLane
        if (isAtRest(lane, curve) && !editing) return@forEach
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

/** The white line that moves over the waveforms as the blend plays; [seconds] is from the start of the blend. */
private fun DrawScope.drawCursor(g: GraphGeometry, seconds: Float) {
    if (g.pxPerSec <= 0f) return
    val x = (g.left + seconds * g.pxPerSec).coerceIn(0f, g.width)
    drawLine(
        Color.White,
        Offset(x, 0f),
        Offset(x, g.bottomDeckTop + g.deckHeight),
        strokeWidth = 2.5.dp.toPx(),
    )
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
private fun BeatmatchRow(
    mix: ManualMix,
    outBpm: Double,
    inBpm: Double,
    onBeatmatch: (Boolean) -> Unit,
    onBarMagnet: (Boolean) -> Unit,
) {
    val rate = mix.incomingRate
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Beatmatching", fontSize = 22.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = mix.tempoMatch,
                onCheckedChange = onBeatmatch,
                colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = Color.White),
            )
        }
        val note = when {
            !mix.tempoMatch -> "Off: the next song plays at its own tempo, and dragging doesn't snap to the beat."
            abs(rate - 1.0) < 0.001 && MixTempo.match(outBpm, inBpm).matched ->
                "Dragging snaps to the beat. Tempos already agree (${outBpm.roundToInt()} and ${inBpm.roundToInt()} BPM, same or double/half), so nothing is stretched."
            abs(rate - 1.0) < 0.001 ->
                "Dragging snaps to the beat. The tempos are too far apart to match (${outBpm.roundToInt()} → ${inBpm.roundToInt()} BPM), so the next song plays at its own."
            else -> {
                val percent = (rate - 1.0) * 100.0
                "Dragging snaps to the beat. The next song is ${if (percent >= 0) "sped up" else "slowed down"} ${"%.1f".format(abs(percent))}% to ${outBpm.roundToInt()} BPM during the blend, then returns to normal."
            }
        }
        Text(note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bar magnet", fontSize = 22.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = mix.barMagnet,
                onCheckedChange = onBarMagnet,
                colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = Color.White),
            )
        }
        Text(
            if (mix.barMagnet) "Dragging snaps from bar line to bar line." else "Off: dragging doesn't snap to bar lines.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
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


/** How much of the blend's length the waveform shows to the left of the window: where playback starts. */
private const val VIEW_LEAD_FRACTION = 0.17 / (0.83 - 0.17)

/** The audition is started at least this long before the blend, so the engine has time to arm it. */
private const val MIN_PRE_BLEND_SECONDS = 0.75
