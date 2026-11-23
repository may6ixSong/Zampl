package com.zampl.player.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * 중요: 앱 업데이트 후에도 데이터가 남도록, DB 구조를 바꿀 때는
 * version 을 올리고 Migration 을 추가해야 함. (fallbackToDestructiveMigration 사용 금지)
 * videoId 는 갤러리(MediaStore)의 동영상 ID.
 */

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "group_videos",
    primaryKeys = ["groupId", "videoId"],
    foreignKeys = [ForeignKey(
        entity = GroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("videoId")],
)
data class GroupVideoEntity(
    val groupId: Long,
    val videoId: Long,
    val addedAt: Long,
)

@Entity(tableName = "ratings")
data class RatingEntity(
    @PrimaryKey val videoId: Long,
    val stars: Int,
)

@Entity(tableName = "flags", indices = [Index("videoId")])
data class FlagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: Long,
    val positionMs: Long,
    val createdAt: Long,
)

/** 동영상 ID 가 바뀌었을 때(파일 이동 등) 다시 찾기 위한 정보. */
@Entity(tableName = "video_info")
data class VideoInfoEntity(
    @PrimaryKey val videoId: Long,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
)

/** 커스텀 조합: 여러 동영상을 순서대로 이어 붙여 하나처럼 재생 (최대 10개) */
@Entity(tableName = "combos")
data class ComboEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** 조합 자체의 평점 (없으면 null) */
    val stars: Int? = null,
)

