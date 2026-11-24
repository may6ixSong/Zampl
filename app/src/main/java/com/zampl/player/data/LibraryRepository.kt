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
import kotlinx.coroutines.withContext

const val ALL_GROUP_ID = -1L
/** 어떤 그룹에도 들어있지 않은 동영상 모음 (자동) */
const val UNGROUPED_GROUP_ID = -2L
/** 커스텀 조합 모음 (자동, 조합만 들어감) */
const val CUSTOM_GROUP_ID = -3L
/** 조합 하나에 넣을 수 있는 최대 동영상 수 */
const val MAX_COMBO_SIZE = 10

/** 커스텀 조합: 동영상 여러 개를 순서대로 이어 하나처럼 재생 */
data class Combo(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val videos: List<Video>,
    val stars: Int?,
) {
    val durationMs get() = videos.sumOf { it.durationMs }
}

/** 재생 단위: 동영상 하나 또는 커스텀 조합 하나 */
sealed interface PlayItem {
    /** 목록 안에서 구분하는 키 ("v12", "c3") */
    val key: String
    val name: String
    val durationMs: Long
    /** 이어 붙여 재생할 동영상들 (동영상 하나면 1개) */
    val segments: List<Video>
    val thumb: Video get() = segments.first()

    data class Single(val video: Video) : PlayItem {
        override val key get() = "v${video.id}"
        override val name get() = video.name
        override val durationMs get() = video.durationMs
        override val segments get() = listOf(video)
    }

    data class Custom(val combo: Combo) : PlayItem {
        override val key get() = "c${combo.id}"
        override val name get() = combo.name
        override val durationMs get() = combo.durationMs
        override val segments get() = combo.videos
    }
}

/** 데이터 정리 결과/미리보기 */
data class CleanupReport(
    val missingVideos: Int,
    val memberships: Int,
    val ratings: Int,
    val flags: Int,
    val combos: Int,
) {
    val isEmpty get() = memberships + ratings + flags + combos == 0
}

/** NORMAL: 일반 재생, RANDOM_GROUP: 그룹 안에서 랜덤, RANDOM_ALL: 전체 동영상 중 랜덤 (목록 없음) */
enum class PlayMode { NORMAL, RANDOM_GROUP, RANDOM_ALL }

enum class SortOrder(val label: String) { LATEST("최신순"), RATING("평점순"), NAME("이름순") }

data class GroupSummary(
    val id: Long,
    val name: String,
    val videos: List<Video>,
    val averageRating: Double?,
    val ratedCount: Int,
    /** 커스텀 그룹일 때의 조합들 */
    val combos: List<Combo> = emptyList(),
) {
    val isCustom get() = id == CUSTOM_GROUP_ID
    /** 목록에 표시하는 개수 */
    val count get() = if (isCustom) combos.size else videos.size
    /** 그룹 리스트 2x2 미리보기용 */
    val previewVideos get() = if (isCustom) combos.map { it.videos.first() } else videos

    val isAll get() = id == ALL_GROUP_ID
    val isUngrouped get() = id == UNGROUPED_GROUP_ID
    /** 기본 그룹(전체 동영상, 미분류, 커스텀): 항상 있고, 이름 변경·삭제·직접 추가 불가 */
    val isDefault get() = id < 0
}

