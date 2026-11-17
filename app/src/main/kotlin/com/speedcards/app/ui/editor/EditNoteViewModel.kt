package com.speedcards.app.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedcards.app.AppStartup
import com.speedcards.app.audio.AudioPlayer
import com.speedcards.app.prefs.UserPrefs
import com.speedcards.app.ui.links.LinkRowData
import com.speedcards.data.db.Deck
import com.speedcards.data.db.LinkType
import com.speedcards.data.db.NoteRow
import com.speedcards.data.db.DeckKind
import com.speedcards.data.db.Note
import com.speedcards.data.db.NoteType
import com.speedcards.data.repo.CardGenerator
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.LinkRepository
import com.speedcards.data.repo.LinkedNote
import com.speedcards.data.repo.MediaRepository
import com.speedcards.data.repo.NoteRepository
import com.speedcards.data.template.Sounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class DuplicateInfo(val note: Note, val deckName: String)

/** A link chosen while adding a note; created once the note is saved. */
data class PendingLink(val target: NoteRow, val type: LinkType) {
    val key get() = "pending-${target.id}-${type.id}"
}

data class EditorState(
    val loading: Boolean = true,
    /** Null when adding a new note. */
    val noteId: Long? = null,
    val decks: List<Deck> = emptyList(),
    val noteTypes: List<NoteType> = emptyList(),
    val type: NoteType? = null,
    val deckId: Long? = null,
    val fields: List<String> = emptyList(),
    val sticky: Set<Int> = emptySet(),
    val tags: String = "",
    /** Read the question side aloud; off keeps it silent until the card is flipped. */
    val readFront: Boolean = true,
    val duplicates: List<DuplicateInfo> = emptyList(),
    val addedCount: Int = 0,
    val savedLinks: List<LinkedNote> = emptyList(),
    /** This note's cards (edit mode), for suspend/reset/leech controls. */
    val cards: List<com.speedcards.data.db.Card> = emptyList(),
    val pendingLinks: List<PendingLink> = emptyList(),
    /** Non-null while the link picker is open; holds its starting search text. */
    val linkPickerQuery: String? = null,
    val message: String? = null,
    val done: Boolean = false,
) {
    val isEdit get() = noteId != null
    val canSave get() = type != null && deckId != null && fields.firstOrNull().orEmpty().isNotBlank()

    /** Preview of the first card this note would create. */
    val preview: CardGenerator.RenderedCard?
        get() {
            val t = type ?: return null
            val ord = CardGenerator.ordsFor(t, fields).minOrNull() ?: return null
            return CardGenerator.render(t, fields, ord)
        }
    val cardCount get() = type?.let { CardGenerator.ordsFor(it, fields).size } ?: 0

    val linkRows: List<LinkRowData>
        get() = savedLinks.map { LinkRowData(linkKey(it), it.label, it.front, it.back, it.type.color) } +
            pendingLinks.map { p ->
                LinkRowData(
                    p.key, p.type.name, LinkRepository.plain(p.target.fields.firstOrNull().orEmpty()),
                    LinkRepository.plain(p.target.fields.getOrNull(1).orEmpty()), p.type.color,
                )
            }
}

fun linkKey(l: LinkedNote) = "link-${l.link.fromNoteId}-${l.link.toNoteId}-${l.link.linkTypeId}"

private val linkTrigger = Regex("""\[\[([^\]\[]*)(\]\])?$""")

