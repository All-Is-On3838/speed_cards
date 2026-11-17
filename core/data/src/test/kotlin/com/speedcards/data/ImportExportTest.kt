package com.speedcards.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.speedcards.data.backup.BackupManager
import com.speedcards.data.backup.Retention
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.io.ApkgException
import com.speedcards.data.io.ApkgImporter
import com.speedcards.data.io.DocumentImportOptions
import com.speedcards.data.io.DocumentImporter
import com.speedcards.data.io.HtmlText
import com.speedcards.data.io.MediaResolver
import com.speedcards.data.io.SctxtParser
import com.speedcards.data.repo.CollectionInitializer
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.Defaults
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.MediaRepository
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.template.Sounds
import com.speedcards.scheduler.CardState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportExportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpeedCardsDatabase
    private lateinit var notes: NoteRepository
    private lateinit var links: LinkRepository
    private lateinit var media: MediaRepository
    private lateinit var importer: DocumentImporter

    @Before fun setUp() = runTest {
        context.deleteDatabase(SpeedCardsDatabase.NAME)
        File(context.filesDir, "media").deleteRecursively()
        db = openDb()
        CollectionInitializer(db).ensureDefaults()
        notes = NoteRepository(db)
        links = LinkRepository(db)
        media = MediaRepository(context, db)
        importer = DocumentImporter(db, notes, links)
    }

    @After fun tearDown() = db.close()

    private fun openDb() = Room.databaseBuilder(context, SpeedCardsDatabase::class.java, SpeedCardsDatabase.NAME).allowMainThreadQueries().build()

    // --- HTML -----------------------------------------------------------------------------------

    @Test fun htmlFieldsBecomePlainText() {
        assertEquals("el gato\nduerme", HtmlText.field("<div>el <b>gato</b></div><div>duerme</div>"))
        assertEquals("a & b < c", HtmlText.field("a &amp; b&nbsp;&lt; c"))
        assertEquals("[img:cat.jpg] gato [sound:g.mp3]", HtmlText.field("""<img src="cat.jpg"> gato [sound:g.mp3]"""))
        assertEquals("ñ", HtmlText.field("&#241;"))
    }

    @Test fun ankiTemplatesAreSimplified() {
        val back = """<style>.x{}</style>{{FrontSide}}<hr id=answer>{{Back}}<br>{{type:Front}}{{hint:Notes}}"""
        assertEquals("{{FrontSide}}\n---\n{{Back}}\n{{Notes}}", HtmlText.template(back))
    }

    @Test fun mediaResolverRewritesReferencesAndBareNames() {
        val r = MediaResolver(mapOf("hola.mp3" to "abc.mp3", "gato.png" to "def.png"))
        assertEquals("hi [sound:abc.mp3]", r.resolve("hi [sound:hola.mp3]"))
        assertEquals("[sound:abc.mp3]", r.resolve("hola.mp3"))
        assertEquals("[img:def.png]", r.resolve(" gato.png "))
        assertEquals("[sound:missing.mp3]", r.resolve("[sound:missing.mp3]"))
    }

    // --- .sctxt --------------------------------------------------------------------------------

    private val sample = """
        # gym words
        @deck Spanish::Gym
        @type Vocab
        @tags gym
        @languages es-MX en-US

        id: gym-barra
        Word: la barra
        Meaning: barbell
        Example: Levanto la barra.
          Todos los días.
        tags: equipment
        links: collocation [[las mancuernas]]; synonym [[gym-pesa]]

        id: gym-pesa
        Word: la pesa
        Meaning: weight
        ---
        Word: las mancuernas
        Meaning: dumbbells
    """.trimIndent()

    @Test fun sctxtParsesDirectivesFieldsAndLinks() {
        val doc = SctxtParser.parse(sample)
        assertEquals(3, doc.notes.size)
        val barra = doc.notes.first()
        assertEquals("gym-barra", barra.id)
        assertEquals("Spanish::Gym", barra.deck)
        assertEquals("Vocab", barra.type)
        assertEquals("Levanto la barra.\nTodos los días.", barra.fields["Example"])
        assertEquals(listOf("gym", "equipment"), barra.tags)
        assertEquals(2, barra.links.size)
        assertEquals("gym-pesa", barra.links[1].target)
        assertEquals("es-MX", doc.decks["Spanish::Gym"]?.languageFront)
        assertEquals(listOf("gym"), doc.notes[2].tags)
    }

    @Test fun sctxtRoundTrips() {
        val doc = SctxtParser.parse(sample)
        val again = SctxtParser.parse(SctxtParser.write(doc.notes))
        assertEquals(doc.notes.map { it.copy(line = 0) }, again.notes.map { it.copy(line = 0) })
    }

    @Test fun sctxtReportsBadLines() {
        try { SctxtParser.parse("@deck X\n@colour red"); fail() } catch (e: SctxtParser.ParseException) {
            assertTrue(e.message!!.contains("Line 2"))
        }
    }

    @Test fun documentImportCreatesNestedDecksLinksAndUpdatesOnReimport() = runTest {
        val report = importer.import(SctxtParser.parse(sample), DocumentImportOptions("gym.sctxt"))
        assertEquals(3, report.base.created)
        assertEquals(2, report.linksCreated)
        assertEquals(2, report.decksCreated)
        val decks = DeckRepository(db).all()
        val gym = decks.first { it.name == "Gym" }
        assertEquals("Spanish", decks.first { it.id == gym.parentId }.name)
        assertEquals("es-MX", gym.languageFront)
        val barra = db.noteDao().findByGuid("gym-barra")!!
        assertEquals(setOf("collocation", "synonym"), links.links(barra.id).map { it.label }.toSet())

        // Re-import with an edit: updated, not duplicated; links not doubled.
        val edited = SctxtParser.parse(sample.replace("Meaning: barbell", "Meaning: bar"))
        val again = importer.import(edited, DocumentImportOptions("gym.sctxt"))
        assertEquals(0, again.base.created)
        assertEquals(2, again.base.updated)
        assertEquals(1, again.base.duplicates.size) // the id-less note matches its earlier copy
        assertEquals("bar", notes.get(barra.id)!!.note.fields[1])
        assertEquals(2, links.links(barra.id).size)
    }

    @Test fun documentImportReportsProblemsWithoutFailingWholeFile() = runTest {
        val text = "@type Nope\nFront: x\n\n@type Basic\nFront: ok\nColour: red\nlinks: synonym [[nowhere]]"
        val report = importer.import(SctxtParser.parse(text), DocumentImportOptions("bad.sctxt"))
        assertEquals(1, report.base.created)
        val reasons = report.base.failures.map { it.reason }
        assertTrue(reasons.any { "unknown note type" in it })
        assertTrue(reasons.any { "unknown field" in it })
        assertTrue(reasons.any { "not found" in it })
    }

    // --- .apkg ---------------------------------------------------------------------------------

    private fun buildApkg(newFormatOnly: Boolean = false): File {
        val dir = File(context.cacheDir, "apkg-src").apply { deleteRecursively(); mkdirs() }
        val col = File(dir, "collection.anki2")
        SQLiteDatabase.openOrCreateDatabase(col, null).use { s ->
            s.execSQL("CREATE TABLE col (id INTEGER, crt INTEGER, models TEXT, decks TEXT)")
            s.execSQL("CREATE TABLE notes (id INTEGER, guid TEXT, mid INTEGER, tags TEXT, flds TEXT)")
            s.execSQL("CREATE TABLE cards (id INTEGER, nid INTEGER, did INTEGER, odid INTEGER, ord INTEGER, type INTEGER, queue INTEGER, due INTEGER, ivl INTEGER, factor INTEGER, reps INTEGER, lapses INTEGER)")
            val models = """{"111":{"id":111,"name":"Basic (and reversed card)","type":0,
                "flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}],
                "tmpls":[{"name":"Card 1","ord":0,"qfmt":"{{Front}}","afmt":"{{FrontSide}}<hr id=answer>{{Back}}"},
                         {"name":"Card 2","ord":1,"qfmt":"{{Back}}","afmt":"{{FrontSide}}<hr id=answer>{{Front}}"}]}}"""
            val decks = """{"1":{"id":1,"name":"Default"},"222":{"id":222,"name":"Español::Animales"}}"""
            s.execSQL("INSERT INTO col VALUES (1, 1700000000, ?, ?)", arrayOf(models, decks))
            s.execSQL("INSERT INTO notes VALUES (1, 'g-gato', 111, ' animal pet ', ?)", arrayOf("el <b>gato</b>\u001fcat [sound:gato.mp3]"))
            s.execSQL("INSERT INTO notes VALUES (2, 'g-perro', 111, '', ?)", arrayOf("el perro\u001fdog"))
            // gato: card 1 in review (ivl 10, due day 100), card 2 suspended new; perro: both new.
            s.execSQL("INSERT INTO cards VALUES (1, 1, 222, 0, 0, 2, 2, 100, 10, 2300, 7, 1)")
            s.execSQL("INSERT INTO cards VALUES (2, 1, 222, 0, 1, 0, -1, 1, 0, 0, 0, 0)")
            s.execSQL("INSERT INTO cards VALUES (3, 2, 222, 0, 0, 0, 0, 2, 0, 0, 0, 0)")
            s.execSQL("INSERT INTO cards VALUES (4, 2, 222, 0, 1, 0, 0, 3, 0, 0, 0, 0)")
        }
        val apkg = File(context.cacheDir, "test.apkg")
        ZipOutputStream(apkg.outputStream()).use { z ->
            if (newFormatOnly) {
                z.putNextEntry(ZipEntry("collection.anki21b")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            } else {
                z.putNextEntry(ZipEntry("collection.anki2")); col.inputStream().use { it.copyTo(z) }; z.closeEntry()
                z.putNextEntry(ZipEntry("media")); z.write("""{"0":"gato.mp3"}""".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("0")); z.write("fake mp3 data".toByteArray()); z.closeEntry()
            }
        }
        return apkg
    }

    @Test fun apkgImportsNotesDecksMediaAndSchedules() = runTest {
        val apkgImporter = ApkgImporter(context, db, media, importer)
        val apkg = buildApkg()
        val summary = apkgImporter.summarize(apkg)
        assertEquals(2, summary.noteCount)
        assertEquals(listOf("Español::Animales"), summary.deckNames)
        assertEquals(1, summary.mediaCount)

        val report = apkgImporter.import(apkg, keepScheduling = true)
        assertEquals(2, report.base.created)
        val gato = db.noteDao().findByGuid("g-gato")!!
        assertEquals("el gato", gato.fields[0])
        val sound = Sounds.extract(gato.fields[1]).single()
        assertTrue(media.file(sound).exists())
        assertEquals("Animales", db.deckDao().get(gato.deckId)!!.name)
        assertEquals("Basic (and reversed card)", notes.noteType(gato.noteTypeId)!!.name)

        val cards = db.cardDao().forNote(gato.id)
        assertEquals(CardState.REVIEW, cards[0].state)
        assertEquals(10, cards[0].intervalDays)
        assertEquals(2.3, cards[0].ease, 1e-9)
        assertEquals(1_700_000_000_000L + 100 * 86_400_000L, cards[0].due)
        assertTrue(cards[1].suspended)

        // Re-importing the same package updates in place.
        val again = apkgImporter.import(apkg, keepScheduling = true)
        assertEquals(0, again.base.created)
        assertEquals(2, again.base.updated)
        assertEquals(1, notes.noteTypes().count { it.name.startsWith("Basic (and reversed card)") })
    }

    @Test fun newerApkgFormatGivesAClearMessage() = runTest {
        try {
            ApkgImporter(context, db, media, importer).summarize(buildApkg(newFormatOnly = true))
            fail()
        } catch (e: ApkgException) {
            assertTrue(e.message!!.contains("Support older Anki versions"))
        }
    }

    // --- Backup --------------------------------------------------------------------------------

    @Test fun backupRoundTripRestoresDataAndMedia() = runTest {
        val deck = DeckRepository(db).all().first { it.name == Defaults.DEFAULT_DECK }
        val tmp = File.createTempFile("tmp", ".m4a", context.cacheDir).apply { writeText("audio!") }
        val sound = media.addFile(tmp, "m4a", "audio/mp4")
        notes.addNote(notes.noteTypeByName(Defaults.NOTE_TYPE_BASIC)!!, deck.id, listOf("hola ${Sounds.tag(sound)}", "hello"))
        val backups = BackupManager(context, db, media)

        val full = File(context.cacheDir, "full.scbackup")
        val manifest = backups.snapshot(full, includeMedia = true, appVersion = "test")
        assertEquals(1, manifest.noteCount)
        assertEquals(1, manifest.media.size)

        // Change things after the backup.
        notes.addNote(notes.noteTypeByName(Defaults.NOTE_TYPE_BASIC)!!, deck.id, listOf("adiós", "bye"))
        media.file(sound).delete()

        val staged = backups.stageRestore(full) { _, _ -> false }
        assertTrue(staged.missingMedia.isEmpty())
        assertTrue(BackupManager.hasPendingRestore(context))
        db.close()
        assertTrue(BackupManager.applyPendingRestore(context))
        db = openDb()
        assertEquals(1, db.noteDao().searchOnce("", 10).size)
        assertEquals("audio!", MediaRepository(context, db).file(sound).readText())
        assertFalse(BackupManager.hasPendingRestore(context))
    }

    @Test fun mediaFreeBackupFetchesMissingMedia() = runTest {
        val tmp = File.createTempFile("tmp", ".m4a", context.cacheDir).apply { writeText("from nas") }
        val sound = media.addFile(tmp, "m4a", "audio/mp4")
        val backups = BackupManager(context, db, media)
        val small = File(context.cacheDir, "small.scbackup")
        assertFalse(backups.snapshot(small, includeMedia = false, appVersion = "test").includesMedia)
        val nasCopy = File(context.cacheDir, "nas-copy").apply { writeText("from nas") }
        media.file(sound).delete()
        val staged = backups.stageRestore(small) { entry, target -> nasCopy.copyTo(target, true); entry.name == sound }
        assertTrue(staged.missingMedia.isEmpty())
        backups.cancelStagedRestore()
        assertFalse(BackupManager.hasPendingRestore(context))
    }

    @Test fun backupFileNamesRoundTrip() {
        val name = BackupManager.fileName(1_800_000_000_000L)
        assertTrue(name.matches(Regex("""speedcards-\d{8}-\d{4}\.scbackup""")))
        assertEquals(1_800_000_000_000L / 60_000, BackupManager.timestampOf(name)!! / 60_000)
    }

    @Test fun retentionKeepsDailyWeeklyMonthly() {
        val zone = ZoneId.of("UTC")
        val start = LocalDateTime.of(2026, 1, 1, 20, 0)
        // One backup a day for 200 days, plus a second one on the last day.
        val backups = (0 until 200).associate { d ->
            val t = start.plusDays(d.toLong()).atZone(zone).toInstant().toEpochMilli()
            "b$d" to t
        } + ("b199-early" to start.plusDays(199).minusHours(5).atZone(zone).toInstant().toEpochMilli())
        val keep = Retention.keep(backups, zone = zone)
        assertTrue("b199" in keep)
        assertFalse("b199-early" in keep)
        assertTrue((193..199).all { "b$it" in keep })
        assertTrue(keep.size in 12..17)
        assertFalse("b0" in keep)
    }
}