@Entity(
    tableName = "combo_items",
    primaryKeys = ["comboId", "position"],
    foreignKeys = [ForeignKey(
        entity = ComboEntity::class,
        parentColumns = ["id"],
        childColumns = ["comboId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("videoId")],
)
data class ComboItemEntity(
    val comboId: Long,
    val position: Int,
    val videoId: Long,
)

@Dao
interface ZamplDao {
    // 그룹
    @Query("SELECT * FROM groups ORDER BY createdAt ASC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Insert
    suspend fun insertGroup(group: GroupEntity): Long

    @Query("UPDATE groups SET name = :name WHERE id = :id")
    suspend fun renameGroup(id: Long, name: String)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    // 그룹 소속
    @Query("SELECT * FROM group_videos")
    fun observeMemberships(): Flow<List<GroupVideoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemberships(items: List<GroupVideoEntity>)

    @Query("DELETE FROM group_videos WHERE groupId = :groupId AND videoId IN (:videoIds)")
    suspend fun deleteMemberships(groupId: Long, videoIds: List<Long>)

    // 평점
    @Query("SELECT * FROM ratings")
    fun observeRatings(): Flow<List<RatingEntity>>

    @Upsert
    suspend fun upsertRating(rating: RatingEntity)

    @Query("DELETE FROM ratings WHERE videoId = :videoId")
    suspend fun deleteRating(videoId: Long)

    // 플래그
    @Query("SELECT * FROM flags WHERE videoId = :videoId ORDER BY positionMs ASC")
    fun observeFlags(videoId: Long): Flow<List<FlagEntity>>

    @Insert
    suspend fun insertFlag(flag: FlagEntity): Long

    @Query("DELETE FROM flags WHERE id = :id")
    suspend fun deleteFlag(id: Long)

    // 동영상 정보 (ID 복구용)
    @Upsert
    suspend fun upsertVideoInfo(info: VideoInfoEntity)

    @Query("SELECT * FROM video_info")
    suspend fun allVideoInfo(): List<VideoInfoEntity>

    @Query("UPDATE OR IGNORE group_videos SET videoId = :newId WHERE videoId = :oldId")
    suspend fun remapMemberships(oldId: Long, newId: Long)

    @Query("DELETE FROM group_videos WHERE videoId = :oldId")
    suspend fun deleteMembershipsOf(oldId: Long)

    @Query("UPDATE OR IGNORE ratings SET videoId = :newId WHERE videoId = :oldId")
    suspend fun remapRating(oldId: Long, newId: Long)

    @Query("DELETE FROM ratings WHERE videoId = :oldId")
    suspend fun deleteRatingOf(oldId: Long)

    @Query("UPDATE flags SET videoId = :newId WHERE videoId = :oldId")
    suspend fun remapFlags(oldId: Long, newId: Long)

    @Query("DELETE FROM video_info WHERE videoId = :oldId")
    suspend fun deleteVideoInfo(oldId: Long)

    // 커스텀 조합
    @Query("SELECT * FROM combos")
    fun observeCombos(): Flow<List<ComboEntity>>

    @Query("SELECT * FROM combo_items ORDER BY comboId, position")
    fun observeComboItems(): Flow<List<ComboItemEntity>>

    @Insert
    suspend fun insertCombo(combo: ComboEntity): Long

    @Insert
    suspend fun insertComboItems(items: List<ComboItemEntity>)

    @Transaction
    suspend fun createCombo(combo: ComboEntity, videoIds: List<Long>): Long {
        val id = insertCombo(combo)
        insertComboItems(videoIds.mapIndexed { i, v -> ComboItemEntity(id, i, v) })
        return id
    }

    @Query("UPDATE combos SET name = :name WHERE id = :id")
    suspend fun renameCombo(id: Long, name: String)

    @Query("UPDATE combos SET stars = :stars WHERE id = :id")
    suspend fun setComboStars(id: Long, stars: Int?)

    @Query("DELETE FROM combos WHERE id = :id")
    suspend fun deleteCombo(id: Long)

    @Query("UPDATE combo_items SET videoId = :newId WHERE videoId = :oldId")
    suspend fun remapComboItems(oldId: Long, newId: Long)

    // 데이터 정리: 저장된 데이터가 가리키는 모든 동영상 ID
    @Query(
        "SELECT videoId FROM group_videos UNION SELECT videoId FROM ratings UNION SELECT videoId FROM flags " +
            "UNION SELECT videoId FROM combo_items UNION SELECT videoId FROM video_info"
    )
    suspend fun referencedVideoIds(): List<Long>

    @Query("SELECT COUNT(*) FROM group_videos WHERE videoId IN (:ids)")
    suspend fun countMemberships(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM ratings WHERE videoId IN (:ids)")
    suspend fun countRatings(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM flags WHERE videoId IN (:ids)")
    suspend fun countFlags(ids: List<Long>): Int

    @Query("SELECT DISTINCT comboId FROM combo_items WHERE videoId IN (:ids)")
    suspend fun combosUsing(ids: List<Long>): List<Long>

    @Query("DELETE FROM group_videos WHERE videoId IN (:ids)")
    suspend fun deleteMembershipsFor(ids: List<Long>)

    @Query("DELETE FROM ratings WHERE videoId IN (:ids)")
    suspend fun deleteRatingsFor(ids: List<Long>)

    @Query("DELETE FROM flags WHERE videoId IN (:ids)")
    suspend fun deleteFlagsFor(ids: List<Long>)

    @Query("DELETE FROM video_info WHERE videoId IN (:ids)")
    suspend fun deleteVideoInfoFor(ids: List<Long>)

    @Query("DELETE FROM combos WHERE id IN (:comboIds)")
    suspend fun deleteCombos(comboIds: List<Long>)

    @Transaction
    suspend fun remapVideo(oldId: Long, newInfo: VideoInfoEntity) {
        remapComboItems(oldId, newInfo.videoId)
        remapMemberships(oldId, newInfo.videoId)
        deleteMembershipsOf(oldId)
        remapRating(oldId, newInfo.videoId)
        deleteRatingOf(oldId)
        remapFlags(oldId, newInfo.videoId)
        deleteVideoInfo(oldId)
        upsertVideoInfo(newInfo)
    }
}

@Database(
    entities = [
        GroupEntity::class,
        GroupVideoEntity::class,
        RatingEntity::class,
        FlagEntity::class,
        VideoInfoEntity::class,
        ComboEntity::class,
        ComboItemEntity::class,
    ],
    // v2: 커스텀 조합 테이블 추가 (기존 데이터는 자동 변환으로 그대로 유지)
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class ZamplDatabase : RoomDatabase() {
    abstract fun dao(): ZamplDao

    companion object {
        fun create(context: Context): ZamplDatabase =
            Room.databaseBuilder(context, ZamplDatabase::class.java, "zampl.db")
                // 마이그레이션은 여기에 .addMigrations(...) 로 추가
                .build()
    }
}
