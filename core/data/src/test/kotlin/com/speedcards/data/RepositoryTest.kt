package com.speedcards.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.speedcards.data.csv.CsvImportOptions
import com.speedcards.data.csv.CsvImporter
import com.speedcards.data.csv.CsvParser
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.repo.CollectionInitializer
import com.speedcards.data.repo.DeckDeletionException
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.MediaRepository
import com.speedcards.data.repo.Defaults
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.repo.QueueResult
import com.speedcards.data.repo.StudyRepository
import com.speedcards.data.template.Sounds
import com.speedcards.data.util.DayClock
import com.speedcards.scheduler.CardState
import com.speedcards.scheduler.Rating
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var db: SpeedCardsDatabase
    private var now = 1_800_000_000_000L
    private val clock = DayClock(clock = { now })
    private lateinit var decks: DeckRepository
    private lateinit var notes: NoteRepository
    private lateinit var study: StudyRepository
    private lateinit var csv: CsvImporter

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpeedCardsDatabase::class.java).allowMainThreadQueries().build()
        CollectionInitializer(db).ensureDefaults()
        decks = DeckRepository(db)
        notes = NoteRepository(db)
        study = StudyRepository(db, clock)
        csv = CsvImporter(db, notes)
    }

    @After fun tearDown() = db.close()

    private suspend fun mainDeck() = decks.all().first { it.name == Defaults.DEFAULT_DECK }
    private suspend fun type(name: String) = notes.noteTypeByName(name)!!

    @Test fun defaultsAreIdempotent() = runTest {
        CollectionInitializer(db).ensureDefaults()
        assertEquals(2, decks.all().size)
        assertEquals(3, db.presetDao().getAll().size)
        assertEquals(6, notes.noteTypes().size) // 4 general + 2 starter-deck types
    }

    @Test fun editingNoteAddsAndRemovesUnstudiedCards() = runTest {
        val vocab = type(Defaults.NOTE_TYPE_VOCAB)
        val id = notes.addNote(vocab, mainDeck().id, listOf("gato", ""))
        assertEquals(listOf(0), notes.get(id)!!.cards.map { it.ord })
        notes.updateNote(id, mainDeck().id, listOf("gato", "cat"))
        assertEquals(listOf(0, 1), notes.get(id)!!.cards.map { it.ord })
        notes.updateNote(id, mainDeck().id, listOf("gato", ""))
        assertEquals(listOf(0), notes.get(id)!!.cards.map { it.ord })
    }

    @Test fun duplicatesAreFoundIgnoringCase() = runTest {
        notes.addNote(type(Defaults.NOTE_TYPE_BASIC), mainDeck().id, listOf("El Gato", "cat"))
        assertEquals(1, notes.findDuplicates(" el gato ").size)
        assertTrue(notes.findDuplicates("perro").isEmpty())
    }

    @Test fun studyFlowAnswersAndUndo() = runTest {
        val deck = mainDeck()
        notes.addNote(type(Defaults.NOTE_TYPE_BASIC), deck.id, listOf("hola", "hello"))
        val first = study.next(deck.id) as QueueResult.Next
        assertEquals(1, first.counts.new)
        assertEquals("hola", first.studyCard.question)
        assertEquals(setOf(Rating.AGAIN, Rating.HARD, Rating.MEDIUM, Rating.EASY), first.studyCard.buttonDelays.keys)

        val outcome = study.answer(first.studyCard.card, Rating.EASY, 1000)
        val after = db.cardDao().get(first.studyCard.card.id)!!
        assertEquals(CardState.REVIEW, after.state)
        assertTrue(study.next(deck.id) is QueueResult.Finished)
        assertEquals(0, study.countsFor(deck.id).new)

        study.undo(outcome.undo)
        assertEquals(CardState.NEW, db.cardDao().get(after.id)!!.state)
        assertEquals(1, study.countsFor(deck.id).new)
    }

    @Test fun learningCardComesBackAfterStep() = runTest {
        val deck = mainDeck()
        notes.addNote(type(Defaults.NOTE_TYPE_BASIC), deck.id, listOf("hola", "hello"))
        val card = (study.next(deck.id) as QueueResult.Next).studyCard.card
        study.answer(card, Rating.AGAIN, 0) // due in 10 minutes; learn-ahead shows it now
        val again = study.next(deck.id)
        assertTrue(again is QueueResult.Next)
        study.answer((again as QueueResult.Next).studyCard.card, Rating.MEDIUM, 0) // due in 1h
        val finished = study.next(deck.id) as QueueResult.Finished
        assertNotNull(finished.nextDueAt)
        now += 61 * 60_000L
        assertTrue(study.next(deck.id) is QueueResult.Next)
    }

    @Test fun newCardLimitIsRespected() = runTest {
        val deck = mainDeck()
        val preset = db.presetDao().get(deck.presetId)!!
        db.presetDao().update(preset.copy(newPerDay = 2))
        repeat(5) { notes.addNote(type(Defaults.NOTE_TYPE_BASIC), deck.id, listOf("w$it", "m$it")) }
        assertEquals(2, study.countsFor(deck.id).new)
        repeat(2) {
            val c = (study.next(deck.id) as QueueResult.Next).studyCard.card
            study.answer(c, Rating.EASY, 0)
        }
        assertEquals(0, study.countsFor(deck.id).new)
        assertTrue(study.next(deck.id) is QueueResult.Finished)
    }

    @Test fun allDecksStudiesEveryRootWithItsOwnLimits() = runTest {
        val first = mainDeck()
        val preset = db.presetDao().get(first.presetId)!!
        db.presetDao().update(preset.copy(newPerDay = 2))
        val second = decks.create("Second", null, first.presetId, null, null)
        repeat(3) { notes.addNote(type(Defaults.NOTE_TYPE_BASIC), first.id, listOf("a$it", "m")) }
        repeat(3) { notes.addNote(type(Defaults.NOTE_TYPE_BASIC), second, listOf("b$it", "m")) }
        notes.addNote(type(Defaults.NOTE_TYPE_VOCAB), decks.inbox().id, listOf("perro", ""))

        // Two new cards from each deck, not two in total; the Inbox isn't included.
        assertEquals(4, study.countsFor(StudyRepository.ALL_DECKS).new)
        val seen = mutableSetOf<Long>()
        repeat(4) {
            val c = (study.next(StudyRepository.ALL_DECKS) as QueueResult.Next).studyCard.card
            seen += c.deckId
            study.answer(c, Rating.EASY, 0)
        }
        assertEquals(setOf(first.id, second), seen)
        assertTrue(study.next(StudyRepository.ALL_DECKS) is QueueResult.Finished)
    }

    @Test fun newCardsWaitUntilDueReviewsAreDone() = runTest {
        val deck = mainDeck()
        val basic = type(Defaults.NOTE_TYPE_BASIC)
        val old = notes.addNote(basic, deck.id, listOf("viejo", "old"))
        repeat(3) { notes.addNote(basic, deck.id, listOf("nuevo$it", "new")) }
        // Make the first card a review that's due now, as if it was learned on an earlier day.
        val oldCard = db.cardDao().forNote(old).first()
        db.cardDao().update(oldCard.copy(state = CardState.REVIEW, due = 0, intervalDays = 3, reps = 3))

        assertEquals(0, study.countsFor(deck.id).new)
        assertEquals(1, study.countsFor(deck.id).review)
        val first = (study.next(deck.id) as QueueResult.Next).studyCard.card
        assertEquals(oldCard.id, first.id)
        study.answer(first, Rating.EASY, 0)

        // Reviews done: new cards appear in the same session.
        assertEquals(3, study.countsFor(deck.id).new)
        val next = (study.next(deck.id) as QueueResult.Next).studyCard.card
        assertEquals(CardState.NEW, next.state)
        study.answer(next, Rating.MEDIUM, 0)
        // A new card's own 10-minute learning step doesn't hold back the rest.
        assertEquals(2, study.countsFor(deck.id).new)
    }

    @Test fun folderScopeStudiesOnlyThatFoldersDecks() = runTest {
        val folders = com.speedcards.data.repo.FolderRepository(db)
        val first = mainDeck()
        val spanish = folders.create("Spanish")
        val inFolder = decks.create("Gatos", null, first.presetId, null, null, folderId = spanish)
        val child = decks.create("Verbos", inFolder, first.presetId, null, null, folderId = spanish)
        assertEquals(null, decks.get(child)!!.folderId) // sub-decks aren't filed themselves
        repeat(2) { notes.addNote(type(Defaults.NOTE_TYPE_BASIC), inFolder, listOf("g$it", "m")) }
        notes.addNote(type(Defaults.NOTE_TYPE_BASIC), child, listOf("v", "m"))
        repeat(5) { notes.addNote(type(Defaults.NOTE_TYPE_BASIC), first.id, listOf("o$it", "m")) }

        assertEquals(3, study.countsFor(StudyRepository.folderScope(spanish)).new)
        assertEquals(5, study.countsFor(StudyRepository.UNFILED).new)
        assertEquals(8, study.countsFor(StudyRepository.ALL_DECKS).new)
        repeat(3) {
            val c = (study.next(StudyRepository.folderScope(spanish)) as QueueResult.Next).studyCard.card
            assertTrue(c.deckId in setOf(inFolder, child))
            study.answer(c, Rating.EASY, 0)
        }
        assertTrue(study.next(StudyRepository.folderScope(spanish)) is QueueResult.Finished)
    }

    @Test fun onlyEmptyFoldersCanBeDeleted() = runTest {
        val folders = com.speedcards.data.repo.FolderRepository(db)
        val id = folders.create("Spanish")
        decks.create("Gatos", null, mainDeck().presetId, null, null, folderId = id)
        val folder = folders.get(id)!!
        assertTrue(runCatching { folders.delete(folder) }.exceptionOrNull() is com.speedcards.data.repo.FolderDeletionException)
        val empty = folders.get(folders.create("Empty"))!!
        folders.delete(empty)
        assertEquals(listOf("Spanish"), folders.all().map { it.name })
    }

    @Test fun inboxCardsAreNeverStudied() = runTest {
        val inbox = decks.inbox()
        notes.addNote(type(Defaults.NOTE_TYPE_VOCAB), inbox.id, listOf("perro", ""))
        assertTrue(study.next(inbox.id) is QueueResult.Finished)
    }

    @Test fun subDecksAreStudiedWithParent() = runTest {
        val parent = mainDeck()
        val child = decks.create("Verbs", parent.id, parent.presetId, null, null)
        notes.addNote(type(Defaults.NOTE_TYPE_BASIC), child, listOf("hablar", "to speak"))
        assertEquals(1, study.countsFor(parent.id).new)
    }

    @Test fun builtInDecksCannotBeDeleted() = runTest {
        try {
            decks.delete(decks.inbox().id)
            fail("expected DeckDeletionException")
        } catch (_: DeckDeletionException) {}
        val parent = mainDeck()
        val child = decks.create("Verbs", parent.id, parent.presetId, null, null)
        notes.addNote(type(Defaults.NOTE_TYPE_BASIC), child, listOf("hablar", "to speak"))
        decks.delete(parent.id)
        assertFalse(decks.all().any { it.id == child || it.id == parent.id })
        assertTrue(notes.findDuplicates("hablar").isEmpty())
    }

    @Test fun csvImportCreatesUpdatesSkipsAndUndoes() = runTest {
        val basic = type(Defaults.NOTE_TYPE_BASIC)
        val deck = mainDeck()
        notes.addNote(basic, deck.id, listOf("existing", "x"))
        val rows = CsvParser.parse("id,Front,Back,tags\nn1,hola,hello,greeting\nn2,existing,dup,\n,,,\nn3,gato,cat,animal pet\n")
        val options = CsvImportOptions(
            noteType = basic, deckId = deck.id, hasHeader = true,
            fieldColumns = csv.guessMapping(basic, rows.first(), 4), tagsColumn = 3, idColumn = 0,
        )
        assertEquals(listOf(1, 2), options.fieldColumns)
        val report = csv.import(rows, options, "test.csv")
        assertEquals(2, report.created)
        assertEquals(listOf(3), report.duplicates)
        val hola = notes.findDuplicates("hola").single()
        assertEquals(listOf("greeting"), notes.get(hola.id)!!.tags)

        // Re-import with an edited row: matched by id, so it updates instead of duplicating.
        val again = csv.import(CsvParser.parse("id,Front,Back\nn1,hola,hi there\n"), options.copy(tagsColumn = null), "test2.csv")
        assertEquals(1, again.updated)
        assertEquals("hi there", notes.get(hola.id)!!.note.fields[1])

        assertEquals(2, csv.undo(report.batchId))
        assertTrue(notes.findDuplicates("gato").isEmpty())
        assertEquals(1, notes.findDuplicates("existing").size)
    }

    @Test fun mediaIsDeduplicatedAndUnusedFilesAreCleaned() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val media = MediaRepository(context, db)
        media.mediaDir.listFiles()?.forEach { it.delete() }
        fun temp(content: String) = java.io.File.createTempFile("tmp", ".m4a", context.cacheDir).apply { writeText(content) }

        val word = media.addFile(temp("word audio"), "m4a", "audio/mp4")
        assertEquals(word, media.addFile(temp("word audio"), "m4a", "audio/mp4")) // same content, same file
        val orphan = media.addFile(temp("orphan"), "m4a", "audio/mp4")
        val noteId = notes.addNote(type(Defaults.NOTE_TYPE_BASIC), mainDeck().id, listOf("gato ${Sounds.tag(word)}", "cat"))
        val card = notes.get(noteId)!!.cards.first()
        media.saveRecording(card.id, temp("my take"))

        val usage = media.usage()
        assertEquals(3, usage.fileCount)
        assertEquals(1, usage.unusedCount)
        assertEquals(1, media.deleteUnused())
        assertFalse(media.file(orphan).exists())
        assertTrue(media.file(word).exists())
        assertEquals(1, media.recordings(card.id).size)
    }
}
