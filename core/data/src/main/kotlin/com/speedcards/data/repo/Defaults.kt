package com.speedcards.data.repo

import com.speedcards.data.db.CardTemplateDef
import com.speedcards.data.db.FieldDef
import com.speedcards.data.db.FieldLanguage
import com.speedcards.data.db.LinkType
import com.speedcards.data.db.NoteKind
import com.speedcards.data.db.NoteType
import com.speedcards.data.db.SchedulingPreset

/** Built-in presets, note types and link types created on first launch. */
object Defaults {
    const val PRESET_VOCAB = "Vocabulary"
    const val NOTE_TYPE_BASIC = "Basic"
    const val NOTE_TYPE_BASIC_REVERSE = "Basic (and reversed)"
    const val NOTE_TYPE_CLOZE = "Cloze"
    const val NOTE_TYPE_VOCAB = "Vocab"
    const val NOTE_TYPE_SPANISH = "Spanish Vocab"
    const val NOTE_TYPE_CHINESE = "Chinese Vocab"
    const val INBOX_DECK = "Inbox"
    const val DEFAULT_DECK = "My Deck"

    val presets = listOf(
        SchedulingPreset(name = PRESET_VOCAB, learningSteps = "10m 1h 1d", newPerDay = 5),
        SchedulingPreset(
            name = "Sentences/grammar",
            learningSteps = "10m 1h 1d 3d",
            relearningSteps = "10m 1d",
            graduatingIntervalDays = 2,
            hardMultiplier = 1.15,
            easyBonus = 1.2,
            newPerDay = 10,
        ),
        SchedulingPreset(
            name = "Cram before trip/exam",
            learningSteps = "1m 10m 1h",
            relearningSteps = "1m 10m",
            graduatingIntervalDays = 1,
            easyIntervalDays = 2,
            maximumIntervalDays = 14,
            intervalModifier = 0.7,
            newPerDay = 60,
            reviewsPerDay = 500,
        ),
    )

    private const val DIVIDER = "\n---\n"

    val noteTypes = listOf(
        NoteType(
            name = NOTE_TYPE_BASIC,
            fields = listOf(FieldDef("Front", FieldLanguage.FRONT), FieldDef("Back", FieldLanguage.BACK)),
            templates = listOf(CardTemplateDef("Card 1", "{{Front}}", "{{FrontSide}}$DIVIDER{{Back}}")),
            builtIn = true,
        ),
        NoteType(
            name = NOTE_TYPE_BASIC_REVERSE,
            fields = listOf(FieldDef("Front", FieldLanguage.FRONT), FieldDef("Back", FieldLanguage.BACK)),
            templates = listOf(
                CardTemplateDef("Card 1", "{{Front}}", "{{FrontSide}}$DIVIDER{{Back}}"),
                CardTemplateDef("Card 2", "{{Back}}", "{{FrontSide}}$DIVIDER{{Front}}"),
            ),
            builtIn = true,
        ),
        NoteType(
            name = NOTE_TYPE_CLOZE,
            kind = NoteKind.CLOZE,
            fields = listOf(FieldDef("Text", FieldLanguage.FRONT), FieldDef("Extra", FieldLanguage.BACK)),
            templates = listOf(
                CardTemplateDef("Cloze", "{{cloze:Text}}", "{{cloze:Text}}{{#Extra}}$DIVIDER{{Extra}}{{/Extra}}"),
            ),
            builtIn = true,
        ),
        NoteType(
            name = NOTE_TYPE_VOCAB,
            fields = listOf(
                FieldDef("Word", FieldLanguage.FRONT),
                FieldDef("Meaning", FieldLanguage.BACK),
                FieldDef("Example", FieldLanguage.FRONT),
                FieldDef("Example translation", FieldLanguage.BACK),
                FieldDef("Notes", FieldLanguage.NONE),
                FieldDef("Source", FieldLanguage.NONE),
            ),
            templates = listOf(
                CardTemplateDef(
                    "Recognition",
                    "{{Word}}",
                    "{{FrontSide}}$DIVIDER{{Meaning}}" + VOCAB_EXTRAS,
                ),
                CardTemplateDef(
                    "Recall",
                    "{{Meaning}}",
                    "{{FrontSide}}$DIVIDER{{Word}}" + VOCAB_EXTRAS,
                ),
            ),
            builtIn = true,
        ),
    )

    private const val ES_EXTRAS =
        "{{#Gender / POS}}\n({{Gender / POS}}){{/Gender / POS}}" +
            "{{#Example (ES)}}\n\n{{Example (ES)}}\n{{Example (EN)}}{{/Example (ES)}}" +
            "{{#Notes}}\n\n{{Notes}}{{/Notes}}"

