package com.zampl.player.ui.player

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zampl.player.data.FlagEntity
import com.zampl.player.data.Library
import com.zampl.player.data.Video
import com.zampl.player.ui.common.ConfirmDialog
import com.zampl.player.ui.common.GroupCheckList
import com.zampl.player.ui.common.NameDialog
import com.zampl.player.ui.common.RatingDialog
import com.zampl.player.ui.common.VideoCell
import com.zampl.player.ui.common.formatTime
import com.zampl.player.ui.theme.FlagColor
import com.zampl.player.ui.theme.StarColor
import com.zampl.player.ui.theme.SubText
import kotlinx.coroutines.delay

private enum class Panel { LIST, GROUPS }

private const val AUTO_HIDE_MS = 3000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val lib by vm.library.collectAsState()
    val video by vm.current.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val ended by vm.ended.collectAsState()
    val position by vm.positionMs.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val locked by vm.locked.collectAsState()
    val flags by vm.flags.collectAsState()
    val missing by vm.missing.collectAsState()
    LaunchedEffect(missing) {
        if (missing) {
            Toast.makeText(ctx, "동영상을 찾을 수 없어요", Toast.LENGTH_SHORT).show()
            onExit()
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var lockedUiVisible by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var showRating by remember { mutableStateOf(false) }
    var flagToDelete by remember { mutableStateOf<FlagEntity?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    val touch = { lastTouch = System.nanoTime() }

    val playlist = remember(lib, vm.groupId, vm.sort) { lib.videosOf(vm.groupId, vm.sort) }
    val groupName = lib.group(vm.groupId)?.name ?: ""

    // 도구 자동 숨김 (3초)
    LaunchedEffect(controlsVisible, isPlaying, lastTouch, scrubbing, panel, locked) {
        if (controlsVisible && !locked && isPlaying && !scrubbing && panel == null) {
            delay(AUTO_HIDE_MS); controlsVisible = false
        }
    }
    LaunchedEffect(lockedUiVisible, lastTouch, scrubbing) {
        if (lockedUiVisible && !scrubbing) { delay(AUTO_HIDE_MS); lockedUiVisible = false }
    }
    LaunchedEffect(ended) { if (ended && !locked) controlsVisible = true }

    BackHandler {
        when {
            locked -> {
                lockedUiVisible = true; touch()
                Toast.makeText(ctx, "잠금을 해제한 뒤 나갈 수 있어요", Toast.LENGTH_SHORT).show()
            }
            panel != null -> panel = null
            else -> onExit()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth >= 600.dp

        // 동영상: 화면 전체 (비율 유지)
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    player = vm.player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 화면 탭 → 도구 보이기/숨기기. (좌우로 밀어도 다음 영상으로 넘어가지 않음)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(onTap = {
                        touch()
                        if (locked) lockedUiVisible = !lockedUiVisible
                        else controlsVisible = !controlsVisible
                    })
                }
        )

        val strip: @Composable () -> Unit = {
            FilmStrip(
                frames = vm.frames,
                durationMs = duration,
                positionMs = position,
                flagPositions = flags.map { it.positionMs },
                onFrameCount = { vm.requestFrames(it) },
                onScrubStart = { scrubbing = true; touch(); vm.beginScrub() },
                onScrub = { vm.scrubTo(it); touch() },
                onScrubEnd = { vm.endScrub(it); scrubbing = false; touch() },
            )
        }

        // ---- 일반 도구 ----
        AnimatedVisibility(
            visible = controlsVisible && !locked && panel == null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 위
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xDD000000), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(video?.name ?: "", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val idx = playlist.indexOfFirst { it.id == video?.id }
                        if (idx >= 0) Text("$groupName · ${idx + 1} / ${playlist.size}", color = Color(0xFFC9CED6), fontSize = 12.sp, maxLines = 1)
                    }
                    IconButton(onClick = { panel = Panel.GROUPS }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "그룹에 추가", tint = Color.White)
                    }
                    val rating = video?.let { lib.ratings[it.id] }
                    IconButton(onClick = { showRating = true }) {
                        if (rating != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, "평점", tint = StarColor, modifier = Modifier.size(22.dp))
                            Text("$rating", color = StarColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else Icon(Icons.Outlined.StarOutline, "평점", tint = Color.White)
                    }
                    IconButton(onClick = { panel = Panel.LIST }) { Icon(Icons.Default.GridView, "목록", tint = Color.White) }
                }

                // 가운데
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.Default.Replay10, "10초 뒤로", 50.dp) { vm.seekBy(-10_000); touch() }
                    RoundButton(
                        when { ended -> Icons.Default.Replay; isPlaying -> Icons.Default.Pause; else -> Icons.Default.PlayArrow },
                        "재생/일시정지", 68.dp,
                    ) { vm.togglePlay(); touch() }
                    RoundButton(Icons.Default.Forward10, "10초 앞으로", 50.dp) { vm.seekBy(10_000); touch() }
                }

                // 아래
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000), Color(0xEE000000))))
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(top = 30.dp, bottom = 14.dp),
                ) {
                    if (flags.isNotEmpty()) FlagChips(
                        flags = flags,
                        onClick = { vm.seekTo(it.positionMs); touch() },
                        onLongClick = { flagToDelete = it },
                    )
                    strip()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillButton(Icons.Default.Lock, "잠금", Color.White) {
                            vm.toggleLock(); controlsVisible = false; lockedUiVisible = true; touch()
                        }
                        TimeText(position, duration, Modifier.weight(1f))
                        PillButton(Icons.Default.Flag, "플래그", Color(0xFFFFA3A6)) {
                            touch()
                            val pos = vm.addFlag()
                            Toast.makeText(
                                ctx,
                                if (pos != null) "플래그 추가: ${formatTime(pos)}" else "이 위치에는 이미 플래그가 있어요",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }

        // ---- 잠금 상태: 썸네일 줄 + 잠금 해제 버튼만 ----
        AnimatedVisibility(
            visible = locked && lockedUiVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(14.dp)
                        .background(Color(0x88000000), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ScreenLockRotation, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(" 회전 고정됨", color = Color.White, fontSize = 11.sp)
                }
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(end = 16.dp)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0x40FFFFFF))
                        .border(1.dp, Color(0x80FFFFFF), CircleShape)
                        .clickable {
                            vm.toggleLock(); lockedUiVisible = false; controlsVisible = true; touch()
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.LockOpen, "잠금 해제", tint = Color.White) }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(top = 24.dp, bottom = 14.dp),
                ) {
                    strip()
                    TimeText(position, duration, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        // ---- 목록 / 그룹 추가 패널 (접은 화면: 아래에서, 펼친 화면: 오른쪽 옆) ----
        if (panel != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panel = null }
            )
        }
        val panelContent: @Composable () -> Unit = {
            when (panel) {
                Panel.LIST -> PlaylistPanel(
                    title = groupName, videos = playlist, lib = lib, currentId = video?.id,
                    onClose = { panel = null },
                    onPick = { vm.play(it); panel = null; controlsVisible = true; touch() },
                )
                Panel.GROUPS -> video?.let { v ->
                    Column {
                        PanelHeader("그룹에 추가", "완료") { panel = null }
                        GroupCheckList(
                            groups = lib.userGroups(),
                            checkedIds = lib.groupsOfVideo[v.id].orEmpty(),
                            onToggle = { g, on ->
                                if (on) vm.repo.addToGroup(g.id, listOf(v)) else vm.repo.removeFromGroup(g.id, listOf(v.id))
                            },
                            onCreateNew = { creatingGroup = true },
                        )
                    }
                }
                null -> {}
            }
        }
        if (wide) {
            AnimatedVisibility(
                visible = panel != null,
                enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Box(
                    Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .background(Color(0xF21A1D23))
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(top = 12.dp)
                ) { panelContent() }
            }
        } else {
            AnimatedVisibility(
                visible = panel != null,
                enter = slideInVertically { it }, exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(this@BoxWithConstraints.maxHeight * 0.6f)
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(Color(0xFF1A1D23))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                ) {
                    Column {
                        Box(
                            Modifier
                                .padding(top = 9.dp, bottom = 4.dp)
                                .align(Alignment.CenterHorizontally)
                                .size(38.dp, 4.dp)
                                .background(Color(0xFF555555), RoundedCornerShape(2.dp))
                        )
                        panelContent()
                    }
                }
            }
        }
    }

    // ---- 대화상자 ----
    val v = video
    if (showRating && v != null) {
        RatingDialog(current = lib.ratings[v.id], onDismiss = { showRating = false; touch() }) { vm.repo.setRating(v, it) }
    }
    flagToDelete?.let { f ->
        ConfirmDialog("플래그 삭제", "${formatTime(f.positionMs)} 플래그를 삭제할까요?", onDismiss = { flagToDelete = null; touch() }) {
            vm.deleteFlag(f)
        }
    }
    if (creatingGroup && v != null) {
        NameDialog("새 그룹 만들기", onDismiss = { creatingGroup = false }) {
            vm.repo.createGroup(it, thenAdd = v); creatingGroup = false
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0x77000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x26FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = tint, fontSize = 13.sp)
    }
}

