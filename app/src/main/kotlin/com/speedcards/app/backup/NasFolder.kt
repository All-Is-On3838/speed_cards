package com.speedcards.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.speedcards.data.backup.BackupManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** The chosen folder lost its permission or vanished; retrying won't help until it's re-picked. */
class FolderAccessException(message: String) : Exception(message)

data class RemoteBackup(val name: String, val timestamp: Long, val size: Long, val uri: Uri)
data class RemoteFile(val name: String, val size: Long, val uri: Uri)

/**
 * Backup folder chosen with the system folder picker (Storage Access Framework). It can be a NAS share
 * through an SMB documents provider or a NAS vendor's app. Layout inside it:
 * ```
 * speedcards-YYYYMMDD-HHmm.scbackup
 * media/<hash>.m4a …
 * import/        ← drop files here on your computer to import them
 * import/done/
 * ```
 */
@Singleton
class NasFolder @Inject constructor(@ApplicationContext private val context: Context) {

    fun persistPermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun releasePermission(uri: Uri) = runCatching {
        context.contentResolver.releasePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** Readable name for showing which folder is chosen. */
    fun displayName(uri: Uri): String =
        root(uri)?.name ?: DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { uri.toString() }

    suspend fun listBackups(treeUri: Uri): List<RemoteBackup> = io {
        requireRoot(treeUri).listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (!name.endsWith(".${BackupManager.EXTENSION}")) return@mapNotNull null
            RemoteBackup(name, BackupManager.timestampOf(name) ?: f.lastModified(), f.length(), f.uri)
        }.sortedByDescending { it.timestamp }
    }

    /** Uploads [file] as [name]: written to "name.part", size-checked, then renamed so half-written files never look valid. */
    suspend fun upload(treeUri: Uri, file: File, name: String, subfolder: String? = null): Unit = io {
        val dir = subfolder?.let { child(requireRoot(treeUri), it, create = true)!! } ?: requireRoot(treeUri)
        dir.findFile(name)?.delete()
        dir.findFile("$name.part")?.delete()
        val part = dir.createFile("application/octet-stream", "$name.part") ?: throw FolderAccessException("Couldn't create a file in the backup folder.")
        context.contentResolver.openOutputStream(part.uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw FolderAccessException("Couldn't write to the backup folder.")
        val written = part.length()
        if (written != file.length()) {
            part.delete()
            throw IllegalStateException("Upload incomplete ($written of ${file.length()} bytes).")
        }
        // Some providers can't rename; then the .part file is copied to the final name instead.
        if (!part.renameTo(name)) {
            val final = dir.createFile("application/octet-stream", name) ?: throw FolderAccessException("Couldn't create $name.")
            context.contentResolver.openOutputStream(final.uri, "w")!!.use { out ->
                context.contentResolver.openInputStream(part.uri)!!.use { it.copyTo(out) }
            }
            part.delete()
        }
    }

    suspend fun mediaNames(treeUri: Uri): Set<String> = io {
        child(requireRoot(treeUri), "media", create = false)?.listFiles()?.mapNotNull { it.name }?.toSet() ?: emptySet()
    }

    suspend fun delete(uri: Uri): Boolean = io { DocumentFile.fromSingleUri(context, uri)?.delete() == true }

    /** Copies a remote document to a local file. */
    suspend fun download(uri: Uri, target: File): Unit = io {
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw FileNotFoundException(uri.toString())
    }

    suspend fun downloadMedia(treeUri: Uri, name: String, target: File): Boolean = io {
        val f = child(requireRoot(treeUri), "media", create = false)?.findFile(name) ?: return@io false
        context.contentResolver.openInputStream(f.uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } != null
    }

    /** Files waiting in the `import/` folder (not sub-folders). */
    suspend fun importFiles(treeUri: Uri): List<RemoteFile> = io {
        child(requireRoot(treeUri), "import", create = true)?.listFiles()
            ?.filter { it.isFile && it.name?.endsWith(".part") == false }
            ?.map { RemoteFile(it.name.orEmpty(), it.length(), it.uri) }
            ?.sortedBy { it.name } ?: emptyList()
    }

    /** Moves a processed import file into `import/done/` (copy + delete when moving isn't supported). */
    suspend fun markImported(treeUri: Uri, file: RemoteFile): Unit = io {
        val importDir = child(requireRoot(treeUri), "import", create = true)!!
        val done = child(importDir, "done", create = true)!!
        val moved = runCatching {
            DocumentsContract.moveDocument(context.contentResolver, file.uri, importDir.uri, done.uri)
        }.getOrNull()
        if (moved == null) {
            val stamp = System.currentTimeMillis()
            val copy = done.createFile("application/octet-stream", "${stamp}-${file.name}") ?: return@io
            context.contentResolver.openOutputStream(copy.uri)!!.use { out ->
                context.contentResolver.openInputStream(file.uri)!!.use { it.copyTo(out) }
            }
            DocumentFile.fromSingleUri(context, file.uri)?.delete()
        }
    }

    private fun root(treeUri: Uri): DocumentFile? = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()

    private fun requireRoot(treeUri: Uri): DocumentFile {
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        if (!granted) throw FolderAccessException("no permission for the folder any more; choose it again in Backup & restore")
        val root = root(treeUri)
        if (root == null || !root.exists()) {
            throw FolderAccessException("the folder isn't available (is the NAS on and are you on home Wi-Fi?)")
        }
        return root
    }

    private fun child(parent: DocumentFile, name: String, create: Boolean): DocumentFile? =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: if (create) parent.createDirectory(name) else null

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: SecurityException) {
            throw FolderAccessException("no permission for the folder any more; choose it again in Backup & restore")
        }
    }
}
