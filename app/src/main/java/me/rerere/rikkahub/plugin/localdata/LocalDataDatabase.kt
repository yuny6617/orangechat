/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/**
 * Filesystem layout for one plugin namespace. The directory name is a hash so a plugin id can never
 * become a path segment, and every media file is addressed by an opaque resource id.
 */
class LocalDataPaths(val pluginId: String, filesDir: File) {
    init {
        LocalDataValidation.requirePluginId(pluginId)
    }

    val namespace: String = sha256Hex(pluginId).substring(0, 32)
    val root: File = File(filesDir, "plugin_localdata/$namespace")
    val mediaRoot: File = File(root, "media")
    val originalsDir: File = File(mediaRoot, "originals")
    val thumbnailsDir: File = File(mediaRoot, "thumbnails")
    val temporaryDir: File = File(mediaRoot, "tmp")
    val restoreTempDir: File = File(root, "restore")
    val databaseName: String = "localdata_v1_$namespace.db"

    fun ensureDirectories() {
        originalsDir.mkdirs()
        thumbnailsDir.mkdirs()
        temporaryDir.mkdirs()
    }
}

class LocalDataDatabase(
    context: Context,
    val pluginId: String,
) : Closeable {
    private val appContext: Context = context.applicationContext
    val paths: LocalDataPaths = LocalDataPaths(pluginId, appContext.filesDir)
    val schemaVersion: Int = LocalDataApi.DB_SCHEMA_VERSION

    init {
        // Android normally creates this for Room/SharedPreferences; Robolectric and some devices
        // do not, and SQLiteOpenHelper then fails with SQLITE_CANTOPEN.
        // Note: a full database path that exceeds the Windows MAX_PATH (including the "-journal"
        // suffix SQLite creates on first write) also surfaces as SQLITE_CANTOPEN. That is a test
        // host problem (long Robolectric per-method temp dir), not fixed by mkdirs; see
        // LOCALDATA_TEST_HOST_DIAGNOSIS.md before changing SQLite behavior here.
        appContext.getDatabasePath(paths.databaseName).parentFile?.mkdirs()
        paths.ensureDirectories()
    }

    private val helper: SQLiteOpenHelper = object : SQLiteOpenHelper(
        appContext,
        paths.databaseName,
        null,
        LocalDataApi.DB_SCHEMA_VERSION,
    ) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(false)
        }

        override fun onCreate(db: SQLiteDatabase) {
            createSchema(db)
            val meta = ContentValues().apply {
                put("key", "plugin_id")
                put("value", pluginId)
            }
            db.insertOrThrow("meta", null, meta)
            val version = ContentValues().apply {
                put("key", "schema_version")
                put("value", LocalDataApi.DB_SCHEMA_VERSION.toString())
            }
            db.insertOrThrow("meta", null, version)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            migrate(db, oldVersion, newVersion)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Local data downgrade $oldVersion -> $newVersion is not supported")
        }
    }

    fun readable(): SQLiteDatabase = helper.readableDatabase

    fun writable(): SQLiteDatabase = helper.writableDatabase

    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writable()
        if (db.inTransaction()) return block(db)
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAllRows(db: SQLiteDatabase = writable()) {
        listOf(
            "post_media", "post_acl", "comments", "likes",
            "draft_media", "draft_acl", "drafts", "posts", "author_snapshots", "media_assets",
        ).forEach { table -> db.delete(table, null, null) }
    }

    override fun close() {
        helper.close()
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS posts (
                id TEXT PRIMARY KEY NOT NULL,
                author_kind TEXT NOT NULL,
                author_id TEXT NOT NULL,
                author_name TEXT NOT NULL,
                author_avatar TEXT,
                body TEXT NOT NULL,
                visibility TEXT NOT NULL,
                chat_snapshot_json TEXT,
                client_key TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER,
                UNIQUE(author_kind, author_id, client_key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS post_acl (
                post_id TEXT NOT NULL,
                assistant_id TEXT NOT NULL,
                PRIMARY KEY(post_id, assistant_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS post_media (
                post_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                resource_id TEXT NOT NULL,
                PRIMARY KEY(post_id, ordinal)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS comments (
                id TEXT PRIMARY KEY NOT NULL,
                post_id TEXT NOT NULL,
                author_kind TEXT NOT NULL,
                author_id TEXT NOT NULL,
                author_name TEXT NOT NULL,
                author_avatar TEXT,
                body TEXT NOT NULL,
                reply_to_id TEXT,
                client_key TEXT,
                created_at INTEGER NOT NULL,
                deleted_at INTEGER,
                UNIQUE(author_kind, author_id, client_key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS likes (
                post_id TEXT NOT NULL,
                actor_kind TEXT NOT NULL,
                actor_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(post_id, actor_kind, actor_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS drafts (
                id TEXT PRIMARY KEY NOT NULL,
                author_kind TEXT NOT NULL,
                author_id TEXT NOT NULL,
                author_name TEXT NOT NULL,
                author_avatar TEXT,
                body TEXT NOT NULL,
                visibility TEXT NOT NULL,
                chat_snapshot_json TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS draft_media (
                draft_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                resource_id TEXT NOT NULL,
                PRIMARY KEY(draft_id, ordinal)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS draft_acl (
                draft_id TEXT NOT NULL,
                assistant_id TEXT NOT NULL,
                PRIMARY KEY(draft_id, assistant_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS author_snapshots (
                author_kind TEXT NOT NULL,
                author_id TEXT NOT NULL,
                name TEXT NOT NULL,
                avatar TEXT,
                captured_at INTEGER NOT NULL,
                PRIMARY KEY(author_kind, author_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_assets (
                resource_id TEXT PRIMARY KEY NOT NULL,
                mime TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                display_name TEXT,
                file_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS posts_feed ON posts(deleted_at, created_at DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS posts_author ON posts(author_kind, author_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS post_acl_assistant ON post_acl(assistant_id, post_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS post_media_resource ON post_media(resource_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS comments_post ON comments(post_id, created_at DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS likes_post ON likes(post_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS drafts_author ON drafts(author_kind, author_id, updated_at DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS draft_media_resource ON draft_media(resource_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS media_assets_sha256 ON media_assets(sha256)")
    }

    /**
     * Versioned migration entry point. Version 1 is the initial schema; future versions must add a
     * branch here instead of relying on destructive fallback.
     */
    private fun migrate(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version < 1) {
            createSchema(db)
            version = 1
        }
        if (version > LocalDataApi.DB_SCHEMA_VERSION) {
            throw SQLiteException("Unsupported local data schema $version")
        }
        if (version != newVersion) {
            throw SQLiteException("Missing local data migration $version -> $newVersion")
        }
    }
}

internal fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

internal fun sha256Hex(value: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value)
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