    private const val ZH_EXTRAS =
        "{{#Measure word}}\nMeasure word: {{Measure word}}{{/Measure word}}" +
            "{{#Example (Simplified)}}\n\n{{hanzi:Example (Simplified)|Example (Traditional)}}\n{{pinyin:Example pinyin}}\n{{Example (EN)}}{{/Example (Simplified)}}" +
            "{{#Notes}}\n\n{{Notes}}{{/Notes}}"

    /** Note types for the built-in starter decks. */
    val starterNoteTypes = listOf(
        NoteType(
            name = NOTE_TYPE_SPANISH,
            fields = listOf(
                FieldDef("Spanish", FieldLanguage.FRONT),
                FieldDef("English", FieldLanguage.BACK),
                FieldDef("Gender / POS", FieldLanguage.NONE),
                FieldDef("Example (ES)", FieldLanguage.FRONT),
                FieldDef("Example (EN)", FieldLanguage.BACK),
                FieldDef("Notes", FieldLanguage.NONE),
            ),
            templates = listOf(
                CardTemplateDef("Spanish → English", "{{Spanish}}", "{{FrontSide}}$DIVIDER{{English}}$ES_EXTRAS"),
                CardTemplateDef("English → Spanish", "{{English}}", "{{FrontSide}}$DIVIDER{{Spanish}}$ES_EXTRAS"),
                CardTemplateDef(
                    "Fill in the sentence",
                    "{{cloze:Example (ES)}}\n{{Example (EN)}}",
                    "{{cloze:Example (ES)}}\n{{Example (EN)}}$DIVIDER{{Spanish}} — {{English}}",
                ),
            ),
            builtIn = true,
        ),
        NoteType(
            name = NOTE_TYPE_CHINESE,
            fields = listOf(
                FieldDef("Simplified", FieldLanguage.FRONT),
                FieldDef("Traditional", FieldLanguage.NONE),
                FieldDef("Pinyin", FieldLanguage.NONE),
                FieldDef("English", FieldLanguage.BACK),
                FieldDef("Measure word", FieldLanguage.NONE),
                FieldDef("Example (Simplified)", FieldLanguage.FRONT),
                FieldDef("Example (Traditional)", FieldLanguage.NONE),
                FieldDef("Example pinyin", FieldLanguage.NONE),
                FieldDef("Example (EN)", FieldLanguage.BACK),
                FieldDef("Notes", FieldLanguage.NONE),
            ),
            templates = listOf(
                CardTemplateDef(
                    "Reading",
                    "{{hanzi:Simplified|Traditional}}",
                    "{{FrontSide}}\n{{pinyin:Pinyin}}$DIVIDER{{English}}$ZH_EXTRAS",
                ),
                CardTemplateDef(
                    "Recall",
                    "{{English}}",
                    "{{FrontSide}}$DIVIDER{{hanzi:Simplified|Traditional}}\n{{pinyin:Pinyin}}$ZH_EXTRAS",
                ),
                CardTemplateDef(
                    "Listening",
                    "{{audio:Simplified}}",
                    "{{hanzi:Simplified|Traditional}}\n{{pinyin:Pinyin}}$DIVIDER{{English}}$ZH_EXTRAS",
                ),
            ),
            builtIn = true,
        ),
    )

    private const val VOCAB_EXTRAS =
        "{{#Example}}\n\n{{Example}}{{/Example}}" +
            "{{#Example translation}}\n{{Example translation}}{{/Example translation}}" +
            "{{#Notes}}\n\n{{Notes}}{{/Notes}}"

    val linkTypes = listOf(
        LinkType(name = "synonym", symmetric = true, color = 0xFF43A047, builtIn = true),
        LinkType(name = "antonym", symmetric = true, color = 0xFFE53935, builtIn = true),
        LinkType(name = "conjugation of", inverseName = "has conjugation", color = 0xFF1E88E5, builtIn = true),
        LinkType(name = "plural of", inverseName = "has plural", color = 0xFF5E35B1, builtIn = true),
        LinkType(name = "gender pair", symmetric = true, color = 0xFFD81B60, builtIn = true),
        LinkType(name = "example sentence for", inverseName = "has example", color = 0xFFFB8C00, builtIn = true),
        LinkType(name = "false friend", symmetric = true, color = 0xFF6D4C41, builtIn = true),
        LinkType(name = "same root", symmetric = true, color = 0xFF00897B, builtIn = true),
        LinkType(name = "collocation", symmetric = true, color = 0xFF546E7A, builtIn = true),
        LinkType(name = "same measure word", symmetric = true, color = 0xFFFDD835, builtIn = true),
    )
}
