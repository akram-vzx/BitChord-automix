package com.music.bitchord.mixed

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.components.MixedGoldLabel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SONG_H = 72.dp
private val PILL_H = 52.dp
private val GREEN = Color(0xFF1ED760)

/**
 * A mixed playlist's page: the songs in Spotify's edit layout — artwork, title,
 * tempo and key, a handle to drag by — with a pill between each pair that says
 * whether (and how) they are mixed.
 *
 * Tapping the pill of a pair with no mix builds one; tapping an Auto or Manual
 * pill opens the editor on it.
 */
@Composable
fun MixedPlaylistScreen(
    browseId: String,
    currentVideoId: String?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (songs: List<Song>, index: Int) -> Unit,
    onPreview: (songs: List<Song>, index: Int, positionMs: Long) -> Unit,
    onStopPreview: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by MixedPlaylists.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == MixedPlaylists.idOf(browseId) }
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    @Suppress("UNUSED_VARIABLE") val revision by MixedAnalysis.revision.collectAsStateWithLifecycle()
    val working by MixedAnalysis.working.collectAsStateWithLifecycle()
    val building by MixedBuilds.inProgress.collectAsStateWithLifecycle()
    val downloads by Downloads.active.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sortPending by remember { mutableStateOf<List<Song>?>(null) }

    val songs = playlist.songs
    val ids = songs.map { it.videoId to it.localUri }
    LaunchedEffect(ids) {
        MixedAnalysis.warm(songs)
        MixedAnalysis.enqueue(songs.filter { it.localUri != null })
    }
    LaunchedEffect(Unit) {
        MixedBuilds.problems.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val editorPair = editing?.let { (from, to) ->
        val a = songs.firstOrNull { it.videoId == from }
        val b = songs.firstOrNull { it.videoId == to }
        if (a != null && b != null) a to b else null
    }
    BackHandler(enabled = editorPair == null) { onBack() }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val itemPx = with(density) { (SONG_H + PILL_H).toPx() }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    val dragTarget = if (dragFrom >= 0) (dragFrom + (dragDelta / itemPx).roundToInt()).coerceIn(0, (songs.size - 1).coerceAtLeast(0)) else -1

    val readySongs = songs.filter { it.localUri != null }
    val minutes = readySongs.sumOf { MixedAnalysis.listedSeconds(it) }.div(60.0).roundToInt()
    val pending = songs.count { it.localUri == null }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item(key = "header") {
                Header(
                    playlist = playlist,
                    minutes = minutes,
                    pending = pending,
                    onBack = onBack,
                    onMenu = { menuOpen = true },
                    menuOpen = menuOpen,
                    onDismissMenu = { menuOpen = false },
                    onRename = { menuOpen = false; renaming = true },
                    onDelete = { menuOpen = false; deleting = true },
                    onRetry = { menuOpen = false; MixedPlaylists.retryPending(playlist.id) },
                    canPlay = readySongs.isNotEmpty(),
                    onPlay = {
                        if (readySongs.isNotEmpty()) {
                            MixedPlayback.activate(playlist.id)
                            onPlay(readySongs, 0)
                        }
                    },
                    canSort = songs.size >= 3,
                    onSort = {
                        scope.launch { sortPending = MixedPlaylists.orderByMixing(playlist.id) }
                    },
                )
            }
            if (songs.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No songs yet. Open the ⋮ menu of any song, choose Add to playlist and tick this playlist. Each song is downloaded first, then measured for tempo and key.",
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                    )
                }
            }
            itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
                val next = songs.getOrNull(index + 1)
                val shift = when {
                    dragFrom < 0 -> 0f
                    index == dragFrom -> dragDelta
                    dragFrom < dragTarget && index in (dragFrom + 1)..dragTarget -> -itemPx
                    dragTarget < dragFrom && index in dragTarget until dragFrom -> itemPx
                    else -> 0f
                }
                val currentIndex = rememberUpdatedState(index)
                Column(
                    Modifier
                        .zIndex(if (index == dragFrom) 1f else 0f)
                        .graphicsLayer { translationY = shift }
                        .then(
                            if (index == dragFrom) Modifier.shadow(8.dp, RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            else Modifier,
                        ),
                ) {
                    SongRow(
                        song = song,
                        tempo = MixedAnalysis.tempoOf(song),
                        analysing = song.videoId in working && MixedAnalysis.tempoOf(song) == null,
                        download = downloads[song.videoId],
                        isCurrent = song.videoId == currentVideoId,
                        isPlaying = isPlaying,
                        onClick = {
                            if (song.localUri != null) {
                                val at = readySongs.indexOfFirst { it.videoId == song.videoId }
                                if (at >= 0) {
                                    MixedPlayback.activate(playlist.id)
                                    onPlay(readySongs, at)
                                }
                            }
                        },
                        onRemove = { MixedPlaylists.removeSong(playlist.id, currentIndex.value) },
                        handle = Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    dragFrom = currentIndex.value
                                    dragDelta = 0f
                                },
                                onDragEnd = {
                                    val from = dragFrom
                                    val to = dragTarget
                                    dragFrom = -1
                                    dragDelta = 0f
                                    if (from >= 0 && to >= 0 && from != to) MixedPlaylists.move(playlist.id, from, to)
                                },
                                onDragCancel = {
                                    dragFrom = -1
                                    dragDelta = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDelta += amount.y
                                    // Near the edge of the screen the list scrolls under the finger.
                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex.value + 1 }
                                    if (info != null) {
                                        val centre = info.offset + dragDelta + info.size / 2f
                                        val edge = 140f
                                        val step = 18f
                                        if (centre > listState.layoutInfo.viewportEndOffset - edge) {
                                            listState.dispatchRawDelta(step); dragDelta += step
                                        } else if (centre < listState.layoutInfo.viewportStartOffset + edge) {
                                            listState.dispatchRawDelta(-step); dragDelta -= step
                                        }
                                    }
                                },
                            )
                        },
                    )
                    if (next != null) {
                        val transition = playlist.transitionBetween(song, next)
                        val buildingNow = MixedBuilds.key(playlist.id, song, next) in building
                        val state = if (buildingNow) MixState.MIXING else transition.state
                        JoinPill(
                            state = state,
                            ready = song.localUri != null && next.localUri != null,
                            onClick = {
                                when (state) {
                                    MixState.NONE -> MixedBuilds.start(playlist.id, song, next)
                                    MixState.AUTO, MixState.MANUAL -> editing = song.videoId to next.videoId
                                    MixState.MIXING -> Unit
                                }
                            },
                        )
                    } else {
                        Spacer(Modifier.height(PILL_H))
                    }
                }
            }
        }
    }

    editorPair?.let { (a, b) ->
        DisposableEffect(a.videoId, b.videoId) {
            val release = AutomixEffort.boost()
            MixedAnalysis.prioritise(listOf(a, b))
            onDispose { release() }
        }
        MixEditorScreen(
            playlistId = playlist.id,
            from = a,
            to = b,
            onClose = {
                MixedPlayback.setPreview(null, null, null)
                onStopPreview()
                editing = null
            },
            onPreview = { positionMs, mix ->
                MixedPlayback.activate(playlist.id)
                MixedPlayback.setPreview(a.videoId, b.videoId, mix)
                onPreview(listOf(a, b), 0, positionMs)
            },
            onStopPreview = onStopPreview,
        )
    }

    if (renaming) {
        var text by remember { mutableStateOf(playlist.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { MixedPlaylists.rename(playlist.id, text); renaming = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${playlist.title}”?") },
            text = { Text("The playlist and its transitions are removed. The downloaded songs stay in your downloads.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    MixedPlaylists.delete(playlist.id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
    sortPending?.let { order ->
        val lost = MixedPlaylists.manualLostBy(playlist.id, order)
        val unmeasured = songs.count { MixedAnalysis.tempoOf(it) == null }
        AlertDialog(
            onDismissRequest = { sortPending = null },
            title = { Text("Sort by transitions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The songs are put in the order that gives each one the closest tempo and key to the one before it.")
                    Text(
                        "All manual transitions will be deleted, except those between songs that stay next to each other." +
                            if (lost > 0) " $lost of yours would be lost." else "",
                    )
                    if (unmeasured > 0) Text("$unmeasured song(s) have not been measured yet and are placed on a best guess.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    MixedPlaylists.applyOrder(playlist.id, order)
                    sortPending = null
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { sortPending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(
    playlist: MixedPlaylist,
    minutes: Int,
    pending: Int,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    canPlay: Boolean,
    onPlay: () -> Unit,
    canSort: Boolean,
    onSort: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = onRename)
                    if (pending > 0) DropdownMenuItem(text = { Text("Retry downloads") }, onClick = onRetry)
                    DropdownMenuItem(text = { Text("Delete playlist") }, onClick = onDelete)
                }
            }
        }
        Box(
            Modifier.size(210.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (playlist.coverUrl != null) {
                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MixedGoldLabel(Modifier.align(Alignment.BottomStart), fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            playlist.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            "Mixed playlist · ${playlist.songs.size} tracks${if (minutes > 0) " · $minutes min" else ""}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (pending > 0) "Downloading $pending song${if (pending == 1) "" else "s"}…" else "On this device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f).height(52.dp).clip(CircleShape)
                    .background(if (canPlay) Color.White else Color.White.copy(alpha = 0.3f))
                    .clickable(enabled = canPlay, onClick = onPlay),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(6.dp))
                Text("Play", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.weight(1f).height(52.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (canSort) 1f else 0.4f))
                    .clickable(enabled = canSort, onClick = onSort),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = GREEN)
                Spacer(Modifier.width(6.dp))
                Text("Sort by mix", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SongRow(
    song: Song,
    tempo: SongTempo?,
    analysing: Boolean,
    download: DownloadState?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    handle: Modifier,
) {
    val pending = song.localUri == null
    Row(
        Modifier.fillMaxWidth().height(SONG_H).clickable(onClick = onClick).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) GREEN else MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
            )
            Text(
                song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            when {
                pending && download is DownloadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Failed", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Downloading", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tempo != null -> {
                    Text(MixOrdering.bpmLabel(tempo.bpm), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        tempo.key?.let { CamelotBadge(it); Spacer(Modifier.width(6.dp)) }
                        Text(formatDuration(song.durationText), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    Text(if (analysing) "Analysing…" else "—", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDuration(song.durationText), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The pill between two songs, on the thin line that links their artwork. */
@Composable
private fun JoinPill(state: MixState, ready: Boolean, onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val label = when {
        !ready -> "Waiting for download"
        state == MixState.NONE -> "No mix"
        state == MixState.MIXING -> "Mixing…"
        state == MixState.AUTO -> "Auto"
        else -> "Manual"
    }
    val clickable = ready && state != MixState.MIXING
    Box(
        Modifier
            .fillMaxWidth()
            .height(PILL_H)
            .drawBehind {
                val x = 4.dp.toPx() + 48.dp.toPx() + 28.dp.toPx()
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .padding(start = 56.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = clickable, onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(
                    when (state) {
                        MixState.AUTO -> GREEN
                        MixState.MANUAL -> Color(0xFF3DA5F4)
                        else -> MaterialTheme.colorScheme.outline
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (state == MixState.MIXING) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (clickable && state != MixState.NONE) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
