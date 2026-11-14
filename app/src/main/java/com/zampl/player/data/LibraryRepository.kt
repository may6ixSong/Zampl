package com.zampl.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val ALL_GROUP_ID = -1L
/** 어떤 그룹에도 들어있지 않은 동영상 모음 (자동) */
const val UNGROUPED_GROUP_ID = -2L

/** NORMAL: 일반 재생, RANDOM_GROUP: 그룹 안에서 랜덤, RANDOM_ALL: 전체 동영상 중 랜덤 (목록 없음) */
enum class PlayMode { NORMAL, RANDOM_GROUP, RANDOM_ALL }

enum class SortOrder(val label: String) { LATEST("최신순"), RATING("평점순"), NAME("이름순") }

data class GroupSummary(
    val id: Long,
    val name: String,
    val videos: List<Video>,
    val averageRating: Double?,
    val ratedCount: Int,
) {
    val isAll get() = id == ALL_GROUP_ID
    val isUngrouped get() = id == UNGROUPED_GROUP_ID
    /** 기본 그룹(전체 동영상, 미분류): 항상 있고, 이름 변경·삭제·직접 추가 불가 */
    val isDefault get() = id < 0
}

data class Library(
    val loaded: Boolean,
    val videos: List<Video>,
    val ratings: Map<Long, Int>,
    val groups: List<GroupSummary>,
    /** videoId -> 들어있는 그룹 ID 들 */
    val groupsOfVideo: Map<Long, Set<Long>>,
) {
    private val byId = videos.associateBy { it.id }

    fun video(id: Long) = byId[id]
    fun group(id: Long) = groups.firstOrNull { it.id == id }
    fun userGroups() = groups.filter { !it.isDefault }

    fun videosOf(groupId: Long, sort: SortOrder): List<Video> {
        val base = group(groupId)?.videos ?: emptyList()
        return when (sort) {
            SortOrder.LATEST -> base.sortedByDescending { it.dateAdded }
            SortOrder.RATING -> base.sortedWith(
                compareByDescending<Video> { ratings[it.id] ?: 0 }.thenByDescending { it.dateAdded }
            )
            SortOrder.NAME -> base.sortedBy { it.name.lowercase() }
        }
    }

    /** 평점과 상관없이 균등하게 하나 고름. exclude 에 있는 것은 가능하면 피함. */
    fun randomVideo(groupId: Long, exclude: Set<Long> = emptySet()): Video? {
        val pool = group(groupId)?.videos ?: return null
        val fresh = pool.filter { it.id !in exclude }
        return (fresh.ifEmpty { pool }).randomOrNull()
    }

    companion object {
        val EMPTY = Library(false, emptyList(), emptyMap(), emptyList(), emptyMap())
    }
}

