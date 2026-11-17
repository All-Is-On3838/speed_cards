package com.speedcards.data.repo

import com.speedcards.data.db.LinkType
import com.speedcards.data.db.Note
import com.speedcards.data.db.NoteLink
import com.speedcards.data.db.NoteRow
import com.speedcards.data.db.SpeedCardsDatabase
import com.speedcards.data.template.Cloze
import com.speedcards.data.template.Sounds
import com.speedcards.data.template.TextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A link as seen from one note: who's on the other end and what to call the relationship. */
data class LinkedNote(
    val noteId: Long,
    val front: String,
    val back: String,
    /** e.g. "synonym", or "has conjugation" when seen from the other end of a directed link. */
    val label: String,
    val type: LinkType,
    val link: NoteLink,
)

data class GraphNode(val noteId: Long, val label: String, val depth: Int)
data class GraphEdge(val from: Long, val to: Long, val type: LinkType)
data class NoteGraph(val centerId: Long, val nodes: List<GraphNode>, val edges: List<GraphEdge>, val truncated: Boolean)

class LinkTypeInUseException(message: String) : Exception(message)

@Singleton
class LinkRepository @Inject constructor(private val db: SpeedCardsDatabase) {
    private val dao get() = db.linkDao()

    fun observeTypes(): Flow<List<LinkType>> = dao.observeTypes()
    suspend fun types(): List<LinkType> = dao.allTypes()

    suspend fun saveType(type: LinkType): Long = if (type.id == 0L) dao.insertType(type) else { dao.updateType(type); type.id }

    /** Built-in types and types still used by links are kept. */
    suspend fun deleteType(type: LinkType) {
        if (type.builtIn) throw LinkTypeInUseException("\"${type.name}\" is built in.")
        val uses = dao.usageCount(type.id)
        if (uses > 0) throw LinkTypeInUseException("\"${type.name}\" is used by $uses link${if (uses == 1) "" else "s"}.")
        dao.deleteType(type)
    }

    /**
     * Links [from] → [to]. Symmetric types are stored once with the lower id first,
     * so "A synonym B" and "B synonym A" are the same link.
     */
    suspend fun addLink(from: Long, to: Long, type: LinkType) {
        if (from == to) return
        val (a, b) = if (type.symmetric && to < from) to to from else from to to
        dao.insert(NoteLink(a, b, type.id))
    }

    suspend fun removeLink(link: NoteLink) = dao.delete(link.fromNoteId, link.toNoteId, link.linkTypeId)

    fun observeLinks(noteId: Long): Flow<List<LinkedNote>> =
        combine(dao.observeTouching(noteId), dao.observeTypes()) { links, types -> links to types }
            .map { (links, types) -> describe(noteId, links, types) }

    suspend fun links(noteId: Long): List<LinkedNote> = describe(noteId, dao.touching(listOf(noteId)), dao.allTypes())

    /** The note and every note within [depth] links of it. */
    suspend fun cluster(noteId: Long, depth: Int): Set<Long> {
        val seen = mutableSetOf(noteId)
        var frontier = setOf(noteId)
        repeat(depth) {
            if (frontier.isEmpty()) return seen
            val next = dao.touching(frontier.toList()).flatMap { listOf(it.fromNoteId, it.toNoteId) }.toSet() - seen
            seen += next
            frontier = next
        }
        return seen
    }

    /** Notes directly linked to [noteId] (either direction). */
    suspend fun neighbours(noteId: Long): Set<Long> = cluster(noteId, 1) - noteId

    /** Neighbourhood of [noteId] for the graph view, capped at [maxNodes] (closest first). */
    suspend fun graph(noteId: Long, depth: Int = 2, maxNodes: Int = 60): NoteGraph {
        val depthOf = linkedMapOf(noteId to 0)
        var frontier = setOf(noteId)
        var truncated = false
        for (d in 1..depth) {
            if (frontier.isEmpty()) break
            val next = dao.touching(frontier.toList()).flatMap { listOf(it.fromNoteId, it.toNoteId) }.filter { it !in depthOf }.distinct()
            for (id in next) {
                if (depthOf.size >= maxNodes) { truncated = true; break }
                depthOf[id] = d
            }
            frontier = next.filter { it in depthOf }.toSet()
        }
        val notes = db.noteDao().getMany(depthOf.keys.toList()).associateBy { it.id }
        val types = dao.allTypes().associateBy { it.id }
        val edges = dao.touching(depthOf.keys.toList())
            .filter { it.fromNoteId in depthOf && it.toNoteId in depthOf }
            .mapNotNull { l -> types[l.linkTypeId]?.let { GraphEdge(l.fromNoteId, l.toNoteId, it) } }
        val nodes = depthOf.map { (id, d) -> GraphNode(id, notes[id]?.let(::frontText).orEmpty(), d) }
        return NoteGraph(noteId, nodes, edges, truncated)
    }

    /** Notes matching [query], for the link picker. */
    suspend fun search(query: String, excludeNoteId: Long?): List<NoteRow> =
        db.noteDao().searchOnce(query.trim(), 40).filter { it.id != excludeNoteId }

    private suspend fun describe(noteId: Long, links: List<NoteLink>, types: List<LinkType>): List<LinkedNote> {
        val typeById = types.associateBy { it.id }
        val others = db.noteDao().getMany(links.map { if (it.fromNoteId == noteId) it.toNoteId else it.fromNoteId }).associateBy { it.id }
        return links.mapNotNull { link ->
            val type = typeById[link.linkTypeId] ?: return@mapNotNull null
            val outgoing = link.fromNoteId == noteId
            val other = others[if (outgoing) link.toNoteId else link.fromNoteId] ?: return@mapNotNull null
            val label = if (outgoing || type.symmetric) type.name else type.inverseName ?: type.name
            LinkedNote(other.id, frontText(other), plain(other.fields.getOrNull(1).orEmpty()), label, type, link)
        }
    }

    companion object {
        fun plain(field: String) = TextNormalizer.plain(Sounds.strip(Cloze.strip(field)))
        fun frontText(note: Note) = plain(note.fields.firstOrNull().orEmpty())
    }
}
