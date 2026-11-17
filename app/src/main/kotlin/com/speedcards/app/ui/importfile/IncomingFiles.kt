package com.speedcards.app.ui.importfile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

enum class ImportKind { APKG, SCTXT, CSV, CSV_ZIP, BACKUP, ANKI_COLLECTION, UNKNOWN }

/** Reads files handed to the app (picker, "Open with", share, NAS import folder). */
@Singleton
class IncomingFiles @Inject constructor(@ApplicationContext private val context: Context) {

    fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    /** Copies the document into the cache so zip/SQLite readers can open it. */
    suspend fun copyToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
        val safeName = name.replace(Regex("""[^\w.\- ]"""), "_")
        val file = File(dir, "${System.nanoTime()}-$safeName")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalArgumentException("Couldn't open $name")
        file
    }

    /** Detects the format by extension, peeking inside zips. */
    suspend fun kindOf(name: String, file: File): ImportKind = withContext(Dispatchers.IO) {
        when (name.substringAfterLast('.', "").lowercase()) {
            "apkg" -> ImportKind.APKG
            "colpkg" -> ImportKind.ANKI_COLLECTION
            "sctxt" -> ImportKind.SCTXT
            "scbackup" -> ImportKind.BACKUP
            "csv", "tsv" -> ImportKind.CSV
            "txt" -> if (looksLikeSctxt(file)) ImportKind.SCTXT else ImportKind.CSV
            "zip" -> runCatching {
                ZipFile(file).use { z ->
                    val names = z.entries().asSequence().map { it.name.lowercase() }.toList()
                    when {
                        names.any { it.startsWith("collection.anki") } -> ImportKind.APKG
                        "manifest.json" in names && "speedcards.db" in names -> ImportKind.BACKUP
                        names.any { it.endsWith(".csv") || it.endsWith(".tsv") || it.endsWith(".txt") } -> ImportKind.CSV_ZIP
                        else -> ImportKind.UNKNOWN
                    }
                }
            }.getOrDefault(ImportKind.UNKNOWN)
            else -> ImportKind.UNKNOWN
        }
    }

    private fun looksLikeSctxt(file: File): Boolean =
        file.bufferedReader().useLines { lines -> lines.take(30).any { it.startsWith("@deck") || it.startsWith("@type") } }
}
