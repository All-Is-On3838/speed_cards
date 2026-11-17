package com.speedcards.data.repo

import com.speedcards.data.db.FieldLanguage
import com.speedcards.data.db.NoteKind
import com.speedcards.data.db.NoteType
import com.speedcards.data.template.Cloze
import com.speedcards.data.template.RenderOptions
import com.speedcards.data.template.Sounds
import com.speedcards.data.template.TemplateRenderer
import com.speedcards.data.template.TextNormalizer

/** Decides which cards (ords) a note should have. */
object CardGenerator {
    private val blankRegex = Regex("""\[[^\]]*\]""")

    fun ordsFor(type: NoteType, fields: List<String>): Set<Int> = when (type.kind) {
        NoteKind.CLOZE -> fields.flatMap { Cloze.numbers(it) }.map { it - 1 }.toSet()
        NoteKind.STANDARD -> type.templates.indices.filter { i ->
            val front = type.templates[i].front
            fun value(name: String) = type.fields.indexOfFirst { it.name == name }.let { idx -> if (idx < 0) "" else fields.getOrElse(idx) { "" } }
            // A {{cloze:Field}} card only exists when the field actually has a blank.
            val clozeFields = TemplateRenderer.clozeFields(front)
            if (clozeFields.isNotEmpty()) clozeFields.any { Cloze.numbers(value(it)).isNotEmpty() }
            else TemplateRenderer.referencedFields(front).any { value(it).isNotBlank() }
        }.toSet()
    }

    data class RenderedCard(val question: String, val answer: String)

    fun render(type: NoteType, fields: List<String>, ord: Int, options: RenderOptions = RenderOptions()): RenderedCard {
        val names = type.fields.map { it.name }
        val template = when (type.kind) {
            NoteKind.CLOZE -> type.templates.first()
            NoteKind.STANDARD -> type.templates.getOrElse(ord) { type.templates.first() }
        }
        val clozeOrd = if (type.kind == NoteKind.CLOZE) ord else null
        val question = TemplateRenderer.render(template.front, names, fields, clozeOrd, showClozeAnswer = false, options = options)
        val answer = TemplateRenderer.render(
            template.back, names, fields, clozeOrd, showClozeAnswer = true, frontSide = question, options = options,
        )
        return RenderedCard(question, answer)
    }

    /** Text to read aloud, tagged with the field's language role (resolved against the deck later). */
    data class Speech(val text: String, val language: FieldLanguage)

    /** Recorded sounds play when present; otherwise the speech list is read with text-to-speech. */
    data class SideAudio(val sounds: List<String>, val speech: List<Speech>)

    data class CardAudio(val question: SideAudio, val answer: SideAudio)

    /**
     * With [readFront] off the question side is silent and its audio moves to the answer side,
     * except on listening cards (`{{audio:…}}` on the front), where the audio is the question.
     */
    fun audio(type: NoteType, fields: List<String>, ord: Int, readFront: Boolean = true): CardAudio {
        val rendered = render(type, fields, ord)
        val questionSounds = Sounds.extract(rendered.question)
        val answerSounds = Sounds.extract(rendered.answer) - questionSounds.toSet()

        fun valueOf(name: String): Pair<String, FieldLanguage>? {
            val i = type.fields.indexOfFirst { it.name == name }
            if (i < 0) return null
            return fields.getOrElse(i) { "" } to type.fields[i].language
        }
        fun speak(text: String, lang: FieldLanguage): Speech? {
            val clean = TextNormalizer.plain(Sounds.strip(Cloze.strip(text)))
            return if (clean.isBlank() || lang == FieldLanguage.NONE) null else Speech(clean, lang)
        }

        val template = when (type.kind) {
            NoteKind.CLOZE -> type.templates.first()
            NoteKind.STANDARD -> type.templates.getOrElse(ord) { type.templates.first() }
        }
        val frontNames = TemplateRenderer.referencedFields(template.front)
        val backNames = TemplateRenderer.referencedFields(template.back) - frontNames

        val questionSpeech: List<Speech>
        val answerSpeech: List<Speech>
        // Question lines the answer side doesn't already read in full (cloze sentences it does).
        val frontOnlySpeech: List<Speech>
        if (type.kind == NoteKind.CLOZE) {
            // Front reads the sentence with a pause for the blank; back reads it whole, then Extra.
            questionSpeech = frontNames.mapNotNull { name ->
                valueOf(name)?.let { (v, lang) ->
                    val withBlank = TemplateRenderer.stripMarkers(Cloze.render(Sounds.strip(v), ord, false))
                    speak(withBlank.replace(blankRegex, "…"), lang)
                }
            }
            answerSpeech = frontNames.mapNotNull { name -> valueOf(name)?.let { (v, lang) -> speak(Cloze.strip(v), lang) } } +
                backNames.mapNotNull { name -> valueOf(name)?.let { (v, lang) -> speak(v, lang) } }
            frontOnlySpeech = emptyList()
        } else {
            // Fields shown as {{cloze:…}} on the front are read with the blank left out, then in full on the back.
            val clozeFront = TemplateRenderer.clozeFields(template.front)
            questionSpeech = frontNames.mapNotNull { name ->
                valueOf(name)?.let { (v, lang) ->
                    if (name in clozeFront) speak(TemplateRenderer.stripMarkers(Cloze.render(Sounds.strip(v), 0, false)).replace(blankRegex, "…"), lang)
                    else speak(v, lang)
                }
            }
            answerSpeech = clozeFront.mapNotNull { name -> valueOf(name)?.let { (v, lang) -> speak(v, lang) } } +
                (backNames - clozeFront).mapNotNull { name -> valueOf(name)?.let { (v, lang) -> speak(v, lang) } }
            frontOnlySpeech = (frontNames - clozeFront).mapNotNull { name -> valueOf(name)?.let { (v, lang) -> speak(v, lang) } }
        }
        if (!readFront && !template.front.contains("{{audio:")) {
            return CardAudio(
                SideAudio(emptyList(), emptyList()),
                SideAudio(questionSounds + answerSounds, frontOnlySpeech + answerSpeech),
            )
        }
        return CardAudio(SideAudio(questionSounds, questionSpeech), SideAudio(answerSounds, answerSpeech))
    }
}
