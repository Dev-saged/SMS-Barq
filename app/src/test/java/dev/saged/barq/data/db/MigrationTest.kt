package dev.saged.barq.data.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * Every database version Barq has saved (app/schemas, written by the build since 1.5.3) must
 * upgrade to the current one without losing rows and match exactly what the app expects.
 *
 * For each saved version: the tables are created from that version's schema file, a row is put in
 * every table, then Barq's real database opens it with all its migrations. Room checks the result
 * against the current schema and stops with an error if anything differs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationTest {

    /** Tables a later version dropped on purpose (1.9.0: smart-reply rules became the reply memory). */
    private companion object {
        val REMOVED_TABLES = setOf("auto_reply_rules")
    }

    private val schemas = File("schemas/${BarqDatabase::class.java.name}")
    private val json = Json { ignoreUnknownKeys = true }

    private fun savedVersions(): List<Int> =
        schemas.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.sorted()
            .orEmpty()

    @Test
    fun everySavedVersionUpgradesWithoutLosingRows() {
        val versions = savedVersions()
        assumeTrue("No saved schema yet (made by the build)", versions.isNotEmpty())
        versions.forEach { version -> upgradeFrom(version) }
    }

    /** Works without saved schema files: a current database is turned back into version 4 first. */
    @Test
    fun smartRulesBecomeTheReplyMemory() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-4-to-5.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        Room.databaseBuilder(context, BarqDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries().build().apply { openHelper.writableDatabase; close() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("DROP TABLE `reply_memory`")
            db.execSQL(
                "CREATE TABLE `auto_reply_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`keywords` TEXT NOT NULL, `reply` TEXT NOT NULL, `enabled` INTEGER NOT NULL)",
            )
            db.execSQL("INSERT INTO `auto_reply_rules` (`keywords`, `reply`, `enabled`) VALUES ('hi', 'hello', 1)")
            db.version = 4
        }
        val room = Room.databaseBuilder(context, BarqDatabase::class.java, file.absolutePath)
            .addMigrations(*BarqDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'auto_reply_rules'").use {
                assertEquals(0, it.count)
            }
            db.query("SELECT COUNT(*) FROM `reply_memory`").use { c ->
                c.moveToFirst()
                assertEquals(0L, c.getLong(0))
            }
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }

    private fun upgradeFrom(version: Int) {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-$version.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }

        val schema = json.parseToJsonElement(File(schemas, "$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val entities = schema.getValue("entities").jsonArray.map { it.jsonObject }

        // 1. The database exactly as that version created it, with one row in each table.
        val rowsBefore = HashMap<String, Long>()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            entities.forEach { e -> createEntity(db, e) }
            schema["views"]?.jsonArray?.forEach { v ->
                val view = v.jsonObject
                db.execSQL(view.str("createSql").replace("\${VIEW_NAME}", view.str("viewName")))
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            entities.filter { it["ftsVersion"] == null }.forEach { e ->
                insertSampleRow(db, e)
                rowsBefore[e.str("tableName")] = count(db, e.str("tableName"))
            }
            db.version = version
        }

        // 2. Barq opens it: migrations run, then Room checks every table, column and index.
        val room = Room.databaseBuilder(context, BarqDatabase::class.java, file.absolutePath)
            .addMigrations(*BarqDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            rowsBefore.filterKeys { it !in REMOVED_TABLES }.forEach { (table, before) ->
                db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                    c.moveToFirst()
                    assertEquals("Rows lost in $table when upgrading from version $version", before, c.getLong(0))
                }
            }
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }

    private fun createEntity(db: SQLiteDatabase, e: JsonObject) {
        val table = e.str("tableName")
        db.execSQL(e.str("createSql").replace("\${TABLE_NAME}", table))
        e["indices"]?.jsonArray?.forEach { i ->
            val index = i.jsonObject
            db.execSQL(
                index.str("createSql").replace("\${TABLE_NAME}", table).replace("\${INDEX_NAME}", index.str("name")),
            )
        }
        e["contentSyncTriggers"]?.jsonArray?.forEach { db.execSQL(it.jsonPrimitive.content) }
    }

    /** One row with a value in every required column (enough to see that rows survive). */
    private fun insertSampleRow(db: SQLiteDatabase, e: JsonObject) {
        val fields = e.getValue("fields").jsonArray.map { it.jsonObject }
            .filter { it["notNull"]?.jsonPrimitive?.booleanOrNull == true }
        if (fields.isEmpty()) return
        val columns = fields.joinToString(",") { "`" + it.str("columnName") + "`" }
        val values = fields.joinToString(",") { f ->
            when (f.str("affinity")) {
                "INTEGER" -> "1"
                "REAL" -> "1.0"
                "BLOB" -> "x'00'"
                else -> "'1'"
            }
        }
        db.execSQL("INSERT INTO `${e.str("tableName")}` ($columns) VALUES ($values)")
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}
