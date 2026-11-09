package com.zampl.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zampl.player.ui.theme.FlagColor
import kotlin.math.roundToInt

/**
 * 갤러리 앱처럼 하단에 썸네일이 쭉 이어진 줄.
 * 가운데 흰 선이 현재 위치이고, 줄을 좌우로 끌면 재생 위치가 바뀜. 줄을 탭하면 그 지점으로 이동.
 */
@Composable
fun FilmStrip(
    frames: List<ImageBitmap?>,
    durationMs: Long,
    positionMs: Long,
    flagPositions: List<Long>,
    onFrameCount: (Int) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(58.dp)) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val frameW = with(density) { 30.dp.toPx() }
        // 줄 전체 길이 ≈ 화면 폭의 2배 → 넓은 화면일수록 더 세밀하게 조절 가능
        val count = ((widthPx * 2f) / frameW).roundToInt().coerceIn(16, 90)
        val stripLen = count * frameW
        LaunchedEffect(count) { onFrameCount(count) }

        var dragPos by remember { mutableStateOf<Long?>(null) }
        val dur = durationMs.coerceAtLeast(1)
        val shown = dragPos ?: positionMs.coerceIn(0, dur)

        val curPos by rememberUpdatedState(positionMs.coerceIn(0, dur))
        val curDur by rememberUpdatedState(dur)
        val curLen by rememberUpdatedState(stripLen)

        val vPad = with(density) { 7.dp.toPx() }
        val playheadW = with(density) { 4.dp.toPx() }
        val flagW = with(density) { 2.dp.toPx() }
        val tri = with(density) { 8.dp.toPx() }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragPos = curPos; onScrubStart() },
                        onDragEnd = { dragPos?.let(onScrubEnd); dragPos = null },
                        onDragCancel = { dragPos?.let(onScrubEnd); dragPos = null },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            val base = dragPos ?: curPos
                            val np = (base - dx / curLen * curDur).toLong().coerceIn(0, curDur)
                            dragPos = np
                            onScrub(np)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        val np = (curPos + (off.x - size.width / 2f) / curLen * curDur).toLong().coerceIn(0, curDur)
                        onScrubStart()
                        onScrubEnd(np)
                    }
                }
        ) {
            val center = size.width / 2f
            val offset = center - shown.toFloat() / dur * stripLen
            val top = vPad
            val h = size.height - vPad * 2
            val fw = frameW.roundToInt()
            val fh = h.roundToInt()

            for (i in 0 until count) {
                val x = offset + i * frameW
                if (x + frameW < 0 || x > size.width) continue
                val img = frames.getOrNull(i)
                if (img == null) {
                    drawRect(Color(0xFF2A2E36), Offset(x, top), Size(frameW, h))
                } else {
                    // 가운데 잘라서 칸에 맞춤
                    val target = frameW / h
                    val src = img.width.toFloat() / img.height
                    val (sw, sh) = if (src > target) (img.height * target).roundToInt() to img.height
                    else img.width to (img.width / target).roundToInt()
                    drawImage(
                        img,
                        srcOffset = IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
                        srcSize = IntSize(sw.coerceAtLeast(1), sh.coerceAtLeast(1)),
                        dstOffset = IntOffset(x.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(fw, fh),
                    )
                }
                drawRect(Color.Black, Offset(x + frameW - 1f, top), Size(1f, h))
            }

            // 플래그 표시
            for (f in flagPositions) {
                val fx = offset + f.toFloat() / dur * stripLen
                if (fx < -tri || fx > size.width + tri) continue
                drawRect(FlagColor, Offset(fx - flagW / 2, 0f), Size(flagW, size.height))
                val p = Path().apply {
                    moveTo(fx, 0f); lineTo(fx + tri, tri * 0.55f); lineTo(fx, tri * 1.1f); close()
                }
                drawPath(p, FlagColor)
            }

            // 현재 위치 (가운데 흰 선)
            drawRoundRect(
                Color.White,
                topLeft = Offset(center - playheadW / 2, 0f),
                size = Size(playheadW, size.height),
                cornerRadius = CornerRadius(playheadW / 2),
            )
        }
    }
}
