package com.speedcards.data.io

import android.content.Context
import com.speedcards.data.repo.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

data class ZipCsvContent(val csvName: String, val csvText: String, val mediaNames: List<String>)

/** A spreadsheet export zipped together with its audio/image files. */
@Singleton
class ZipCsvReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media: MediaRepository,
) {
    suspend fun read(zipFile: File): ZipCsvContent = withContext(Dispatchers.IO) {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("__MACOSX") }.toList()
            val csv = entries.firstOrNull { e -> e.name.substringAfterLast('.').lowercase() in setOf("csv", "tsv", "txt") }
                ?: throw IllegalArgumentException("The zip has no .csv or .tsv file in it.")
            ZipCsvContent(
                csvName = csv.name.substringAfterLast('/'),
                csvText = zip.getInputStream(csv).bufferedReader(Charsets.UTF_8).readText(),
                mediaNames = entries.map { it.name.substringAfterLast('/') }.filter { MediaResolver.isMedia(it) },
            )
        }
    }

    /** Stores the zip's media files and returns a resolver from their original names to stored names. */
    suspend fun storeMedia(zipFile: File): MediaResolver = withContext(Dispatchers.IO) {
        val stored = mutableMapOf<String, String>()
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && MediaResolver.isMedia(it.name) && !it.name.startsWith("__MACOSX") }
                .forEach { e ->
                    val original = e.name.substringAfterLast('/')
                    val tmp = File.createTempFile("zipmedia", null, context.cacheDir)
                    zip.getInputStream(e).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    stored[original] = media.addFile(tmp, original.substringAfterLast('.').lowercase(), MediaResolver.mimeFor(original))
                }
        }
        MediaResolver(stored)
    }
}
