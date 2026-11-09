package com.zampl.player.data

import android.content.Context
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

    @Transaction
    suspend fun remapVideo(oldId: Long, newInfo: VideoInfoEntity) {
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
    ],
    version = 1,
    exportSchema = true,
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
