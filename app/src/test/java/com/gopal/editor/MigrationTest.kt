package com.gopal.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room v1 -> v2 on real SQLite (sqlite-jdbc): build the exact v1 table from Room's exported
 * schema 1.json, insert a Phase 1 project, run our migration SQL, then check
 *  - the row and its timeline JSON are untouched (no data loss) and still decode,
 *  - the migrated tables match what Room expects for v2 (schema 2.json), column by column.
 */
class MigrationTest {
    private fun schema(v: Int) = JSONObject(File("schemas/com.gopal.editor.EditorDb/$v.json").readText()).getJSONObject("database")

    private fun createFrom(c: Connection, v: Int) {
        val ents = schema(v).getJSONArray("entities")
        for (i in 0 until ents.length()) {
            val e = ents.getJSONObject(i)
            c.createStatement().use { it.execute(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))) }
        }
    }

    private fun columns(c: Connection, table: String): List<String> = c.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            val out = ArrayList<String>()
            while (rs.next()) out += listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"), rs.getInt("pk")).joinToString("|")
            out.sorted()
        }
    }

    private val phase1Json = """{"v":1,"aspect":"R16_9","fill":true,"style":"neon","lang":"hinglish","video":[{"id":"a","uri":"content://m/1","name":"trip.mp4","src":8000,"in":500,"out":7000,"vol":0.8}],"audio":[],"captions":[{"id":"c","s":100,"e":900,"t":"namaste","w":[[100,900,"namaste"]]}]}"""

    @Test fun v1ToV2KeepsEveryRowAndMatchesRoomSchema() {
        val old = DriverManager.getConnection("jdbc:sqlite::memory:")
        old.use { c ->
            createFrom(c, 1)
            c.prepareStatement("INSERT INTO projects (id, name, timelineJson, thumbPath, durationMs, createdAt, updatedAt, exportedPath) VALUES (?,?,?,?,?,?,?,?)").use { p ->
                p.setLong(1, 7); p.setString(2, "मेरा प्रोजेक्ट"); p.setString(3, phase1Json); p.setString(4, "/t.jpg")
                p.setLong(5, 6500); p.setLong(6, 111); p.setLong(7, 222); p.setString(8, null); p.executeUpdate()
            }
            Migrations.SQL_1_2.forEach { sql -> c.createStatement().use { it.execute(sql) } }

            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM projects WHERE id = 7").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("मेरा प्रोजेक्ट", rs.getString("name"))
                    assertEquals(phase1Json, rs.getString("timelineJson"))
                    assertEquals("/t.jpg", rs.getString("thumbPath"))
                    assertEquals(6500L, rs.getLong("durationMs"))
                    assertEquals(111L, rs.getLong("createdAt")); assertEquals(222L, rs.getLong("updatedAt"))
                    assertEquals(1, rs.getInt("fxVersion"))
                    val tl = TimelineJson.decode(rs.getString("timelineJson"))
                    assertEquals(6500L, tl.durationMs); assertEquals("namaste", tl.captions[0].text); assertEquals(0.8f, tl.video[0].volume)
                }
            }
            c.createStatement().use { it.execute("INSERT INTO luts (name, path, size, addedAt) VALUES ('Teal', '/l.cube', 33, 1)") }

            val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
            fresh.use { f ->
                createFrom(f, 2)
                for (t in listOf("projects", "luts")) assertEquals(t, columns(f, t), columns(c, t))
            }
        }
    }

    @Test fun roomSchemaVersionsAreExported() {
        assertEquals(1, schema(1).getInt("version"))
        assertEquals(2, schema(2).getInt("version"))
        assertEquals(2, Migrations.MIGRATION_1_2.endVersion)
        assertEquals(1, Migrations.MIGRATION_1_2.startVersion)
    }
}
