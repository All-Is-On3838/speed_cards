package com.speedcards.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.speedcards.scheduler.CardState
import com.speedcards.scheduler.Rating
import com.speedcards.scheduler.SchedulerConfig
import kotlinx.serialization.Serializable

enum class DeckKind { NORMAL, INBOX }

@Entity(
    tableName = "deck",
    indices = [Index("parentId"), Index("presetId"), Index("folderId")],
)
data class Deck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val presetId: Long,
    /** BCP-47 tag for the language being learned, e.g. "es-MX". */
    val languageFront: String? = null,
    /** BCP-47 tag for the language you already know, e.g. "en-US". */
    val languageBack: String? = null,
    /** Built-in decks (starter decks, Inbox) cannot be deleted. */
    val builtIn: Boolean = false,
    val kind: DeckKind = DeckKind.NORMAL,
    val created: Long = System.currentTimeMillis(),
    /** The folder a top-level deck is filed in; null means unfiled. Sub-decks follow their top-level deck. */
    val folderId: Long? = null,
)

/** A named group of top-level decks, e.g. "Spanish". The home screen opens on the list of folders. */
@Entity(tableName = "folder")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val created: Long = System.currentTimeMillis(),
)

@Entity(tableName = "scheduling_preset")
data class SchedulingPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Minutes, space separated, e.g. "10 60 1440". */
    val learningSteps: String = "10 60 1440",
    val relearningSteps: String = "10",
    val graduatingIntervalDays: Int = 1,
    val easyIntervalDays: Int = 4,
    val startingEase: Double = 2.5,
    val hardMultiplier: Double = 1.2,
    val easyBonus: Double = 1.3,
    val intervalModifier: Double = 1.0,
    val againEaseDelta: Double = -0.20,
    val hardEaseDelta: Double = -0.15,
    val easyEaseDelta: Double = 0.15,
    val minimumEase: Double = 1.3,
    val maximumIntervalDays: Int = 36_500,
    val lapseIntervalMultiplier: Double = 0.0,
    val newPerDay: Int = 20,
    val reviewsPerDay: Int = 200,
    val fuzzEnabled: Boolean = true,
    val showAgain: Boolean = true,
    val leechThreshold: Int = 8,
) {
    fun toConfig() = SchedulerConfig(
        learningStepsMinutes = parseSteps(learningSteps),
        relearningStepsMinutes = parseSteps(relearningSteps),
        graduatingIntervalDays = graduatingIntervalDays,
        easyIntervalDays = easyIntervalDays,
        startingEase = startingEase,
        hardMultiplier = hardMultiplier,
        easyBonus = easyBonus,
        intervalModifier = intervalModifier,
        againEaseDelta = againEaseDelta,
        hardEaseDelta = hardEaseDelta,
        easyEaseDelta = easyEaseDelta,
        minimumEase = minimumEase,
        maximumIntervalDays = maximumIntervalDays,
        lapseIntervalMultiplier = lapseIntervalMultiplier,
        fuzzEnabled = fuzzEnabled,
        leechThreshold = leechThreshold,
        showAgain = showAgain,
    )

    companion object {
        /** Parses "10 1h 1d" style steps into minutes. Plain numbers are minutes. */
        fun parseSteps(text: String): List<Double> =
            text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.mapNotNull { token ->
                val t = token.lowercase()
                val (num, mult) = when {
                    t.endsWith("d") -> t.dropLast(1) to 1440.0
                    t.endsWith("h") -> t.dropLast(1) to 60.0
                    t.endsWith("m") -> t.dropLast(1) to 1.0
                    t.endsWith("s") -> t.dropLast(1) to 1.0 / 60
                    else -> t to 1.0
                }
                num.toDoubleOrNull()?.takeIf { it > 0 }?.times(mult)
            }

        fun formatSteps(minutes: List<Double>): String = minutes.joinToString(" ") { m ->
            when {
                m >= 1440 && m % 1440 == 0.0 -> "${(m / 1440).toLong()}d"
                m >= 60 && m % 60 == 0.0 -> "${(m / 60).toLong()}h"
                m == m.toLong().toDouble() -> "${m.toLong()}m"
                else -> "${m}m"
            }
        }
    }
}

