package com.zampl.player.ui.common

import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.ImageRequest
import coil.request.Options
import com.zampl.player.data.GroupSummary
import com.zampl.player.data.Video
import com.zampl.player.ui.theme.Accent
import com.zampl.player.ui.theme.StarColor
import com.zampl.player.ui.theme.SubText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- 썸네일 로딩 (시스템 썸네일 캐시 사용 → 빠름) ----------

data class VideoThumb(val video: Video)

class VideoThumbKeyer : Keyer<VideoThumb> {
    override fun key(data: VideoThumb, options: Options) = "thumb:${data.video.id}:${data.video.sizeBytes}"
}

class VideoThumbFetcher(private val data: VideoThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val ctx = options.context
        val bmp = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.loadThumbnail(data.video.uri, Size(320, 320), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    ctx.contentResolver, data.video.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            }
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        DrawableResult(BitmapDrawable(ctx.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<VideoThumb> {
        override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader) =
            VideoThumbFetcher(data, options)
    }
}

@Composable
fun VideoThumbImage(video: Video, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    AsyncImage(
        model = remember(video.id) { ImageRequest.Builder(ctx).data(VideoThumb(video)).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Color(0xFF242830)),
    )
}

// ---------- 공통 조각 ----------

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun RatingBadge(stars: Int, modifier: Modifier = Modifier) {
    Text(
        "★ $stars",
        color = StarColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 정사각형 썸네일 칸. 제목 없음, 평점은 왼쪽 위, 길이는 오른쪽 아래. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCell(
    video: Video,
    rating: Int?,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        VideoThumbImage(video, Modifier.fillMaxSize())
        if (rating != null) RatingBadge(rating, Modifier.align(Alignment.TopStart).padding(4.dp))
        Text(
            formatDuration(video.durationMs),
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        if (selected != null) {
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, Accent, RoundedCornerShape(4.dp)))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) Accent else Color(0x55000000))
                    .border(2.dp, if (selected) Accent else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        if (highlighted) {
            Box(Modifier.fillMaxSize().border(3.dp, Accent, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String = "",
    confirmLabel: String = "만들기",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("그룹 이름") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
                textStyle = LocalTextStyle.current.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                ),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "삭제",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun RatingDialog(current: Int?, onDismiss: () -> Unit, onSet: (Int?) -> Unit) {
    var stars by remember { mutableIntStateOf(current ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("평점") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.Center) {
                    for (i in 1..5) {
                        IconButton(onClick = { stars = i }, modifier = Modifier.size(52.dp)) {
                            Icon(
                                if (i <= stars) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = "$i 점",
                                tint = if (i <= stars) StarColor else SubText,
                                modifier = Modifier.size(42.dp),
                            )
                        }
                    }
                }
                Text(if (stars == 0) "별을 눌러 평점을 주세요" else "${stars}점", color = SubText, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(stars); onDismiss() }, enabled = stars > 0) { Text("확인") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSet(null); onDismiss() }) { Text("평점 지우기") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

/** 동영상을 넣을 그룹을 체크하는 목록 (재생 화면, 길게 누르기 메뉴에서 사용). */
@Composable
fun GroupCheckList(
    groups: List<GroupSummary>,
    checkedIds: Set<Long>,
    onToggle: (GroupSummary, Boolean) -> Unit,
    onCreateNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        items(groups, key = { it.id }) { g ->
            val checked = g.id in checkedIds
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickableCompat { onToggle(g, !checked) }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { onToggle(g, it) })
                Text(g.name, fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
                Text("${g.videos.size}개", color = SubText, fontSize = 12.sp)
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickableCompat(onCreateNew)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text("  새 그룹 만들기", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)
