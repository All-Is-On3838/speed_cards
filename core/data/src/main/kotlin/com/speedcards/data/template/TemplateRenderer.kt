package com.speedcards.data.template

/** Display choices that change how templates render. */
data class RenderOptions(
    /** For `{{hanzi:Simplified|Traditional}}`: show Traditional as the main script. */
    val traditionalFirst: Boolean = false,
    /** For `{{pinyin:Field}}`: colour each syllable by tone. */
    val toneColors: Boolean = true,
)

/**
 * Renders card templates into plain text with lightweight markers that the UI turns into styling.
 *
 * Supported syntax (a subset of Anki's, plus a few language helpers):
 * - `{{Field}}` substitutes a field (cloze markup is shown as plain text)
 * - `{{FrontSide}}` on the back inserts the rendered front
 * - `{{#Field}}…{{/Field}}` keeps the section only when the field is non-empty, `{{^Field}}` when empty
 * - `{{cloze:Field}}` renders cloze deletions (for normal note types: the c1 deletion)
 * - `{{audio:Field}}` shows a speaker symbol; the field is read aloud instead of shown
 * - `{{hanzi:A|B}}` shows field A with B underneath, smaller (swapped when Traditional is preferred)
 * - `{{pinyin:Field}}` colours pinyin syllables by tone
 * - a line containing only `---` becomes a divider
 */
object TemplateRenderer {
    /** Wraps highlighted text (cloze blanks and answers). */
    const val HIGHLIGHT_START = '\u0001'
    const val HIGHLIGHT_END = '\u0002'
    /** Wraps secondary (smaller, muted) text. */
    const val SECONDARY_START = '\u0003'
    const val SECONDARY_END = '\u0004'
    /** Wraps a pinyin syllable: TONE_START, tone digit 0–4, the syllable, TONE_END. */
    const val TONE_START = '\u0005'
    const val TONE_END = '\u0006'
    const val DIVIDER = "---"
    const val SPEAKER = "🔊"

    private val sectionRegex = Regex("""\{\{([#^])([^\}]+)\}\}(.*?)\{\{/\2\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val tagRegex = Regex("""\{\{([^\}]+)\}\}""")
    private val markerRegex = Regex("[\u0001-\u0004\u0006]|\u0005[0-4]")

    fun render(
        template: String,
        fieldNames: List<String>,
        values: List<String>,
        clozeOrd: Int? = null,
        showClozeAnswer: Boolean = false,
        frontSide: String = "",
        options: RenderOptions = RenderOptions(),
    ): String {
        val byName = fieldNames.mapIndexed { i, n -> n to values.getOrElse(i) { "" } }.toMap()
        fun field(name: String) = byName[name.trim()].orEmpty()
        var text = template
        // Resolve sections repeatedly to support nesting.
        while (true) {
            val replaced = sectionRegex.replace(text) { m ->
                val inverted = m.groupValues[1] == "^"
                val filled = field(m.groupValues[2]).isNotBlank()
                if (filled != inverted) m.groupValues[3] else ""
            }
            if (replaced == text) break
            text = replaced
        }
        text = tagRegex.replace(text) { m ->
            val tag = m.groupValues[1].trim()
            val arg = tag.substringAfter(':')
            when {
                tag == "FrontSide" -> frontSide
                tag.startsWith("cloze:") -> Cloze.render(field(arg), clozeOrd ?: 0, showClozeAnswer)
                tag.startsWith("text:") -> Cloze.strip(field(arg))
                tag.startsWith("audio:") -> if (field(arg).isNotBlank()) SPEAKER else ""
                tag.startsWith("hanzi:") -> {
                    val (a, b) = arg.split('|').map { Cloze.strip(field(it)) }.let { it[0] to it.getOrElse(1) { "" } }
                    val (main, other) = if (options.traditionalFirst && b.isNotBlank()) b to a else a to b
                    if (other.isBlank() || other == main) main else "$main\n$SECONDARY_START$other$SECONDARY_END"
                }
                tag.startsWith("pinyin:") -> field(arg).let { if (options.toneColors) Pinyin.markTones(it) else it }
                else -> Cloze.strip(field(tag))
            }
        }
        return text.lines().joinToString("\n") { it.trimEnd() }.trim()
    }

    /** Names of fields a template refers to (ignoring FrontSide), used to decide if a card should exist. */
    fun referencedFields(template: String): Set<String> =
        tags(template).filter { it != "FrontSide" }.flatMap { it.substringAfter(':').split('|') }.map { it.trim() }.toSet()

    /** Fields used as `{{cloze:Field}}` in [template]. */
    fun clozeFields(template: String): Set<String> =
        tags(template).filter { it.startsWith("cloze:") }.map { it.substringAfter(':').trim() }.toSet()

    private fun tags(template: String): List<String> {
        val withoutSections = template.replace(Regex("""\{\{[#^/][^\}]+\}\}"""), "")
        return tagRegex.findAll(withoutSections).map { it.groupValues[1].trim() }.toList()
    }

    /** Removes markers for plain-text use (search, duplicate checks, TTS). */
    fun stripMarkers(text: String): String = text.replace(markerRegex, "")
}

object Cloze {
    private val clozeRegex = Regex("""\{\{c(\d+)::(.*?)(?:::(.*?))?\}\}""", RegexOption.DOT_MATCHES_ALL)

    /** Distinct cloze numbers (1-based) used anywhere in [text]. */
    fun numbers(text: String): Set<Int> =
        clozeRegex.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.toSet()

    /**
     * Front: the active cloze becomes `[…]` (or `[hint]`), other clozes show their text.
     * Back: the active cloze shows its answer, highlighted.
     */
    fun render(text: String, ord: Int, showAnswer: Boolean): String =
        clozeRegex.replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull()
            val answer = m.groupValues[2]
            val hint = m.groupValues.getOrNull(3).orEmpty()
            if (n != ord + 1) answer
            else {
                val shown = if (showAnswer) answer else if (hint.isNotEmpty()) "[$hint]" else "[…]"
                "${TemplateRenderer.HIGHLIGHT_START}$shown${TemplateRenderer.HIGHLIGHT_END}"
            }
        }

    /** Plain text with cloze markup removed, e.g. for duplicate checks. */
    fun strip(text: String): String = clozeRegex.replace(text) { it.groupValues[2] }
}

object TextNormalizer {
    private val tagRegex = Regex("<[^>]*>")
    private val spaceRegex = Regex("\\s+")

    private val imageRegex = Regex("""\[img:[^\]]*\]""")

    /** Plain readable text: no HTML tags, image markers or repeated whitespace. */
    fun plain(text: String): String =
        text.replace(imageRegex, " ").replace(tagRegex, " ").replace(spaceRegex, " ").trim()

    /** Key used for duplicate detection: no cloze markup, HTML, extra spaces or case. */
    fun sortKey(text: String): String =
        plain(Sounds.strip(Cloze.strip(text))).lowercase()
}