enum class NoteKind { STANDARD, CLOZE }

/** Which deck language a field is written in; drives TTS and keyboard language. */
enum class FieldLanguage { FRONT, BACK, NONE }

@Serializable
data class FieldDef(
    val name: String,
    val language: FieldLanguage = FieldLanguage.NONE,
)

@Serializable
data class CardTemplateDef(
    val name: String,
    val front: String,
    val back: String,
)

@Entity(tableName = "note_type", indices = [Index("name", unique = true)])
data class NoteType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: NoteKind = NoteKind.STANDARD,
    val fields: List<FieldDef>,
    val templates: List<CardTemplateDef>,
    val builtIn: Boolean = false,
)

@Entity(
    tableName = "note",
    foreignKeys = [
        ForeignKey(Deck::class, ["id"], ["deckId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(NoteType::class, ["id"], ["noteTypeId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("deckId"), Index("noteTypeId"), Index("guid", unique = true), Index("sortField"), Index("importBatchId")],
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id used to match notes on re-import (CSV id column or Anki GUID). */
    val guid: String,
    val noteTypeId: Long,
    val deckId: Long,
    val fields: List<String>,
    /** Normalized first field, used for duplicate checks and sorting. */
    val sortField: String,
    val importBatchId: Long? = null,
    val created: Long = System.currentTimeMillis(),
    val modified: Long = System.currentTimeMillis(),
    /** Off: the question side stays silent (listening cards excepted) and is heard after the card is flipped. */
    @ColumnInfo(defaultValue = "1") val readFront: Boolean = true,
)

@Entity(
    tableName = "card",
    foreignKeys = [
        ForeignKey(Note::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Deck::class, ["id"], ["deckId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["noteId", "ord"], unique = true), Index(value = ["deckId", "state", "due"])],
)
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    /** Kept in sync with the note's deck so queue queries don't need a join. */
    val deckId: Long,
    /** Template index, or cloze number - 1. */
    val ord: Int,
    val state: CardState = CardState.NEW,
    val due: Long = 0,
    val intervalDays: Int = 0,
    val ease: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val stepIndex: Int = 0,
    val flags: Int = 0,
    val suspended: Boolean = false,
    val buriedUntil: Long = 0,
    val leech: Boolean = false,
    val created: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "review_log",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index("timestamp")],
)
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val timestamp: Long,
    val rating: Rating,
    val prevState: CardState,
    val prevIntervalDays: Int,
    val newIntervalDays: Int,
    val prevEase: Double,
    val newEase: Double,
    val durationMs: Long,
)

@Entity(tableName = "link_type", indices = [Index("name", unique = true)])
data class LinkType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Label seen from the other end of a directed link, e.g. "has conjugation". Null when symmetric. */
    val inverseName: String? = null,
    val symmetric: Boolean = false,
    val color: Long = 0xFF607D8B,
    val builtIn: Boolean = false,
)

@Entity(
    tableName = "note_link",
    primaryKeys = ["fromNoteId", "toNoteId", "linkTypeId"],
    foreignKeys = [
        ForeignKey(Note::class, ["id"], ["fromNoteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Note::class, ["id"], ["toNoteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(LinkType::class, ["id"], ["linkTypeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("toNoteId"), Index("linkTypeId")],
)
data class NoteLink(
    val fromNoteId: Long,
    val toNoteId: Long,
    val linkTypeId: Long,
    val created: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tag", indices = [Index("name", unique = true)])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "note_tag",
    primaryKeys = ["noteId", "tagId"],
    foreignKeys = [
        ForeignKey(Note::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Tag::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class NoteTag(val noteId: Long, val tagId: Long)

@Entity(tableName = "media", indices = [Index("hash", unique = true), Index("fileName", unique = true)])
data class Media(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val hash: String,
)

@Entity(
    tableName = "user_recording",
    foreignKeys = [
        ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Media::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId"), Index("mediaId")],
)
data class UserRecording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val mediaId: Long,
    val timestamp: Long,
)

@Entity(tableName = "import_batch")
data class ImportBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val createdCount: Int = 0,
    val updatedCount: Int = 0,
    val undone: Boolean = false,
)
