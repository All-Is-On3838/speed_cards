package com.speedcards.data

import com.speedcards.data.db.Deck
import com.speedcards.data.db.FieldLanguage
import com.speedcards.data.repo.CardGenerator
import com.speedcards.data.repo.Defaults
import com.speedcards.data.repo.SpokenLine
import com.speedcards.data.repo.resolve
import com.speedcards.data.template.Sounds
import com.speedcards.data.template.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlanTest {
    private val vocab = Defaults.noteTypes.first { it.name == Defaults.NOTE_TYPE_VOCAB }
    private val cloze = Defaults.noteTypes.first { it.name == Defaults.NOTE_TYPE_CLOZE }
    private val deck = Deck(name = "Spanish", presetId = 1, languageFront = "es-MX", languageBack = "en-US")

    @Test fun soundTagsExtractStripAndJoin() {
        val field = "gato [sound:abc.m4a] [sound:def.m4a]"
        assertEquals(listOf("abc.m4a", "def.m4a"), Sounds.extract(field))
        assertEquals("gato", Sounds.strip(field))
        assertEquals("perro [sound:abc.m4a] [sound:def.m4a]", Sounds.withText(field, "perro"))
        assertEquals("[sound:x.m4a]", Sounds.join("", listOf("x.m4a")))
        assertEquals("gato", TextNormalizer.sortKey("Gato [sound:abc.m4a]"))
    }

    @Test fun recordedAudioSplitsBetweenSides() {
        val fields = listOf("gato [sound:word.m4a]", "cat", "El gato duerme. [sound:ex.m4a]", "", "", "")
        val audio = CardGenerator.audio(vocab, fields, 0)
        assertEquals(listOf("word.m4a"), audio.question.sounds)
        assertEquals(listOf("ex.m4a"), audio.answer.sounds)
    }

    @Test fun speechUsesFieldLanguagesAndSkipsNoneFields() {
        val fields = listOf("gato", "cat", "El gato duerme.", "The cat sleeps.", "my note", "a book")
        val audio = CardGenerator.audio(vocab, fields, 0)
        assertEquals(listOf(SpokenLine("gato", "es-MX")), audio.question.resolve(deck).speech)
        assertEquals(
            listOf(SpokenLine("cat", "en-US"), SpokenLine("El gato duerme.", "es-MX"), SpokenLine("The cat sleeps.", "en-US")),
            audio.answer.resolve(deck).speech,
        )
        // Recall card: question is the meaning in English.
        assertEquals(listOf(SpokenLine("cat", "en-US")), CardGenerator.audio(vocab, fields, 1).question.resolve(deck).speech)
    }

    @Test fun readFrontOffMovesQuestionAudioToAnswer() {
        val fields = listOf("gato [sound:word.m4a]", "cat", "", "", "", "")
        val audio = CardGenerator.audio(vocab, fields, 0, readFront = false)
        assertTrue(audio.question.sounds.isEmpty() && audio.question.speech.isEmpty())
        assertEquals(listOf("word.m4a"), audio.answer.sounds)
        assertEquals(listOf(SpokenLine("gato", "es-MX"), SpokenLine("cat", "en-US")), audio.answer.resolve(deck).speech)
    }

    @Test fun readFrontOffOnClozeDoesNotRepeatTheSentence() {
        val audio = CardGenerator.audio(cloze, listOf("Yo {{c1::hablo}} español", ""), 0, readFront = false)
        assertTrue(audio.question.speech.isEmpty())
        assertEquals(listOf(CardGenerator.Speech("Yo hablo español", FieldLanguage.FRONT)), audio.answer.speech)
    }

    @Test fun deckWithoutLanguageDropsSpeech() {
        val audio = CardGenerator.audio(vocab, listOf("gato", "cat"), 0)
        assertTrue(audio.question.resolve(deck.copy(languageFront = null)).speech.isEmpty())
    }

    @Test fun clozeReadsBlankThenFullSentence() {
        val fields = listOf("Yo {{c1::hablo}} español [sound:s.m4a]", "")
        val audio = CardGenerator.audio(cloze, fields, 0)
        assertEquals(listOf(CardGenerator.Speech("Yo … español", FieldLanguage.FRONT)), audio.question.speech)
        assertEquals(listOf(CardGenerator.Speech("Yo hablo español", FieldLanguage.FRONT)), audio.answer.speech)
    }
}
