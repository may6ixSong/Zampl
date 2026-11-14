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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.zampl.player.ZamplApp
import com.zampl.player.data.FlagEntity
import com.zampl.player.data.Library
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** 랜덤 재생에서 이미 본 동영상 (다 볼 때까지 겹치지 않게) */
    private val randomHistory = HashSet<Long>()

    val current = MutableStateFlow<Video?>(null)
    val isPlaying = MutableStateFlow(false)
    val ended = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val locked = MutableStateFlow(false)

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

    val flags: StateFlow<List<FlagEntity>> = current
        .flatMapLatest { v -> if (v == null) flowOf(emptyList()) else repo.flags(v.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 하단 썸네일 줄에 쓰이는 프레임들. */
    val frames = mutableStateListOf<ImageBitmap?>()
    private var frameJob: Job? = null
    private var frameKey: Pair<Long, Int>? = null
    private var lastFrameCount = 0

    /** 열려던 동영상이 갤러리에서 사라진 경우 true */
    val missing = MutableStateFlow(false)

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
        })
        viewModelScope.launch {
            while (isActive) {
                positionMs.value = player.currentPosition
                if (player.duration > 0) durationMs.value = player.duration
                delay(if (player.isPlaying) 100 else 250)
            }
        }
    }

    fun init(groupId: Long, videoId: Long, sort: SortOrder, mode: PlayMode) {
        if (initialized) return
        initialized = true
        this.groupId = groupId
        this.sort = sort
        this.mode = mode
        if (!library.value.loaded) repo.refresh()
        viewModelScope.launch {
            val lib = library.first { it.loaded }
            val v = lib.video(videoId)
            if (v != null) { addToTrail(v); play(v) } else missing.value = true
        }
    }

    fun play(video: Video) {
        current.value = video
        durationMs.value = video.durationMs
        positionMs.value = 0
        player.setMediaItem(MediaItem.fromUri(video.uri))
        player.prepare()
        player.playWhenReady = true
        frameKey = null
        frames.clear()
        if (lastFrameCount > 0) requestFrames(lastFrameCount)
    }

    // 랜덤 재생에서 지나온 순서 (좌우로 밀어 이전/다음으로 오갈 수 있게)
    private val randomTrail = ArrayList<Long>()
    private var trailIdx = -1

    private fun currentList(): List<Video> = library.value.videosOf(groupId, sort)

    /** dir: +1 다음, -1 이전. 갈 곳이 없으면 false */
    // 마지막으로 확인한 현재 영상의 목록 위치. (미분류에서 재생 중 그룹에 넣어 목록에서 빠져도 이어서 이동하기 위함)
    private var lastIndex = -1

    /** 일반 재생에서 dir 방향의 영상 */
    private fun neighbor(dir: Int): Video? {
        val cur = current.value ?: return null
        val list = currentList()
        val i = list.indexOfFirst { it.id == cur.id }
        if (i >= 0) {
            lastIndex = i
            return list.getOrNull(i + dir)
        }
        if (lastIndex < 0) return null
        // 현재 영상이 목록에서 빠진 경우: 그 자리에 들어온 영상이 '다음'
        return list.getOrNull(if (dir > 0) lastIndex else lastIndex - 1)
    }

    fun canGo(dir: Int): Boolean {
        current.value ?: return false
        return if (mode == PlayMode.NORMAL) {
            neighbor(dir) != null
        } else if (dir < 0) {
            trailIdx > 0
        } else {
            trailIdx < randomTrail.size - 1 || (library.value.group(groupId)?.videos?.size ?: 0) > 1
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
            val v = lib.video(randomTrail[trailIdx + dir]) ?: return false
            trailIdx += dir
            play(v)
            return true
        }
        return pickNewRandom()
    }

    /** 랜덤 재생: 지금 영상이 마음에 안 들 때 다음 랜덤 영상으로. */
    fun nextRandom(): Boolean = go(1)

    private fun pickNewRandom(): Boolean {
        val lib = library.value
        val pool = lib.group(groupId)?.videos.orEmpty()
        current.value?.let { randomHistory += it.id }
        if (pool.none { it.id !in randomHistory }) {
            randomHistory.clear()
            current.value?.let { randomHistory += it.id }
        }
        val next = lib.randomVideo(groupId, randomHistory) ?: return false
        if (next.id == current.value?.id) return false
        addToTrail(next)
        play(next)
        return true
    }

    private fun addToTrail(v: Video) {
        if (mode == PlayMode.NORMAL) return
        while (randomTrail.size > trailIdx + 1) randomTrail.removeAt(randomTrail.size - 1)
        randomTrail += v.id
        trailIdx = randomTrail.size - 1
    }

    /** 지금 영상을 지운 뒤 이어서 볼 영상 (없으면 null → 재생 화면 닫기) */
    fun nextAfterDelete(): Video? {
        val cur = current.value ?: return null
        return if (mode == PlayMode.NORMAL) {
            neighbor(1) ?: neighbor(-1)
        } else {
            library.value.randomVideo(groupId, randomHistory + cur.id)?.takeIf { it.id != cur.id }
        }
    }

    /** 휴지통으로 옮긴 뒤 호출. next 가 있으면 이어서 재생 */
    fun afterDeleted(deletedId: Long, next: Video?) {
        val removedBefore = randomTrail.take(trailIdx + 1).count { it == deletedId }
        randomTrail.removeAll { it == deletedId }
        trailIdx = (trailIdx - removedBefore).coerceIn(-1, randomTrail.size - 1)
        randomHistory += deletedId
        repo.refresh()
        if (next != null) {
            if (mode == PlayMode.NORMAL) play(next) else { addToTrail(next); play(next) }
        } else {
            player.stop()
        }
    }

    /** 목록에서 직접 고른 영상 재생 */
    fun pick(v: Video) {
        if (v.id == current.value?.id) return
        addToTrail(v)
        play(v)
    }

    fun togglePlay() {
        when {
            ended.value -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val d = durationMs.value
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, if (d > 0) d else Long.MAX_VALUE))
    }

    fun seekTo(ms: Long) {
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(ms)
        positionMs.value = ms
    }

    // 썸네일 줄을 끄는 동안: 잠시 멈추고 빠르게 화면만 이동, 손을 떼면 정확한 위치로 이동 후 재생 재개
    fun beginScrub() {
        wasPlayingBeforeScrub = player.isPlaying || player.playWhenReady && !ended.value
        player.pause()
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    fun scrubTo(ms: Long) {
        player.seekTo(ms)
        positionMs.value = ms
    }

    fun endScrub(ms: Long) {
        seekTo(ms)
        if (wasPlayingBeforeScrub) player.play()
    }

    fun addFlag(): Long? {
        val v = current.value ?: return null
        val pos = player.currentPosition
        if (flags.value.any { kotlin.math.abs(it.positionMs - pos) < 1000 }) return null
        repo.addFlag(v, pos)
        return pos
    }

    fun deleteFlag(flag: FlagEntity) = repo.deleteFlag(flag.id)

    fun toggleLock() {
        locked.value = !locked.value
    }

    /** 썸네일 줄에 필요한 프레임 개수가 정해지면 호출. 대략적인 것부터 채워 빠르게 보이도록 함. */
    fun requestFrames(count: Int) {
        lastFrameCount = count
        val v = current.value ?: return
        val key = v.id to count
        if (frameKey == key) return
        frameKey = key
        frameJob?.cancel()
        frames.clear()
        repeat(count) { frames.add(null) }
        val duration = if (v.durationMs > 0) v.durationMs else player.duration.coerceAtLeast(1)
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
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(getApplication(), v.uri)
                    for (i in order) {
                        ensureActive()
                        val tUs = ((i + 0.5) / count * duration * 1000).toLong()
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
                } catch (_: Exception) {
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onCleared() {
        frameJob?.cancel()
        player.release()
    }
}
