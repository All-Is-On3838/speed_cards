package com.speedcards.data.io

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.speedcards.data.db.CardTemplateDef
import com.speedcards.data.db.FieldDef
import com.speedcards.data.db.FieldLanguage
import com.speedcards.data.db.NoteKind
import com.speedcards.data.db.NoteType
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.repo.MediaRepository
import com.speedcards.scheduler.CardState
import com.speedcards.scheduler.DAY_MS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

class ApkgException(message: String) : Exception(message)

data class ApkgSummary(
    val noteCount: Int,
    val cardCount: Int,
    val deckNames: List<String>,
    val noteTypeNames: List<String>,
    val mediaCount: Int,
)

/**
 * Reads Anki packages exported in the legacy format (collection.anki21 / collection.anki2).
 * Newer packages (collection.anki21b, zstd-compressed) are detected and reported, not imported.
 */
@Singleton
class ApkgImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpeedCardsDatabase,
    private val media: MediaRepository,
    private val documents: DocumentImporter,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun summarize(apkg: File): ApkgSummary = withPackage(apkg) { pkg ->
        val models = pkg.models
        ApkgSummary(
            noteCount = pkg.count("notes"),
            cardCount = pkg.count("cards"),
            deckNames = pkg.usedDeckNames(),
            noteTypeNames = pkg.usedModelIds().mapNotNull { models[it]?.name },
            mediaCount = pkg.mediaMap.size,
        )
    }

    suspend fun import(apkg: File, keepScheduling: Boolean): DocumentImportReport = withPackage(apkg) { pkg ->
        // 1. Media: store every file, remembering original name → stored name.
        val stored = mutableMapOf<String, String>()
        pkg.mediaMap.forEach { (number, original) ->
            val entry = pkg.zip.getEntry(number) ?: return@forEach
            val tmp = File.createTempFile("apkg-media", null, context.cacheDir)
            pkg.zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val ext = original.substringAfterLast('.', "bin").lowercase()
            stored[original] = media.addFile(tmp, ext, MediaResolver.mimeFor(original))
        }
        val resolver = MediaResolver(stored)

        // 2. Note types.
        val typeNames = mutableMapOf<Long, String>()
        for (mid in pkg.usedModelIds()) {
            val model = pkg.models[mid] ?: continue
            typeNames[mid] = ensureNoteType(model)
        }

        // 3. Notes with their decks and card schedules.
        val notes = mutableListOf<ImportNote>()
        val cardsByNote = pkg.cards().groupBy { it.noteId }
        pkg.sqlite.rawQuery("SELECT id, guid, mid, tags, flds FROM notes ORDER BY id", null).use { c ->
            var line = 0
            while (c.moveToNext()) {
                line++
                val noteId = c.getLong(0)
                val model = pkg.models[c.getLong(2)] ?: continue
                val values = c.getString(4).split('\u001f')
                val cards = cardsByNote[noteId].orEmpty()
                val deckId = cards.minByOrNull { it.ord }?.deckId
                notes += ImportNote(
                    id = c.getString(1),
                    deck = deckId?.let { pkg.deckNames[it] } ?: "Default",
                    type = typeNames.getValue(model.id),
                    fields = model.fields.mapIndexed { i, name -> name to resolver.resolve(HtmlText.field(values.getOrElse(i) { "" })) }.toMap(),
                    tags = c.getString(3).split(' ').filter { it.isNotBlank() },
                    cards = cards.map { it.toSchedule(pkg.createdSec) },
                    line = line,
                )
            }
        }
        documents.import(
            ImportDocument(notes),
            DocumentImportOptions(sourceName = apkg.name, keepScheduling = keepScheduling),
        )
    }

    /** Reuses a note type with the same name and fields, otherwise creates one (renamed if the name is taken). */
    private suspend fun ensureNoteType(model: AnkiModel): String {
        val dao = db.noteTypeDao()
        val templates = model.templates.map { (name, q, a) -> CardTemplateDef(name, HtmlText.template(q), HtmlText.template(a)) }
        var name = model.name
        var n = 1
        while (true) {
            val existing = dao.findByName(name) ?: break
            if (existing.fields.map { it.name } == model.fields && existing.templates.size == templates.size) return existing.name
            n++
            name = "${model.name} (Anki${if (n > 2) " $n" else ""})"
        }
        dao.insert(
            NoteType(
                name = name,
                kind = if (model.cloze) NoteKind.CLOZE else NoteKind.STANDARD,
                // Guess: first field is the language being learned, the rest are in your language.
                fields = model.fields.mapIndexed { i, f -> FieldDef(f, if (i == 0) FieldLanguage.FRONT else FieldLanguage.BACK) },
                templates = templates,
            )
        )
        return name
    }

    // --- Package reading -------------------------------------------------------------------------

    private data class AnkiModel(
        val id: Long,
        val name: String,
        val cloze: Boolean,
        val fields: List<String>,
        val templates: List<Triple<String, String, String>>,
    )

    private data class AnkiCard(
        val noteId: Long, val deckId: Long, val ord: Int, val type: Int, val queue: Int,
        val due: Long, val ivl: Int, val factor: Int, val reps: Int, val lapses: Int,
    ) {
        fun toSchedule(createdSec: Long): ImportCardSchedule {
            val state = when (type) { 1 -> CardState.LEARNING; 2 -> CardState.REVIEW; 3 -> CardState.RELEARNING; else -> CardState.NEW }
            val dueMs = when (state) {
                CardState.REVIEW -> createdSec * 1000 + due * DAY_MS
                CardState.LEARNING, CardState.RELEARNING -> due * 1000
                CardState.NEW -> 0L
            }
            return ImportCardSchedule(
                ord = ord, state = state, due = dueMs, intervalDays = ivl.coerceAtLeast(0),
                ease = if (factor > 0) factor / 1000.0 else 0.0, reps = reps, lapses = lapses, suspended = queue == -1,
            )
        }
    }

    private inner class Package(val zip: ZipFile, val sqlite: SQLiteDatabase, val dir: File) {
        val createdSec: Long
        val models: Map<Long, AnkiModel>
        val deckNames: Map<Long, String>
        val mediaMap: Map<String, String>

        init {
            sqlite.rawQuery("SELECT crt, models, decks FROM col LIMIT 1", null).use { c ->
                if (!c.moveToFirst()) throw ApkgException("This file has no Anki collection in it.")
                createdSec = c.getLong(0)
                models = json.parseToJsonElement(c.getString(1)).jsonObject.values.map { parseModel(it.jsonObject) }.associateBy { it.id }
                deckNames = json.parseToJsonElement(c.getString(2)).jsonObject.values.associate { d ->
                    d.jsonObject.getValue("id").jsonPrimitive.long to d.jsonObject.getValue("name").jsonPrimitive.content.replace("\u001f", "::")
                }
            }
            mediaMap = zip.getEntry("media")?.let { e ->
                val text = zip.getInputStream(e).bufferedReader().readText()
                runCatching { json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrNull()
                    ?: throw ApkgException(NEWER_FORMAT)
            } ?: emptyMap()
        }

        fun count(table: String): Int = sqlite.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

        fun usedModelIds(): List<Long> = sqlite.rawQuery("SELECT DISTINCT mid FROM notes", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

        fun usedDeckNames(): List<String> = cards().map { it.deckId }.distinct().mapNotNull { deckNames[it] }.sorted()

        fun cards(): List<AnkiCard> = sqlite.rawQuery(
            "SELECT nid, CASE WHEN odid != 0 THEN odid ELSE did END, ord, type, queue, due, ivl, factor, reps, lapses FROM cards",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    AnkiCard(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getLong(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9))
                )
            }
        }

        private fun parseModel(o: JsonObject) = AnkiModel(
            id = o.getValue("id").jsonPrimitive.long,
            name = o.getValue("name").jsonPrimitive.content,
            cloze = (o["type"]?.jsonPrimitive?.int ?: 0) == 1,
            fields = o.getValue("flds").jsonArray.map { it.jsonObject }.sortedBy { it["ord"]?.jsonPrimitive?.int ?: 0 }
                .map { it.getValue("name").jsonPrimitive.content },
            templates = o.getValue("tmpls").jsonArray.map { it.jsonObject }.sortedBy { it["ord"]?.jsonPrimitive?.int ?: 0 }
                .map { Triple(it.getValue("name").jsonPrimitive.content, it.getValue("qfmt").jsonPrimitive.content, it.getValue("afmt").jsonPrimitive.content) },
        )
    }

    private suspend fun <T> withPackage(apkg: File, block: suspend (Package) -> T): T = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apkg-${System.nanoTime()}").apply { mkdirs() }
        try {
            ZipFile(apkg).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                val collection = listOf("collection.anki21", "collection.anki2").firstOrNull { it in names }
                    ?: throw ApkgException(if ("collection.anki21b" in names) NEWER_FORMAT else "This doesn't look like an Anki package (.apkg).")
                // Only the collection is extracted; media entries are streamed straight from the zip.
                val dbFile = File(dir, "collection.db")
                zip.getInputStream(zip.getEntry(collection)).use { input -> dbFile.outputStream().use { input.copyTo(it) } }
                val sqlite = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    val pkg = Package(zip, sqlite, dir)
                    if ("collection.anki21b" in names && pkg.isUpgradeStub()) throw ApkgException(NEWER_FORMAT)
                    block(pkg)
                } finally {
                    sqlite.close()
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Newer Anki exports include a dummy legacy collection that only says "please update". */
    private fun Package.isUpgradeStub(): Boolean = count("notes") <= 1 &&
        sqlite.rawQuery("SELECT flds FROM notes LIMIT 1", null).use { c -> c.moveToFirst() && c.getString(0).contains("update", ignoreCase = true) }

    companion object {
        const val NEWER_FORMAT =
            "This deck was exported in Anki's newest format, which Speed Cards can't read yet. " +
                "In Anki, export again with \"Support older Anki versions\" ticked."
    }
}
