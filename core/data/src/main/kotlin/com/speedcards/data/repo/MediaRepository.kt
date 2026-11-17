package com.speedcards.data.repo

import android.content.Context
import com.speedcards.data.db.Media
import com.speedcards.data.db.RecordingRow
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.db.UserRecording
import com.speedcards.data.template.Sounds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class MediaUsage(
    val fileCount: Int,
    val totalBytes: Long,
    val unusedCount: Int,
    val unusedBytes: Long,
)

/**
 * Audio and image files live in `files/media/`, named by content hash so identical files are
 * stored once and backups can mirror them incrementally.
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpeedCardsDatabase,
) {
    val mediaDir: File get() = File(context.filesDir, "media").apply { mkdirs() }

    fun file(fileName: String): File = File(mediaDir, fileName)

    /** Moves [source] into the media folder (deduplicated) and returns its media file name. */
    suspend fun addFile(source: File, extension: String, mime: String): String = withContext(Dispatchers.IO) {
        val hash = sha256(source)
        val dao = db.mediaDao()
        dao.findByHash(hash)?.let { existing ->
            if (file(existing.fileName).exists()) { source.delete(); return@withContext existing.fileName }
        }
        val name = "${hash.take(20)}.$extension"
        val target = file(name)
        // Copy rather than rename: files moved out of cacheDir keep cache-quota ownership.
        source.copyTo(target, overwrite = true)
        source.delete()
        dao.insert(Media(fileName = name, mime = mime, sizeBytes = target.length(), hash = hash))
        name
    }

    suspend fun saveRecording(cardId: Long, source: File): RecordingRow? {
        val name = addFile(source, "m4a", "audio/mp4")
        val media = db.mediaDao().findByName(name) ?: return null
        val id = db.mediaDao().insertRecording(UserRecording(cardId = cardId, mediaId = media.id, timestamp = System.currentTimeMillis()))
        return RecordingRow(id, cardId, System.currentTimeMillis(), name, media.id)
    }

    suspend fun recordings(cardId: Long): List<RecordingRow> = db.mediaDao().recordingsForCard(cardId)

    suspend fun deleteRecording(row: RecordingRow) = db.mediaDao().deleteRecording(row.id)

    suspend fun usage(): MediaUsage = withContext(Dispatchers.IO) {
        val files = mediaDir.listFiles().orEmpty().filter { it.isFile }
        val unused = unusedFiles(files)
        MediaUsage(files.size, files.sumOf { it.length() }, unused.size, unused.sumOf { it.length() })
    }

    /** Deletes files no note or saved recording refers to. Returns how many were removed. */
    suspend fun deleteUnused(): Int = withContext(Dispatchers.IO) {
        val unused = unusedFiles(mediaDir.listFiles().orEmpty().filter { it.isFile })
        val names = unused.map { it.name }.toSet()
        val rows = db.mediaDao().all().filter { it.fileName in names }
        unused.forEach { it.delete() }
        if (rows.isNotEmpty()) db.mediaDao().deleteIds(rows.map { it.id })
        unused.size
    }

    private suspend fun unusedFiles(files: List<File>): List<File> {
        val referenced = db.mediaDao().allNoteFieldsJson().flatMap { Sounds.extract(it) }.toMutableSet()
        val recordingIds = db.mediaDao().recordingMediaIds().toSet()
        db.mediaDao().all().filter { it.id in recordingIds }.forEach { referenced += it.fileName }
        return files.filter { it.name !in referenced }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
