package com.speedcards.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.speedcards.data.db.LinkType
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.repo.CollectionInitializer
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.Defaults
import com.speedcards.data.repo.ForceLayout
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.LinkTypeInUseException
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.repo.QueueResult
import com.speedcards.data.repo.StudyRepository
import com.speedcards.data.util.DayClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkTest {
    private lateinit var db: SpeedCardsDatabase
    private lateinit var links: LinkRepository
    private lateinit var notes: NoteRepository
    private lateinit var study: StudyRepository
    private var deckId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SpeedCardsDatabase::class.java)
            .allowMainThreadQueries().build()
        CollectionInitializer(db).ensureDefaults()
        links = LinkRepository(db)
        notes = NoteRepository(db)
        study = StudyRepository(db, DayClock(clock = { 1_800_000_000_000L }))
        deckId = DeckRepository(db).all().first { it.name == Defaults.DEFAULT_DECK }.id
    }

    @After fun tearDown() = db.close()

    private suspend fun note(front: String, back: String = "") =
        notes.addNote(notes.noteTypeByName(Defaults.NOTE_TYPE_BASIC)!!, deckId, listOf(front, back))

    private suspend fun type(name: String) = links.types().first { it.name == name }

    @Test fun symmetricLinksAreStoredOnceAndLabelledFromBothSides() = runTest {
        val hablar = note("hablar", "to speak")
        val decir = note("decir", "to say")
        links.addLink(decir, hablar, type("synonym"))
        links.addLink(hablar, decir, type("synonym"))
        assertEquals(1, links.links(hablar).size)
        assertEquals("synonym", links.links(hablar).single().label)
        assertEquals("hablar", links.links(decir).single().front)
        assertEquals("to speak", links.links(decir).single().back)
    }

    @Test fun directedLinksUseInverseLabelFromTheOtherEnd() = runTest {
        val hablar = note("hablar")
        val hablo = note("hablo")
        links.addLink(hablo, hablar, type("conjugation of"))
        assertEquals("conjugation of", links.links(hablo).single().label)
        assertEquals("has conjugation", links.links(hablar).single().label)
    }

    @Test fun oneToManyAndManyToMany() = runTest {
        val root = note("hablar")
        val forms = listOf("hablo", "hablas", "habla").map { note(it) }
        forms.forEach { links.addLink(it, root, type("conjugation of")) }
        val other = note("conversar")
        links.addLink(root, other, type("synonym"))
        links.addLink(forms[0], other, type("same root"))
        assertEquals(4, links.links(root).size)
        assertEquals(2, links.links(forms[0]).size)
        assertEquals(2, links.links(other).size)
    }

    @Test fun clusterRespectsDepth() = runTest {
        val a = note("a"); val b = note("b"); val c = note("c"); val d = note("d")
        val syn = type("synonym")
        links.addLink(a, b, syn); links.addLink(b, c, syn); links.addLink(c, d, syn)
        assertEquals(setOf(a), links.cluster(a, 0))
        assertEquals(setOf(a, b), links.cluster(a, 1))
        assertEquals(setOf(a, b, c), links.cluster(a, 2))
        assertEquals(setOf(a, c), links.neighbours(b))
    }

    @Test fun deletingANoteRemovesItsLinks() = runTest {
        val a = note("a"); val b = note("b")
        links.addLink(a, b, type("antonym"))
        notes.deleteNote(b)
        assertTrue(links.links(a).isEmpty())
    }

    @Test fun builtInOrUsedTypesCannotBeDeleted() = runTest {
        try { links.deleteType(type("synonym")); fail() } catch (_: LinkTypeInUseException) {}
        val custom = links.saveType(LinkType(name = "diminutive of", inverseName = "has diminutive"))
        val a = note("gato"); val b = note("gatito")
        links.addLink(b, a, links.types().first { it.id == custom })
        try { links.deleteType(links.types().first { it.id == custom }); fail() } catch (_: LinkTypeInUseException) {}
        links.removeLink(links.links(a).single().link)
        links.deleteType(links.types().first { it.id == custom })
        assertTrue(links.types().none { it.id == custom })
    }

    @Test fun queueSpacesLinkedNotesApart() = runTest {
        // Note order would normally be hablar, hablo, gato.
        val hablar = note("hablar", "to speak")
        val hablo = note("hablo", "I speak")
        note("gato", "cat")
        links.addLink(hablo, hablar, type("conjugation of"))
        val first = (study.next(deckId) as QueueResult.Next).studyCard
        assertEquals("hablar", first.question)
        val avoid = links.neighbours(hablar) + hablar
        val second = (study.next(deckId, avoid) as QueueResult.Next).studyCard
        assertEquals("gato", second.question)
        // With nothing else left the linked card is still shown.
        val onlyLinked = (study.next(deckId, avoid + notes.findDuplicates("gato").single().id) as QueueResult.Next).studyCard
        assertEquals("hablar", onlyLinked.question)
    }

    @Test fun graphAndLayout() = runTest {
        val center = note("hablar")
        val forms = listOf("hablo", "hablas", "habla").map { note(it) }
        forms.forEach { links.addLink(it, center, type("conjugation of")) }
        val far = note("hablador")
        links.addLink(forms[0], far, type("same root"))
        val graph = links.graph(center, depth = 1)
        assertEquals(4, graph.nodes.size)
        assertEquals(3, graph.edges.size)
        assertEquals(5, links.graph(center, depth = 2).nodes.size)
        assertTrue(links.graph(center, depth = 2, maxNodes = 3).truncated)

        val pos = ForceLayout.layout(graph)
        assertEquals(0f, pos.getValue(center).x, 0.001f)
        assertEquals(0f, pos.getValue(center).y, 0.001f)
        val points = forms.map { pos.getValue(it) }
        // Leaves are spread out, not stacked.
        for (i in points.indices) for (j in i + 1 until points.size) {
            assertTrue(abs(points[i].x - points[j].x) + abs(points[i].y - points[j].y) > 20f)
        }
        assertEquals(pos, ForceLayout.layout(graph)) // deterministic
        assertNotEquals(0, pos.size)
    }
}