@OptIn(FlowPreview::class)
@HiltViewModel
class EditNoteViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val startup: AppStartup,
    private val notes: NoteRepository,
    private val deckRepo: DeckRepository,
    private val prefs: UserPrefs,
    private val media: MediaRepository,
    private val links: LinkRepository,
    private val study: com.speedcards.data.repo.StudyRepository,
    val player: AudioPlayer,
) : ViewModel() {
    private val noteIdArg: Long? = savedState.get<Long>("noteId")
    private val deckIdArg: Long? = savedState.get<Long>("deckId")?.takeIf { it > 0 }

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            startup.ready()
            val decks = deckRepo.all()
            val types = notes.noteTypes()
            if (noteIdArg != null) {
                val existing = notes.get(noteIdArg) ?: return@launch _state.update { it.copy(done = true) }
                _state.update {
                    it.copy(
                        loading = false, noteId = noteIdArg, decks = decks, noteTypes = types,
                        type = existing.type, deckId = existing.note.deckId,
                        fields = existing.note.fields, tags = existing.tags.joinToString(" "),
                        readFront = existing.note.readFront, cards = existing.cards,
                    )
                }
                launch { links.observeLinks(noteIdArg).collect { l -> _state.update { it.copy(savedLinks = l) } } }
            } else {
                val type = prefs.lastNoteTypeId()?.let { id -> types.firstOrNull { it.id == id } } ?: types.first()
                val deckId = deckIdArg ?: prefs.lastDeckId()?.takeIf { id -> decks.any { it.id == id } }
                    ?: decks.first { it.kind == DeckKind.NORMAL }.id
                _state.update {
                    it.copy(
                        loading = false, decks = decks, noteTypes = types, type = type, deckId = deckId,
                        fields = List(type.fields.size) { "" }, sticky = prefs.stickyFields(type.id),
                    )
                }
            }
        }
        // Duplicate warning follows the first field as you type.
        viewModelScope.launch {
            _state.map { it.fields.firstOrNull().orEmpty() }
                .distinctUntilChanged()
                .debounce(250)
                .collect { first ->
                    val decks = _state.value.decks
                    val dups = notes.findDuplicates(first, excludeNoteId = _state.value.noteId).map { n ->
                        DuplicateInfo(n, decks.firstOrNull { it.id == n.deckId }?.let { DeckRepository.fullName(decks, it) }.orEmpty())
                    }
                    _state.update { it.copy(duplicates = dups) }
                }
        }
    }

    fun selectType(type: NoteType) = viewModelScope.launch {
        val old = _state.value
        // Carry values over by field name, then by position.
        val carried = type.fields.mapIndexed { i, f ->
            val byName = old.type?.fields?.indexOfFirst { it.name == f.name }?.takeIf { it >= 0 }
            old.fields.getOrNull(byName ?: i).orEmpty()
        }
        _state.update { it.copy(type = type, fields = carried, sticky = prefs.stickyFields(type.id)) }
    }

    fun selectDeck(deck: Deck) = _state.update { it.copy(deckId = deck.id) }

    fun setField(index: Int, value: String) = _state.update {
        it.copy(fields = it.fields.toMutableList().also { f -> f[index] = value })
    }

    /** Edits the text of a field while keeping its attached audio. */
    fun setFieldText(index: Int, text: String) {
        // Typing "[[" (optionally followed by a word and "]]") opens the link picker instead of adding text.
        val trigger = linkTrigger.find(text)
        val clean = if (trigger != null) text.removeRange(trigger.range) else text
        _state.update {
            it.copy(
                fields = it.fields.toMutableList().also { f -> f[index] = Sounds.withText(f[index], clean) },
                linkPickerQuery = trigger?.groupValues?.get(1)?.trim() ?: it.linkPickerQuery,
            )
        }
    }

    fun openLinkPicker() = _state.update { it.copy(linkPickerQuery = "") }
    fun closeLinkPicker() = _state.update { it.copy(linkPickerQuery = null) }

    fun addLink(target: NoteRow, type: LinkType) = viewModelScope.launch {
        val noteId = _state.value.noteId
        if (noteId != null) links.addLink(noteId, target.id, type)
        else _state.update { s ->
            val pending = PendingLink(target, type)
            if (s.pendingLinks.any { it.key == pending.key }) s else s.copy(pendingLinks = s.pendingLinks + pending)
        }
        closeLinkPicker()
    }

    fun removeLink(key: String) = viewModelScope.launch {
        _state.value.savedLinks.firstOrNull { linkKey(it) == key }?.let { links.removeLink(it.link) }
        _state.update { s -> s.copy(pendingLinks = s.pendingLinks.filterNot { it.key == key }) }
    }

    /** Note id behind a link row, for opening it. */
    fun linkedNoteId(key: String): Long? =
        _state.value.savedLinks.firstOrNull { linkKey(it) == key }?.noteId
            ?: _state.value.pendingLinks.firstOrNull { it.key == key }?.target?.id

    fun attachRecording(index: Int, file: File) = viewModelScope.launch {
        val name = media.addFile(file, "m4a", "audio/mp4")
        _state.update {
            it.copy(fields = it.fields.toMutableList().also { f ->
                f[index] = Sounds.join(Sounds.strip(f[index]), Sounds.extract(f[index]) + name)
            })
        }
    }

    fun removeSound(index: Int, name: String) = _state.update {
        it.copy(fields = it.fields.toMutableList().also { f ->
            f[index] = Sounds.join(Sounds.strip(f[index]), Sounds.extract(f[index]) - name)
        })
    }

    fun playSound(name: String) = player.playFile(media.file(name))

    override fun onCleared() = player.stop()

    fun setTags(value: String) = _state.update { it.copy(tags = value) }

    fun setReadFront(value: Boolean) = _state.update { it.copy(readFront = value) }

    fun toggleSticky(index: Int) = viewModelScope.launch {
        val s = _state.value
        val type = s.type ?: return@launch
        val sticky = if (index in s.sticky) s.sticky - index else s.sticky + index
        prefs.setStickyFields(type.id, sticky)
        _state.update { it.copy(sticky = sticky) }
    }

    /** Saves; when [addAnother] is true the form is cleared except sticky fields. */
    fun save(addAnother: Boolean) = viewModelScope.launch {
        val s = _state.value
        val type = s.type ?: return@launch
        val deckId = s.deckId ?: return@launch
        if (!s.canSave) return@launch
        val tags = s.tags.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (s.noteId != null) {
            notes.updateNote(s.noteId, deckId, s.fields, tags, readFront = s.readFront)
            _state.update { it.copy(done = true) }
            return@launch
        }
        val newId = notes.addNote(type, deckId, s.fields, tags, readFront = s.readFront)
        s.pendingLinks.forEach { links.addLink(newId, it.target.id, it.type) }
        prefs.rememberAdd(deckId, type.id)
        if (addAnother) {
            _state.update {
                it.copy(
                    fields = it.fields.mapIndexed { i, v -> if (i in it.sticky) v else "" },
                    pendingLinks = emptyList(),
                    addedCount = it.addedCount + 1,
                    message = "Added",
                )
            }
        } else _state.update { it.copy(done = true) }
    }

    fun delete() = viewModelScope.launch {
        val id = _state.value.noteId ?: return@launch
        notes.deleteNote(id)
        _state.update { it.copy(done = true) }
    }

    private fun reloadCards() = viewModelScope.launch {
        val id = _state.value.noteId ?: return@launch
        notes.get(id)?.let { n -> _state.update { it.copy(cards = n.cards) } }
    }

    fun setSuspended(card: com.speedcards.data.db.Card, suspended: Boolean) = viewModelScope.launch {
        study.setSuspended(card, suspended); reloadCards()
    }

    fun resetCard(card: com.speedcards.data.db.Card) = viewModelScope.launch {
        study.resetCard(card); reloadCards()
        _state.update { it.copy(message = "Card reset to new") }
    }

    fun clearLeech(card: com.speedcards.data.db.Card) = viewModelScope.launch { study.clearLeech(card); reloadCards() }

    fun messageShown() = _state.update { it.copy(message = null) }
}