data class Library(
    val loaded: Boolean,
    val videos: List<Video>,
    val ratings: Map<Long, Int>,
    val groups: List<GroupSummary>,
    /** videoId -> 들어있는 그룹 ID 들 */
    val groupsOfVideo: Map<Long, Set<Long>>,
    /** 동영상이 모두 남아 있는 조합들 */
    val combos: List<Combo> = emptyList(),
) {
    private val byId = videos.associateBy { it.id }
    private val comboById = combos.associateBy { it.id }

    fun combo(id: Long) = comboById[id]

    /** 재생 목록: 커스텀 그룹이면 조합들, 아니면 동영상들 */
    fun itemsOf(groupId: Long, sort: SortOrder): List<PlayItem> =
        if (groupId == CUSTOM_GROUP_ID) combosOf(sort).map { PlayItem.Custom(it) }
        else videosOf(groupId, sort).map { PlayItem.Single(it) }

    fun combosOf(sort: SortOrder): List<Combo> = when (sort) {
        SortOrder.LATEST -> combos.sortedByDescending { it.createdAt }
        SortOrder.RATING -> combos.sortedWith(compareByDescending<Combo> { it.stars ?: 0 }.thenByDescending { it.createdAt })
        SortOrder.NAME -> combos.sortedBy { it.name.lowercase() }
    }

    fun item(key: String): PlayItem? = when (key.firstOrNull()) {
        'v' -> key.drop(1).toLongOrNull()?.let { video(it) }?.let { PlayItem.Single(it) }
        'c' -> key.drop(1).toLongOrNull()?.let { combo(it) }?.let { PlayItem.Custom(it) }
        else -> null
    }

    fun ratingOf(item: PlayItem): Int? = when (item) {
        is PlayItem.Single -> ratings[item.video.id]
        is PlayItem.Custom -> combo(item.combo.id)?.stars
    }

    /** 평점과 상관없이 균등하게 하나 고름. exclude(키) 에 있는 것은 가능하면 피함. */
    fun randomItem(groupId: Long, exclude: Set<String> = emptySet()): PlayItem? {
        val pool = itemsOf(groupId, SortOrder.LATEST)
        val fresh = pool.filter { it.key !in exclude }
        return (fresh.ifEmpty { pool }).randomOrNull()
    }

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
        if (pool.isEmpty()) return null
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

    private val comboRows = combine(dao.observeCombos(), dao.observeComboItems()) { c, i -> c to i }
    private val baseRows = combine(dao.observeGroups(), dao.observeMemberships(), dao.observeRatings()) { g, m, r -> Triple(g, m, r) }

    val library: StateFlow<Library> = combine(
        videos, loaded, baseRows, comboRows,
    ) { vids, isLoaded, base, combos ->
        buildLibrary(vids, isLoaded, base.first, base.second, base.third, combos.first, combos.second)
    }.stateIn(scope, SharingStarted.Eagerly, Library.EMPTY)

    private fun buildLibrary(
        vids: List<Video>,
        isLoaded: Boolean,
        groups: List<GroupEntity>,
        members: List<GroupVideoEntity>,
        ratingRows: List<RatingEntity>,
        comboEntities: List<ComboEntity>,
        comboItems: List<ComboItemEntity>,
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
        // 커스텀: 조합 모음. 조합 안 동영상이 하나라도 없으면 (데이터 정리 전까지) 숨김
        val itemsByCombo = comboItems.groupBy { it.comboId }
        val combos = comboEntities.mapNotNull { c ->
            val vs = itemsByCombo[c.id].orEmpty().sortedBy { it.position }.map { byId[it.videoId] }
            if (vs.isEmpty() || vs.any { it == null }) null
            else Combo(c.id, c.name, c.createdAt, vs.filterNotNull(), c.stars)
        }
        val comboRated = combos.mapNotNull { it.stars }
        summaries += GroupSummary(
            CUSTOM_GROUP_ID, "커스텀", emptyList(),
            if (comboRated.isEmpty()) null else comboRated.average(), comboRated.size, combos,
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
        return Library(isLoaded, vids, ratings, summaries, groupsOfVideo, combos)
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

    // ---- 커스텀 조합 ----
    fun createCombo(name: String, videos: List<Video>) {
        val list = videos.take(MAX_COMBO_SIZE)
        if (list.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            list.forEach { dao.upsertVideoInfo(it.toInfo()) }
            dao.createCombo(ComboEntity(name = name.trim(), createdAt = System.currentTimeMillis()), list.map { it.id })
        }
    }

    fun renameCombo(id: Long, name: String) {
        scope.launch(Dispatchers.IO) { dao.renameCombo(id, name.trim()) }
    }

    fun setComboRating(id: Long, stars: Int?) {
        scope.launch(Dispatchers.IO) { dao.setComboStars(id, stars?.coerceIn(1, 5)) }
    }

    fun deleteCombo(id: Long) {
        scope.launch(Dispatchers.IO) { dao.deleteCombo(id) }
    }

    /** 여러 조합 한 번에 지우기 (원본 동영상은 그대로) */
    fun deleteCombos(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) { ids.chunked(500).forEach { dao.deleteCombos(it) } }
    }

    fun setRating(item: PlayItem, stars: Int?) = when (item) {
        is PlayItem.Single -> setRating(item.video, stars)
        is PlayItem.Custom -> setComboRating(item.combo.id, stars)
    }

    // ---- 데이터 정리 ----
    /**
     * 갤러리에 더 이상 없는 동영상의 데이터(그룹 소속, 평점, 플래그, 그 동영상이 들어간 조합)를 찾음.
     * apply = true 면 실제로 지움. 갤러리를 읽지 못하면(권한 없음 등) null.
     */
    suspend fun cleanup(apply: Boolean): CleanupReport? = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val list = MediaStoreSource.queryAll(context)
            if (list.isEmpty()) return@withLock null
            videos.value = list
            loaded.value = true
            reconcile(list) // ID 가 바뀐 동영상은 먼저 옮겨서 지워지지 않게
            val existing = list.mapTo(HashSet()) { it.id }
            val missing = dao.referencedVideoIds().filter { it !in existing }
            var members = 0; var rates = 0; var flagCount = 0
            val comboIds = HashSet<Long>()
            for (chunk in missing.chunked(500)) {
                members += dao.countMemberships(chunk)
                rates += dao.countRatings(chunk)
                flagCount += dao.countFlags(chunk)
                comboIds += dao.combosUsing(chunk)
            }
            if (apply) {
                for (chunk in comboIds.toList().chunked(500)) dao.deleteCombos(chunk)
                for (chunk in missing.chunked(500)) {
                    dao.deleteMembershipsFor(chunk)
                    dao.deleteRatingsFor(chunk)
                    dao.deleteFlagsFor(chunk)
                    dao.deleteVideoInfoFor(chunk)
                }
            }
            CleanupReport(missing.size, members, rates, flagCount, comboIds.size)
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
