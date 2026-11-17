package com.speedcards.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.repo.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class MediaEntry(val name: String, val sha256: String, val size: Long)

@Serializable
data class BackupManifest(
    val format: Int = 1,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val noteCount: Int,
    val cardCount: Int,
    val reviewCount: Int,
    /** Every media file the backup expects; mirrored separately to the NAS `media/` folder. */
    val media: List<MediaEntry>,
    /** True when the media files are inside this zip (full export). */
    val includesMedia: Boolean,
)

class BackupException(message: String) : Exception(message)

data class StagedRestore(val manifest: BackupManifest, val missingMedia: List<String>)

/**
 * `.scbackup` = zip of `manifest.json` + `speedcards.db` (+ `media/…` for full exports).
 * Restores are staged under `files/restore/` and applied by [applyPendingRestore] before the database opens.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpeedCardsDatabase,
    private val media: MediaRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Writes a backup zip to [dest]. */
    suspend fun snapshot(dest: File, includeMedia: Boolean, appVersion: String): BackupManifest = withContext(Dispatchers.IO) {
        val tmpDb = File(context.cacheDir, "snapshot-${System.nanoTime()}.db")
        try {
            copyDatabase(tmpDb)
            val manifest = BackupManifest(
                schemaVersion = SCHEMA_VERSION,
                appVersion = appVersion,
                createdAt = System.currentTimeMillis(),
                noteCount = count(tmpDb, "note"),
                cardCount = count(tmpDb, "card"),
                reviewCount = count(tmpDb, "review_log"),
                media = mediaEntries(),
                includesMedia = includeMedia,
            )
            dest.parentFile?.mkdirs()
            ZipOutputStream(dest.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                tmpDb.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (includeMedia) manifest.media.forEach { m ->
                    zip.putNextEntry(ZipEntry("media/${m.name}"))
                    media.file(m.name).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            manifest
        } finally {
            tmpDb.delete()
        }
    }

    suspend fun readManifest(zipFile: File): BackupManifest = withContext(Dispatchers.IO) {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: throw BackupException("This isn't a Speed Cards backup (no manifest).")
            json.decodeFromString(BackupManifest.serializer(), zip.getInputStream(entry).bufferedReader().readText())
        }
    }

    /**
     * Unpacks a backup for restoring on next launch. Media comes from the zip when included,
     * otherwise from [fetchMedia] (e.g. the NAS media folder); files already on the phone are reused.
     */
    suspend fun stageRestore(zipFile: File, fetchMedia: suspend (MediaEntry, File) -> Boolean): StagedRestore = withContext(Dispatchers.IO) {
        val manifest = readManifest(zipFile)
        if (manifest.schemaVersion > SCHEMA_VERSION) {
            throw BackupException("This backup is from a newer version of Speed Cards. Update the app first.")
        }
        val stage = stagingDir(context).apply { deleteRecursively(); mkdirs() }
        val stagedMedia = File(stage, "media").apply { mkdirs() }
        val pendingDb = File(stage, PENDING_DB_TMP)
        ZipFile(zipFile).use { zip ->
            val dbEntry = zip.getEntry(DB_ENTRY) ?: throw BackupException("The backup has no database in it.")
            zip.getInputStream(dbEntry).use { input -> pendingDb.outputStream().use { input.copyTo(it) } }
            validate(pendingDb)
            if (manifest.includesMedia) manifest.media.forEach { m ->
                zip.getEntry("media/${m.name}")?.let { e ->
                    zip.getInputStream(e).use { input -> File(stagedMedia, m.name).outputStream().use { input.copyTo(it) } }
                }
            }
        }
        val missing = mutableListOf<String>()
        for (m in manifest.media) {
            val target = File(stagedMedia, m.name)
            if (target.exists()) continue
            val local = media.file(m.name)
            val ok = if (local.exists() && local.length() == m.size) { local.copyTo(target, overwrite = true); true } else fetchMedia(m, target)
            if (!ok) missing += m.name
        }
        // Mark the stage complete only once everything is in place.
        pendingDb.renameTo(File(stage, PENDING_DB))
        StagedRestore(manifest, missing)
    }

    fun cancelStagedRestore() { stagingDir(context).deleteRecursively() }

    /** Latest edit or review, used to skip the backup reminder when nothing changed. */
    suspend fun lastChangeAt(): Long = withContext(Dispatchers.IO) {
        val q = "SELECT MAX(t) FROM (SELECT MAX(timestamp) AS t FROM review_log UNION ALL SELECT MAX(modified) FROM note)"
        db.openHelper.readableDatabase.query(q).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }

    suspend fun reviewsSince(since: Long): Int = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM review_log WHERE timestamp > ?", arrayOf(since)).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
    }

    private fun copyDatabase(target: File) {
        val sqlite = db.openHelper.writableDatabase
        target.delete()
        try {
            sqlite.execSQL("VACUUM INTO '${target.absolutePath.replace("'", "''")}'")
        } catch (e: Exception) {
            // Android 10 and older ship SQLite without VACUUM INTO: checkpoint the WAL, then copy the file.
            sqlite.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            context.getDatabasePath(SpeedCardsDatabase.NAME).copyTo(target, overwrite = true)
        }
    }

    private fun mediaEntries(): List<MediaEntry> =
        media.mediaDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            .map { MediaEntry(it.name, MediaRepository.sha256(it), it.length()) }

    private fun count(dbFile: File, table: String): Int =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { s ->
            s.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
        }

    private fun validate(dbFile: File) {
        try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { s ->
                val version = s.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
                if (version > SCHEMA_VERSION) throw BackupException("This backup is from a newer version of Speed Cards.")
                s.rawQuery("SELECT COUNT(*) FROM note", null).use { it.moveToFirst() }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("The backup's database is damaged: ${e.message}")
        }
    }

    companion object {
        const val SCHEMA_VERSION = 3
        const val EXTENSION = "scbackup"
        private const val MANIFEST = "manifest.json"
        private const val DB_ENTRY = "speedcards.db"
        private const val PENDING_DB = "pending.db"
        private const val PENDING_DB_TMP = "pending.db.part"

        fun fileName(at: Long = System.currentTimeMillis()): String =
            "speedcards-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))}.$EXTENSION"

        /** Parses the timestamp back out of [fileName]'s format. */
        fun timestampOf(name: String): Long? = Regex("""speedcards-(\d{8}-\d{4})""").find(name)?.groupValues?.get(1)?.let {
            runCatching { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).parse(it)?.time }.getOrNull()
        }

        private fun stagingDir(context: Context) = File(context.filesDir, "restore")

        fun hasPendingRestore(context: Context) = File(stagingDir(context), PENDING_DB).exists()

        /**
         * Swaps in a staged restore. Must run before the Room database is opened (Application.onCreate).
         * Returns true when a restore was applied.
         */
        fun applyPendingRestore(context: Context): Boolean {
            val stage = stagingDir(context)
            val pending = File(stage, PENDING_DB)
            if (!pending.exists()) { stage.deleteRecursively(); return false }
            val dbFile = context.getDatabasePath(SpeedCardsDatabase.NAME)
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            dbFile.parentFile?.mkdirs()
            pending.copyTo(dbFile, overwrite = true)
            val stagedMedia = File(stage, "media")
            val mediaDir = File(context.filesDir, "media")
            mediaDir.deleteRecursively()
            if (!stagedMedia.renameTo(mediaDir)) {
                mediaDir.mkdirs()
                stagedMedia.listFiles().orEmpty().forEach { it.copyTo(File(mediaDir, it.name), overwrite = true) }
            }
            stage.deleteRecursively()
            return true
        }
    }
}
