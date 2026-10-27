package com.gopal.editor

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val exportedPath: String? = null,
    /** Timeline JSON format of this row: 1 = Phase 1, 2 = stage 2A (speed, filters, adjust). Old rows read fine either way. */
    @ColumnInfo(defaultValue = "1") val fxVersion: Int = 2
)

/** A .cube LUT the user imported (the file itself is copied into the app's files/luts folder). */
@Entity(tableName = "luts")
data class LutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val path: String,
    val size: Int,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface LutDao {
    @Query("SELECT * FROM luts ORDER BY addedAt DESC")
    fun all(): Flow<List<LutEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(l: LutEntity): Long

    @Query("DELETE FROM luts WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * Room v1 -> v2: adds projects.fxVersion (existing rows = 1) and the luts table. Nothing is
 * dropped or rewritten, so every Phase 1 project and its timeline JSON survive as they are.
 */
object Migrations {
    val SQL_1_2: List<String> = listOf(
        "ALTER TABLE `projects` ADD COLUMN `fxVersion` INTEGER NOT NULL DEFAULT 1",
        "CREATE TABLE IF NOT EXISTS `luts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `path` TEXT NOT NULL, `size` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)"
    )
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) { SQL_1_2.forEach { db.execSQL(it) } }
    }
    val ALL: Array<Migration> get() = arrayOf(MIGRATION_1_2)
}

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

@Database(entities = [ProjectEntity::class, LutEntity::class], version = 2, exportSchema = true)
abstract class EditorDb : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun luts(): LutDao
    companion object {
        @Volatile private var inst: EditorDb? = null
        fun get(c: Context): EditorDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, EditorDb::class.java, "gopal_editor.db").addMigrations(*Migrations.ALL).build().also { inst = it }
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
    // ---- Free AI (not secret: no keys here; keys stay in KeyVault) ----
    var freeMode: Boolean
        get() = p.getBoolean("free_mode", true)
        set(v) { p.edit().putBoolean("free_mode", v).apply() }
    /** "" = use the default derived from the Updates repo. */
    var providersUrl: String
        get() = p.getString("providers_url", "") ?: ""
        set(v) { p.edit().putString("providers_url", v.trim()).apply() }
}