@Composable
private fun TimeText(position: Long, duration: Long, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        Text(formatTime(position), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("  /  ${formatTime(duration)}", color = Color(0xFFC9CED6), fontSize = 13.sp)
    }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlagChips(flags: List<FlagEntity>, onClick: (FlagEntity) -> Unit, onLongClick: (FlagEntity) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        items(flags, key = { it.id }) { f ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(FlagColor.copy(alpha = 0.22f))
                    .border(1.dp, FlagColor.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = { onClick(f) }, onLongClick = { onLongClick(f) })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Flag, null, tint = Color(0xFFFFA3A6), modifier = Modifier.size(13.dp))
                Text(" ${formatTime(f.positionMs)}", color = Color(0xFFFFC2C4), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** 재생 중 현재 그룹 목록. 제목 없이 썸네일만, 평점은 왼쪽 위. */
@Composable
private fun PlaylistPanel(
    title: String,
    videos: List<Video>,
    lib: Library,
    currentId: Long?,
    onClose: () -> Unit,
    onPick: (Video) -> Unit,
) {
    val idx = videos.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = idx)
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("  ${videos.size}개", color = SubText, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "닫기", tint = SubText) }
        }
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(minSize = 96.dp),
            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(videos, key = { it.id }) { v ->
                VideoCell(
                    video = v,
                    rating = lib.ratings[v.id],
                    highlighted = v.id == currentId,
                    onClick = { onPick(v) },
                )
            }
        }
    }
}