class LibraryRepository(
    private val context: Context,
    private val dao: ZamplDao,
    private val scope: CoroutineScope,
) {
    private val videos = MutableStateFlow<List<Video>>(emptyList())
    private val loaded = MutableStateFlow(false)
    private val refreshLock = Mutex()

    val library: StateFlow<Library> = combine(
        videos, loaded, dao.observeGroups(), dao.observeMemberships(), dao.observeRatings(),
    ) { vids, isLoaded, groups, members, ratingRows ->
        buildLibrary(vids, isLoaded, groups, members, ratingRows)
    }.stateIn(scope, SharingStarted.Eagerly, Library.EMPTY)

    private fun buildLibrary(
        vids: List<Video>,
        isLoaded: Boolean,
        groups: List<GroupEntity>,
        members: List<GroupVideoEntity>,
        ratingRows: List<RatingEntity>,
    ): Library {
        val byId = vids.associateBy { it.id }
        val ratings = ratingRows.associate { it.videoId to it.stars }
        val membersByGroup = members.groupBy { it.groupId }
        val summaries = ArrayList<GroupSummary>(groups.size + 1)
        // 전체 동영상 그룹: 항상 맨 위, 평점 표시 없음
        summaries += GroupSummary(ALL_GROUP_ID, "전체 동영상", vids, null, 0)
        // 미분류: 어느 그룹에도 속하지 않은 동영상
        val grouped = members.mapTo(HashSet()) { it.videoId }
        val ungrouped = vids.filter { it.id !in grouped }
        val ungroupedRated = ungrouped.mapNotNull { ratings[it.id] }
        summaries += GroupSummary(
            UNGROUPED_GROUP_ID, "미분류", ungrouped,
            if (ungroupedRated.isEmpty()) null else ungroupedRated.average(), ungroupedRated.size,
        )
        for (g in groups) {
            val gv = membersByGroup[g.id].orEmpty()
                .mapNotNull { byId[it.videoId] }
                .sortedByDescending { it.dateAdded }
            // 평점 평균: 평가하지 않은 동영상은 모수에서 제외
            val rated = gv.mapNotNull { ratings[it.id] }
            summaries += GroupSummary(
                id = g.id,
                name = g.name,
                videos = gv,
                averageRating = if (rated.isEmpty()) null else rated.average(),
                ratedCount = rated.size,
            )
        }
        val groupsOfVideo = members.groupBy({ it.videoId }, { it.groupId }).mapValues { it.value.toSet() }
        return Library(isLoaded, vids, ratings, summaries, groupsOfVideo)
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            refreshLock.withLock {
                val list = MediaStoreSource.queryAll(context)
                videos.value = list
                loaded.value = true
                if (list.isNotEmpty()) reconcile(list)
            }
        }
    }

    /** 파일 이동 등으로 갤러리 ID 가 바뀐 동영상을 찾아 저장된 데이터를 옮겨줌. */
    private suspend fun reconcile(list: List<Video>) {
        val currentIds = list.mapTo(HashSet()) { it.id }
        val known = dao.allVideoInfo()
        val knownIds = known.mapTo(HashSet()) { it.videoId }
        val missing = known.filter { it.videoId !in currentIds }
        if (missing.isEmpty()) return
        val candidates = list.filter { it.id !in knownIds }
        for (m in missing) {
            val match = candidates.filter { it.sizeBytes == m.sizeBytes && it.durationMs == m.durationMs }
            val v = match.singleOrNull() ?: continue
            dao.remapVideo(m.videoId, v.toInfo())
        }
    }

    private fun Video.toInfo() = VideoInfoEntity(id, name, sizeBytes, durationMs)

    private fun remember(video: Video) = scope.launch(Dispatchers.IO) { dao.upsertVideoInfo(video.toInfo()) }

    // ---- 그룹 ----
    fun createGroup(name: String, thenAdd: Video? = null) {
        scope.launch(Dispatchers.IO) {
            val id = dao.insertGroup(GroupEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
            if (thenAdd != null) {
                dao.upsertVideoInfo(thenAdd.toInfo())
                dao.insertMemberships(listOf(GroupVideoEntity(id, thenAdd.id, System.currentTimeMillis())))
            }
        }
    }

    fun renameGroup(id: Long, name: String) {
        scope.launch(Dispatchers.IO) { dao.renameGroup(id, name.trim()) }
    }

    fun deleteGroup(id: Long) {
        if (id < 0) return
        scope.launch(Dispatchers.IO) { dao.deleteGroup(id) }
    }

    fun addToGroup(groupId: Long, videos: List<Video>) {
        if (groupId < 0 || videos.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            videos.forEach { dao.upsertVideoInfo(it.toInfo()) }
            dao.insertMemberships(videos.map { GroupVideoEntity(groupId, it.id, now) })
        }
    }

    fun removeFromGroup(groupId: Long, videoIds: List<Long>) {
        if (groupId < 0 || videoIds.isEmpty()) return
        scope.launch(Dispatchers.IO) { dao.deleteMemberships(groupId, videoIds) }
    }

    /** 그룹 구성원을 selected 로 맞춤 (추가/제거 차이만 반영). */
    fun setGroupMembers(groupId: Long, selected: Set<Long>) {
        val lib = library.value
        val current = lib.group(groupId)?.videos?.map { it.id }?.toSet() ?: return
        addToGroup(groupId, (selected - current).mapNotNull { lib.video(it) })
        removeFromGroup(groupId, (current - selected).toList())
    }

    // ---- 평점 ----
    fun setRating(video: Video, stars: Int?) {
        scope.launch(Dispatchers.IO) {
            if (stars == null) dao.deleteRating(video.id)
            else {
                dao.upsertVideoInfo(video.toInfo())
                dao.upsertRating(RatingEntity(video.id, stars.coerceIn(1, 5)))
            }
        }
    }

    // ---- 플래그 ----
    fun flags(videoId: Long): Flow<List<FlagEntity>> = dao.observeFlags(videoId)

    fun addFlag(video: Video, positionMs: Long) {
        remember(video)
        scope.launch(Dispatchers.IO) {
            dao.insertFlag(FlagEntity(videoId = video.id, positionMs = positionMs, createdAt = System.currentTimeMillis()))
        }
    }

    fun deleteFlag(id: Long) {
        scope.launch(Dispatchers.IO) { dao.deleteFlag(id) }
    }
}
