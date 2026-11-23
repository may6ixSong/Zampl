package com.zampl.player.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import com.zampl.player.ZamplApp
import com.zampl.player.data.FlagEntity
import com.zampl.player.data.Library
import com.zampl.player.data.PlayItem
import com.zampl.player.data.PlayMode
import com.zampl.player.data.SortOrder
import com.zampl.player.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    val repo = (app as ZamplApp).repo
    val library: StateFlow<Library> = repo.library

    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    var groupId: Long = 0; private set
    var sort: SortOrder = SortOrder.LATEST; private set
    var mode: PlayMode = PlayMode.NORMAL; private set
    private var initialized = false
    /** 랜덤 재생에서 이미 본 항목 키 (다 볼 때까지 겹치지 않게) */
    private val randomHistory = HashSet<String>()

    /** 지금 재생 중인 항목 (동영상 하나 또는 커스텀 조합) */
    val currentItem = MutableStateFlow<PlayItem?>(null)
    /** 지금 재생 중인 단일 동영상 (조합이면 null) */
    val current: StateFlow<Video?> = currentItem
        .map { (it as? PlayItem.Single)?.video }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isPlaying = MutableStateFlow(false)
    val ended = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val locked = MutableStateFlow(false)
    /** 조합 재생 중 몇 번째 동영상인지 (0부터) */
    val segmentIndex = MutableStateFlow(0)
    /** 조합 안 각 동영상이 시작하는 위치 (전체 기준 ms) */
    private val segStarts = MutableStateFlow(listOf(0L))

    // 왼손 모드: 목록/플래그 버튼 위치와 목록 패널 위치를 좌우 반대로. 앱을 다시 켜도 유지.
    private val prefs = app.getSharedPreferences("zampl_prefs", android.content.Context.MODE_PRIVATE)
    val leftHanded = MutableStateFlow(prefs.getBoolean("left_handed", false))

    fun toggleHand() {
        val v = !leftHanded.value
        leftHanded.value = v
        prefs.edit().putBoolean("left_handed", v).apply()
    }

    /** 동영상 가로/세로 비율 (모르면 0) */
    val videoAspect = MutableStateFlow(0f)

    /**
     * 플래그 (전체 기준 위치). 조합이면 각 원본 동영상의 플래그를 이어 붙인 위치로 바꿔서 보여줌.
     * 플래그는 항상 원본 동영상에 저장됨.
     */
    val flags: StateFlow<List<FlagEntity>> = currentItem
        .flatMapLatest { item ->
            when {
                item == null -> flowOf(emptyList())
                item.segments.size == 1 -> repo.flags(item.segments[0].id)
                else -> combine(item.segments.map { repo.flags(it.id) }) { it.toList() }
                    .combine(segStarts) { perSeg, starts ->
                        perSeg.flatMapIndexed { i, list ->
                            val start = starts.getOrElse(i) { 0L }
                            val len = item.segments[i].durationMs
                            list.filter { len <= 0 || it.positionMs <= len }
                                .map { it.copy(positionMs = start + it.positionMs) }
                        }.sortedBy { it.positionMs }
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 하단 썸네일 줄에 쓰이는 프레임들. */
    val frames = mutableStateListOf<ImageBitmap?>()
    private var frameJob: Job? = null
    private var frameKey: Pair<String, Int>? = null
    private var lastFrameCount = 0

    /** 열려던 동영상이 갤러리에서 사라진 경우 true */
    val missing = MutableStateFlow(false)

    // 썸네일 줄을 끄는 동안의 상태
    private var scrubActive = false
    private var wasPlayingBeforeScrub = false

    init {
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                ended.value = state == Player.STATE_ENDED
                if (player.duration > 0) durationMs.value = player.duration
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // 조합: 실제 파일 길이로 각 동영상 시작 위치를 다시 계산
                val item = currentItem.value ?: return
                if (item.segments.size > 1 && timeline.periodCount == item.segments.size) {
                    val period = Timeline.Period()
                    segStarts.value = (0 until timeline.periodCount).map {
                        timeline.getPeriod(it, period).positionInWindowMs.coerceAtLeast(0)
                    }
                }
            }
        })
        viewModelScope.launch {
            while (isActive) {
                positionMs.value = player.currentPosition
                if (player.duration > 0) durationMs.value = player.duration
                segmentIndex.value = player.currentPeriodIndex.coerceAtLeast(0)
                delay(if (player.isPlaying) 100 else 250)
            }
        }
    }

    /** key: "v동영상ID" 또는 "c조합ID" */
    fun init(groupId: Long, key: String, sort: SortOrder, mode: PlayMode) {
        if (initialized) return
        initialized = true
        this.groupId = groupId
        this.sort = sort
        this.mode = mode
        if (!library.value.loaded) repo.refresh()
        viewModelScope.launch {
            val lib = library.first { it.loaded }
            val item = lib.item(key)
            if (item != null) { addToTrail(item); play(item) } else missing.value = true
        }
    }

    private fun cumulativeStarts(segs: List<Video>): List<Long> {
        var acc = 0L
        return segs.map { v -> acc.also { acc += v.durationMs.coerceAtLeast(1) } }
    }

    fun play(item: PlayItem) {
        currentItem.value = item
        durationMs.value = item.durationMs
        positionMs.value = 0
        segmentIndex.value = 0
        segStarts.value = cumulativeStarts(item.segments)
        if (item.segments.size == 1) {
            player.setMediaItem(MediaItem.fromUri(item.segments[0].uri))
        } else {
            // 조합: 여러 파일을 하나의 동영상처럼 이어 붙임 (전체 길이 기준으로 위치 이동 가능)
            val source = ConcatenatingMediaSource2.Builder()
                .useDefaultMediaSourceFactory(getApplication())
                .apply { item.segments.forEach { add(MediaItem.fromUri(it.uri), it.durationMs.coerceAtLeast(1)) } }
                .build()
            player.setMediaSource(source)
        }
        player.prepare()
        player.playWhenReady = true
        frameKey = null
        frames.clear()
        if (lastFrameCount > 0) requestFrames(lastFrameCount)
    }

    // 랜덤 재생에서 지나온 순서 (좌우로 밀어 이전/다음으로 오갈 수 있게)
    private val randomTrail = ArrayList<String>()
    private var trailIdx = -1

    private fun currentList(): List<PlayItem> = library.value.itemsOf(groupId, sort)

    // 마지막으로 확인한 현재 항목의 목록 위치. (미분류에서 재생 중 그룹에 넣어 목록에서 빠져도 이어서 이동하기 위함)
    private var lastIndex = -1

    /** 일반 재생에서 dir 방향의 항목 */
    private fun neighbor(dir: Int): PlayItem? {
        val cur = currentItem.value ?: return null
        val list = currentList()
        val i = list.indexOfFirst { it.key == cur.key }
        if (i >= 0) {
            lastIndex = i
            return list.getOrNull(i + dir)
        }
        if (lastIndex < 0) return null
        // 현재 항목이 목록에서 빠진 경우: 그 자리에 들어온 항목이 '다음'
        return list.getOrNull(if (dir > 0) lastIndex else lastIndex - 1)
    }

    /** dir: +1 다음, -1 이전. 갈 곳이 없으면 false */
    fun canGo(dir: Int): Boolean {
        currentItem.value ?: return false
        return if (mode == PlayMode.NORMAL) {
            neighbor(dir) != null
        } else if (dir < 0) {
            trailIdx > 0
        } else {
            trailIdx < randomTrail.size - 1 || currentList().size > 1
        }
    }

    fun go(dir: Int): Boolean {
        if (!canGo(dir)) return false
        val lib = library.value
        if (mode == PlayMode.NORMAL) {
            play(neighbor(dir) ?: return false)
            return true
        }
        if (dir < 0 || trailIdx < randomTrail.size - 1) {
            val item = lib.item(randomTrail[trailIdx + dir]) ?: return false
            trailIdx += dir
            play(item)
            return true
        }
        return pickNewRandom()
    }

    /** 랜덤 재생: 지금 영상이 마음에 안 들 때 다음 랜덤 영상으로. */
    fun nextRandom(): Boolean = go(1)

    private fun pickNewRandom(): Boolean {
        val lib = library.value
        val pool = currentList()
        currentItem.value?.let { randomHistory += it.key }
        if (pool.none { it.key !in randomHistory }) {
            randomHistory.clear()
            currentItem.value?.let { randomHistory += it.key }
        }
        val next = lib.randomItem(groupId, randomHistory) ?: return false
        if (next.key == currentItem.value?.key) return false
        addToTrail(next)
        play(next)
        return true
    }

    private fun addToTrail(item: PlayItem) {
        if (mode == PlayMode.NORMAL) return
        while (randomTrail.size > trailIdx + 1) randomTrail.removeAt(randomTrail.size - 1)
        randomTrail += item.key
        trailIdx = randomTrail.size - 1
    }

    /** 지금 항목을 지운 뒤 이어서 볼 항목 (없으면 null → 재생 화면 닫기) */
    fun nextAfterDelete(): PlayItem? {
        val cur = currentItem.value ?: return null
        return if (mode == PlayMode.NORMAL) {
            neighbor(1) ?: neighbor(-1)
        } else {
            library.value.randomItem(groupId, randomHistory + cur.key)?.takeIf { it.key != cur.key }
        }
    }

    /** 지운 뒤 호출. next 가 있으면 이어서 재생 */
    fun afterDeleted(deletedKey: String, next: PlayItem?) {
        val removedBefore = randomTrail.take(trailIdx + 1).count { it == deletedKey }
        randomTrail.removeAll { it == deletedKey }
        trailIdx = (trailIdx - removedBefore).coerceIn(-1, randomTrail.size - 1)
        randomHistory += deletedKey
        repo.refresh()
        if (next != null) {
            if (mode != PlayMode.NORMAL) addToTrail(next)
            play(next)
        } else {
            player.stop()
        }
    }

    /** 목록에서 직접 고른 항목 재생 */
    fun pick(item: PlayItem) {
        if (item.key == currentItem.value?.key) return
        addToTrail(item)
        play(item)
    }

    fun togglePlay() {
        when {
            ended.value -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    /** 끝까지 가서 재생이 멈추지 않도록 끝 바로 앞까지만 */
    private fun clampPos(ms: Long): Long {
        val d = durationMs.value
        return if (d > 0) ms.coerceIn(0, (d - 500).coerceAtLeast(0)) else ms.coerceAtLeast(0)
    }

    fun seekBy(deltaMs: Long) {
        player.seekTo(clampPos(player.currentPosition + deltaMs))
    }

    fun seekTo(ms: Long) {
        player.setSeekParameters(SeekParameters.EXACT)
        val p = clampPos(ms)
        player.seekTo(p)
        positionMs.value = p
    }

    // 썸네일 줄을 끄는 동안: 잠시 멈추고 빠르게 화면만 이동, 손을 떼면 정확한 위치로 이동 후 재생 재개.
    // 일시정지 버튼을 누른 상태가 아니었다면 항상 다시 재생됨.
    fun beginScrub() {
        if (!scrubActive) {
            // 이미 끄는 중에 다시 불려도 처음 상태를 덮어쓰지 않음
            wasPlayingBeforeScrub = player.playWhenReady
            scrubActive = true
        }
        player.pause()
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    fun scrubTo(ms: Long) {
        val p = clampPos(ms)
        player.seekTo(p)
        positionMs.value = p
    }

    fun endScrub(ms: Long) {
        seekTo(ms)
        if (!scrubActive) return
        scrubActive = false
        if (wasPlayingBeforeScrub) player.play()
    }

    /** 지금 위치의 원본 동영상과 그 안에서의 위치 */
    private fun locate(globalMs: Long): Pair<Int, Long>? {
        val item = currentItem.value ?: return null
        val starts = segStarts.value
        var i = 0
        while (i + 1 < item.segments.size && i + 1 < starts.size && starts[i + 1] <= globalMs) i++
        return i to (globalMs - starts.getOrElse(i) { 0L }).coerceAtLeast(0)
    }

    fun addFlag(): Long? {
        val item = currentItem.value ?: return null
        val pos = player.currentPosition
        if (flags.value.any { kotlin.math.abs(it.positionMs - pos) < 1000 }) return null
        val (i, local) = locate(pos) ?: return null
        repo.addFlag(item.segments[i], local)
        return pos
    }

    fun deleteFlag(flag: FlagEntity) = repo.deleteFlag(flag.id)

    fun toggleLock() {
        locked.value = !locked.value
    }

    /** 썸네일 줄에 필요한 프레임 개수가 정해지면 호출. 대략적인 것부터 채워 빠르게 보이도록 함. */
    fun requestFrames(count: Int) {
        lastFrameCount = count
        val item = currentItem.value ?: return
        val key = item.key to count
        if (frameKey == key) return
        frameKey = key
        frameJob?.cancel()
        frames.clear()
        repeat(count) { frames.add(null) }
        val segs = item.segments
        val starts = cumulativeStarts(segs)
        val total = segs.sumOf { it.durationMs.coerceAtLeast(1) }.takeIf { it > segs.size }
            ?: player.duration.coerceAtLeast(1)
        frameJob = viewModelScope.launch {
            val order = buildList {
                val seen = BooleanArray(count)
                var step = 8
                while (step >= 1) {
                    for (i in 0 until count step step) if (!seen[i]) { seen[i] = true; add(i) }
                    step /= 2
                }
            }
            withContext(Dispatchers.IO) {
                // 조합이면 동영상마다 읽기 도구를 하나씩 열어 둠 (최대 10개)
                val retrievers = HashMap<Int, MediaMetadataRetriever?>()
                fun retriever(i: Int): MediaMetadataRetriever? = retrievers.getOrPut(i) {
                    try {
                        MediaMetadataRetriever().apply { setDataSource(getApplication(), segs[i].uri) }
                    } catch (_: Exception) {
                        null
                    }
                }
                try {
                    for (i in order) {
                        ensureActive()
                        val t = ((i + 0.5) / count * total).toLong()
                        var s = 0
                        while (s + 1 < segs.size && starts[s + 1] <= t) s++
                        val tUs = (t - starts[s]).coerceAtLeast(0) * 1000
                        val mmr = retriever(s) ?: continue
                        val bmp: Bitmap? = try {
                            if (Build.VERSION.SDK_INT >= 27) {
                                mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 120)
                            } else {
                                mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                                    val scale = 120f / maxOf(full.width, full.height)
                                    Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
                                }
                            }
                        } catch (_: Exception) {
                            null
                        }
                        if (bmp != null) {
                            val img = bmp.asImageBitmap()
                            withContext(Dispatchers.Main) {
                                if (frameKey == key && i < frames.size) frames[i] = img
                            }
                        }
                    }
                } finally {
                    retrievers.values.forEach { try { it?.release() } catch (_: Exception) {} }
                }
            }
        }
    }

    override fun onCleared() {
        frameJob?.cancel()
        player.release()
    }
}
