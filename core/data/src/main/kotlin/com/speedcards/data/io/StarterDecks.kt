package com.speedcards.data.io

import android.content.Context
import androidx.room.withTransaction
import com.speedcards.data.db.Folder
import com.speedcards.data.db.SpeedCardsDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A bundled `.sctxt` deck. Its first line carries `seed-version: N`. */
data class SeedFile(val key: String, val text: String) {
    val version: Int get() = Regex("""seed-version:\s*(\d+)""").find(text.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)?.toInt() ?: 1
}

/** Where seed files come from: app assets in production, strings in tests. */
fun interface SeedSource {
    fun files(): List<SeedFile>
}

/**
 * Installs the required starter decks (Spanish, the Spanish frequency lists, Chinese). Each topic (travel, archery, gym, cats) is a top-level deck.
 * - Missing decks (first launch, or after restoring a backup without them) are installed in full.
 * - A newer bundled version adds notes that aren't there yet; notes you already have, including your edits, are left alone.
 * - Notes whose front is Chinese characters are added with "read front aloud" off.
 * - The decks and their sub-decks are marked built-in so they can't be deleted.
 * - Each starter deck is filed into its language's folder ([FOLDERS]) whenever it isn't in one.
 * - Installs from before folders kept the topics under one parent deck ([LEGACY_ROOTS]); those topics are
 *   moved up into the folder and the parent is removed once it's empty.
 */
@Singleton
class StarterDecks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpeedCardsDatabase,
    private val importer: DocumentImporter,
) {
    var source: SeedSource = SeedSource {
        context.assets.list("seed").orEmpty().filter { it.endsWith(".sctxt") }.sorted().map { name ->
            SeedFile(name, context.assets.open("seed/$name").bufferedReader().readText())
        }
    }

    private val prefs get() = context.getSharedPreferences("starter_decks", Context.MODE_PRIVATE)

    /** Returns how many notes were added. */
    suspend fun ensureInstalled(): Int = withContext(Dispatchers.IO) {
        var added = 0
        val rootsByFile = mutableMapOf<String, List<String>>()
        for (file in source.files()) {
            flattenLegacyRoot(file.key)
            val doc = SctxtParser.parse(file.text)
            val roots = doc.notes.map { DeckPathResolver.normalize(it.deck).substringBefore("::") }.distinct()
            rootsByFile[file.key] = roots
            val missing = roots.any { db.deckDao().findByName(it, null) == null }
            val installed = prefs.getInt(file.key, 0)
            if (!missing && installed >= file.version) continue

            // Every deck path in the file (and its parents) is built in; parents share the sub-decks' languages.
            val paths = doc.notes.map { DeckPathResolver.normalize(it.deck) }.distinct()
            val settings = buildMap<String, DeckSettings> {
                for (path in paths) {
                    val langs = doc.decks.entries.firstOrNull { DeckPathResolver.normalize(it.key) == path }?.value
                    val parts = path.split("::")
                    for (i in parts.indices) {
                        val p = parts.take(i + 1).joinToString("::")
                        val existing = get(p)
                        put(p, DeckSettings(existing?.languageFront ?: langs?.languageFront, existing?.languageBack ?: langs?.languageBack, builtIn = true))
                    }
                }
            }
            val report = importer.import(
                doc.copy(decks = settings),
                DocumentImportOptions(
                    sourceName = "Starter deck: ${file.key}", keepScheduling = false, skipExisting = true, allowDuplicates = true,
                    muteChineseFronts = true,
                ),
            )
            added += report.base.created
            prefs.edit().putInt(file.key, file.version).apply()
        }
        fileIntoFolders(rootsByFile)
        added
    }

    /** Puts starter decks that aren't in a folder (new installs, upgrades, restored older backups) into theirs. */
    private suspend fun fileIntoFolders(rootsByFile: Map<String, List<String>>) {
        val deckDao = db.deckDao()
        val folderDao = db.folderDao()
        for ((key, roots) in rootsByFile) {
            for (root in roots) {
                val deck = deckDao.findByName(root, null) ?: continue
                if (deck.folderId != null && folderDao.get(deck.folderId) != null) continue
                deckDao.update(deck.copy(folderId = folderFor(key, root)))
            }
        }
    }

    private suspend fun folderFor(key: String, fallbackName: String): Long {
        val name = FOLDERS[key] ?: fallbackName
        return db.folderDao().findByName(name)?.id ?: db.folderDao().insert(Folder(name = name))
    }

    /**
     * Moves the decks under an old "Spanish (Mexico)" / "中文 Chinese" parent up to the top level, into the
     * parent's folder (or the language's folder), keeping their cards and history. The parent is deleted
     * when it holds no notes of its own; otherwise it stays as a deck in the same folder.
     */
    private suspend fun flattenLegacyRoot(key: String) {
        val rootName = LEGACY_ROOTS[key] ?: return
        db.withTransaction {
            val deckDao = db.deckDao()
            val root = deckDao.findByName(rootName, null) ?: return@withTransaction
            val folderId = root.folderId?.takeIf { db.folderDao().get(it) != null } ?: folderFor(key, rootName)
            deckDao.getAll().filter { it.parentId == root.id }.forEach { deckDao.update(it.copy(parentId = null, folderId = folderId)) }
            if (db.noteDao().countInDecks(listOf(root.id)) == 0) deckDao.delete(root)
            else deckDao.update(root.copy(folderId = folderId))
        }
    }

    companion object {
        /** Folder for each bundled seed file; a file not listed gets a folder named after its deck. */
        val FOLDERS = mapOf(
            "spanish_mx.sctxt" to "Spanish",
            "spanish_mx_frequency.sctxt" to "Spanish",
            "chinese.sctxt" to "Chinese",
        )

        /** The single parent deck each seed file used before folders existed. */
        val LEGACY_ROOTS = mapOf(
            "spanish_mx.sctxt" to "Spanish (Mexico)",
            "chinese.sctxt" to "中文 Chinese",
        )
    }
}
