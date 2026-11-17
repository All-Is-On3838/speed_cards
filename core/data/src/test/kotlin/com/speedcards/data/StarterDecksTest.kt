package com.speedcards.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.speedcards.data.db.Deck
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.io.DocumentImporter
import com.speedcards.data.io.SeedFile
import com.speedcards.data.io.SeedSource
import com.speedcards.data.io.StarterDecks
import com.speedcards.data.repo.CardGenerator
import com.speedcards.data.repo.CollectionInitializer
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.Defaults
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.repo.resolve
import com.speedcards.data.template.Pinyin
import com.speedcards.data.template.RenderOptions
import com.speedcards.data.template.TemplateRenderer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarterDecksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpeedCardsDatabase
    private lateinit var notes: NoteRepository
    private lateinit var starter: StarterDecks

    /** The real bundled decks. */
    private val seedDir = File("../../app/src/main/assets/seed")
    private fun realSeeds() = SeedSource { seedDir.listFiles()!!.filter { it.name.endsWith(".sctxt") }.sorted().map { SeedFile(it.name, it.readText()) } }

    @Before fun setUp() = runTest {
        context.getSharedPreferences("starter_decks", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, SpeedCardsDatabase::class.java).allowMainThreadQueries().build()
        CollectionInitializer(db).ensureDefaults()
        notes = NoteRepository(db)
        starter = StarterDecks(context, db, DocumentImporter(db, notes, LinkRepository(db))).apply { source = realSeeds() }
    }

    @After fun tearDown() = db.close()

    private val spanishTopics = setOf("Supervivencia / Viaje", "Tiro con arco", "Gimnasio / Pesas", "Gatos")
    private val chineseTopics = setOf("旅行 Travel survival", "射箭 Archery", "健身 Gym & weightlifting", "猫 Cats")

    private suspend fun folderId(name: String): Long = db.folderDao().findByName(name)!!.id

    /** Top-level deck names in a folder. */
    private suspend fun decksIn(folder: String): Set<String> {
        val id = folderId(folder)
        return DeckRepository(db).all().filter { it.parentId == null && it.folderId == id }.map { it.name }.toSet()
    }

    /** Notes in a folder's decks, sub-decks included. */
    private suspend fun countNotes(folder: String): Int {
        val decks = DeckRepository(db).all()
        val id = folderId(folder)
        val ids = decks.filter { it.parentId == null && it.folderId == id }.flatMap { DeckRepository.subtree(decks, it.id) }
        return db.noteDao().countInDecks(ids)
    }

    /** Puts [topics] back under one parent deck with no folder, as installs from before folders had them. */
    private suspend fun nestUnderLegacyRoot(rootName: String, topics: Set<String>): Long {
        val presetId = db.presetDao().getAll().first().id
        val root = db.deckDao().insert(Deck(name = rootName, presetId = presetId, builtIn = true))
        DeckRepository(db).all().filter { it.name in topics }.forEach { db.deckDao().update(it.copy(parentId = root, folderId = null)) }
        return root
    }

    @Test fun installsTopicsAsTopLevelBuiltInDecksInLanguageFolders() = runTest {
        val added = starter.ensureInstalled()
        assertEquals(403, added)
        assertEquals(listOf("Chinese", "Spanish"), db.folderDao().getAll().map { it.name })
        assertEquals(spanishTopics, decksIn("Spanish"))
        assertEquals(chineseTopics, decksIn("Chinese"))
        assertEquals(200, countNotes("Spanish"))
        assertEquals(203, countNotes("Chinese"))
        val decks = DeckRepository(db).all()
        val topics = decks.filter { it.name in spanishTopics + chineseTopics }
        assertEquals(8, topics.size)
        assertTrue(topics.all { it.builtIn && it.parentId == null })
        assertTrue(decks.none { it.name == "Spanish (Mexico)" || it.name == "中文 Chinese" })
        assertEquals("es-MX", topics.first { it.name == "Gatos" }.languageFront)
        assertEquals("zh-CN", topics.first { it.name == "猫 Cats" }.languageFront)
        // Built-in decks can't be deleted.
        assertTrue(runCatching { DeckRepository(db).delete(topics.first().id) }.isFailure)
        // Every Spanish note has its 3 cards (recognition, recall, sentence); Chinese has 3 (reading, recall, listening).
        val barra = db.noteDao().findByGuid("seed-es-gym-001")!!
        assertEquals(listOf(0, 1, 2), db.cardDao().forNote(barra.id).map { it.ord })
        val zh = db.noteDao().findByGuid("seed-zh-cats-001")!!
        assertEquals(listOf(0, 1, 2), db.cardDao().forNote(zh.id).map { it.ord })
        // Links from the files were created (69 Spanish + 77 Chinese targets).
        val allIds = db.noteDao().searchOnce("", 1000).map { it.id }
        assertEquals(146, db.linkDao().touching(allIds).size)
    }

    @Test fun unfiledStarterDeckIsFiledAgainWithoutDuplicateFolders() = runTest {
        starter.ensureInstalled()
        val gatos = DeckRepository(db).all().first { it.name == "Gatos" }
        db.deckDao().update(gatos.copy(folderId = null))
        starter.ensureInstalled()
        assertEquals(listOf("Chinese", "Spanish"), db.folderDao().getAll().map { it.name })
        assertEquals(folderId("Spanish"), db.deckDao().get(gatos.id)!!.folderId)
    }

    @Test fun oldNestedLayoutIsFlattenedKeepingTheSameDecks() = runTest {
        starter.ensureInstalled()
        val before = DeckRepository(db).all().filter { it.name in spanishTopics }.map { it.id }.toSet()
        // Before folders there was no "Spanish" folder either.
        db.folderDao().delete(db.folderDao().findByName("Spanish")!!)
        val root = nestUnderLegacyRoot("Spanish (Mexico)", spanishTopics)
        // A user's own sub-deck under a topic moves along with it.
        val gatos = DeckRepository(db).all().first { it.name == "Gatos" }
        val mine = db.deckDao().insert(Deck(name = "Mine", parentId = gatos.id, presetId = gatos.presetId))

        assertEquals(0, starter.ensureInstalled())
        assertEquals(null, db.deckDao().get(root))
        assertEquals(spanishTopics, decksIn("Spanish"))
        assertEquals(200, countNotes("Spanish"))
        assertEquals(gatos.id, db.deckDao().get(mine)!!.parentId)
        // Same deck rows as before, so cards and review history are untouched.
        assertEquals(before, DeckRepository(db).all().filter { it.name in spanishTopics }.map { it.id }.toSet())
    }

    @Test fun oldParentDeckWithItsOwnNotesIsKeptInTheFolder() = runTest {
        starter.ensureInstalled()
        val root = nestUnderLegacyRoot("中文 Chinese", chineseTopics)
        notes.addNote(notes.noteTypeByName(Defaults.NOTE_TYPE_BASIC)!!, root, listOf("你好", "hello"))

        starter.ensureInstalled()
        assertEquals(chineseTopics + "中文 Chinese", decksIn("Chinese"))
        assertEquals(204, countNotes("Chinese"))
    }

    @Test fun secondRunIsANoOpAndMissingDecksComeBack() = runTest {
        starter.ensureInstalled()
        assertEquals(0, starter.ensureInstalled())
        // Simulate restoring an old backup without the Spanish decks.
        DeckRepository(db).all().filter { it.name in spanishTopics }.forEach { db.deckDao().delete(it) }
        assertEquals(200, starter.ensureInstalled())
        assertEquals(200, countNotes("Spanish"))
    }

    @Test fun newerSeedVersionAddsOnlyNewNotesAndKeepsEdits() = runTest {
        val small = "# seed-version: 1\n@deck Test::A\n@type Basic\n\nid: t1\nFront: uno\nBack: one\n"
        starter.source = SeedSource { listOf(SeedFile("test.sctxt", small)) }
        starter.ensureInstalled()
        val t1 = db.noteDao().findByGuid("t1")!!
        notes.updateNote(t1.id, t1.deckId, listOf("uno", "one (edited)"))
        val v2 = small.replace("seed-version: 1", "seed-version: 2").replace("Back: one", "Back: ONE") + "\nid: t2\nFront: dos\nBack: two\n"
        starter.source = SeedSource { listOf(SeedFile("test.sctxt", v2)) }
        assertEquals(1, starter.ensureInstalled())
        assertEquals("one (edited)", notes.get(t1.id)!!.note.fields[1])
    }

    @Test fun chineseCardsRenderBothScriptsTonesAndListening() = runTest {
        starter.ensureInstalled()
        val n = db.noteDao().findByGuid("seed-zh-travel-001")!!
        val type = notes.noteType(n.noteTypeId)!!
        val reading = CardGenerator.render(type, n.fields, 0, RenderOptions(traditionalFirst = false))
        assertTrue(reading.question.startsWith("厕所"))
        assertTrue(reading.question.contains("${TemplateRenderer.SECONDARY_START}廁所"))
        assertTrue(reading.answer.contains("${TemplateRenderer.TONE_START}4cè${TemplateRenderer.TONE_END}"))
        val trad = CardGenerator.render(type, n.fields, 0, RenderOptions(traditionalFirst = true))
        assertTrue(trad.question.startsWith("廁所"))
        val listening = CardGenerator.render(type, n.fields, 2)
        assertEquals(TemplateRenderer.SPEAKER, listening.question)
        val deck = db.deckDao().get(n.deckId)!!
        val speech = CardGenerator.audio(type, n.fields, 2).question.resolve(deck).speech
        assertEquals("厕所", speech.single().text)
        assertEquals("zh-CN", speech.single().languageTag)
    }

    @Test fun chineseStarterFrontsAreMutedButListeningStillPlays() = runTest {
        starter.ensureInstalled()
        val zh = db.noteDao().findByGuid("seed-zh-travel-001")!!
        assertFalse(zh.readFront)
        assertTrue(db.noteDao().findByGuid("seed-es-travel-001")!!.readFront)
        val type = notes.noteType(zh.noteTypeId)!!
        val deck = db.deckDao().get(zh.deckId)!!
        // Reading card: silent until flipped, then the characters are read before the meaning.
        val reading = CardGenerator.audio(type, zh.fields, 0, zh.readFront)
        assertTrue(reading.question.resolve(deck).isEmpty)
        assertEquals("厕所", reading.answer.resolve(deck).speech.first().text)
        // Listening card: the audio is the question, so it still plays.
        assertEquals("厕所", CardGenerator.audio(type, zh.fields, 2, zh.readFront).question.resolve(deck).speech.single().text)
    }

    @Test fun spanishSentenceCardBlanksTheWord() = runTest {
        starter.ensureInstalled()
        val n = db.noteDao().findByGuid("seed-es-travel-001")!!
        val type = notes.noteType(n.noteTypeId)!!
        val card = CardGenerator.render(type, n.fields, 2)
        assertTrue(card.question.contains("${TemplateRenderer.HIGHLIGHT_START}[…]"))
        assertFalse(card.question.contains("{{c1::"))
        // The recognition card shows the example without cloze markup.
        assertFalse(CardGenerator.render(type, n.fields, 0).answer.contains("{{c1::"))
        val deck = db.deckDao().get(n.deckId)!!
        val q = CardGenerator.audio(type, n.fields, 2).question.resolve(deck).speech
        assertTrue(q.first().text.contains("…"))
        assertEquals("es-MX", q.first().languageTag)
    }

    @Test fun pinyinSegmentation() {
        assertEquals(listOf("cè", "suǒ"), Pinyin.segment("cèsuǒ"))
        assertEquals(listOf("gōng", "jiàn"), Pinyin.segment("gōngjiàn"))
        assertEquals(listOf("Qǐng", "wèn"), Pinyin.segment("Qǐngwèn"))
        assertEquals(listOf("fān", "gàn"), Pinyin.segment("fāngàn"))
        assertEquals(listOf("xiān"), Pinyin.segment("xiān"))
        assertEquals(listOf("kè", "qi"), Pinyin.segment("kèqi"))
        assertEquals(0, Pinyin.toneOf("qi"))
        assertEquals(3, Pinyin.toneOf("nǚ"))
        val marked = Pinyin.markTones("Xī'ān")
        assertEquals(2, marked.count { it == TemplateRenderer.TONE_START })
        assertTrue(marked.contains("'"))
    }

    @Test fun starterNoteTypesExist() = runTest {
        assertTrue(notes.noteTypeByName(Defaults.NOTE_TYPE_SPANISH) != null)
        assertTrue(notes.noteTypeByName(Defaults.NOTE_TYPE_CHINESE) != null)
        assertTrue(LinkRepository(db).types().any { it.name == "same measure word" && it.symmetric })
    }
}
