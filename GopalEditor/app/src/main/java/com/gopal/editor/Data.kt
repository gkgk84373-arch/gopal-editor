package com.gopal.editor

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timelineJson: String,
    val thumbPath: String? = null,
    val durationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val exportedPath: String? = null
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun all(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProjectEntity): Long

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = true)
abstract class EditorDb : RoomDatabase() {
    abstract fun projects(): ProjectDao
    companion object {
        @Volatile private var inst: EditorDb? = null
        fun get(c: Context): EditorDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, EditorDb::class.java, "gopal_editor.db").build().also { inst = it }
        }
    }
}

/** Small non-secret settings (UI language, first-launch flag). */
class AppPrefs(c: Context) {
    private val p = c.getSharedPreferences("gopal_editor_prefs", Context.MODE_PRIVATE)
    var lang: String
        get() = p.getString("lang", "hi") ?: "hi"
        set(v) { p.edit().putString("lang", v).apply() }
    var setupDone: Boolean
        get() = p.getBoolean("setup_done", false)
        set(v) { p.edit().putBoolean("setup_done", v).apply() }
    var lastSeenUpdate: Int
        get() = p.getInt("last_seen_update", 0)
        set(v) { p.edit().putInt("last_seen_update", v).apply() }
}
