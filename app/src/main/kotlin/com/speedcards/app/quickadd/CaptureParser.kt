package com.speedcards.app.quickadd

/** What another app handed us, split into the fields of a Vocab note. */
data class Captured(
    val word: String = "",
    val example: String = "",
    val source: String = "",
) {
    val isEmpty get() = word.isBlank() && example.isBlank()
}

object CaptureParser {
    private val urlRegex = Regex("""https?://\S+""")
    private val cjkRegex = Regex("""[㐀-鿿豈-﫿぀-ヿ가-힯]""")
    private val edgePunctuation = Regex("""^[\p{P}\p{S}]+|[\p{P}\p{S}]+$""")

    /** Longest text still treated as a single word/phrase rather than a sentence. */
    private const val MAX_WORDS = 3
    private const val MAX_CJK_CHARS = 6

    /**
     * @param text selected or shared text
     * @param subject share subject (browsers put the page title here)
     */
    fun parse(text: String?, subject: String? = null): Captured {
        val raw = text.orEmpty().trim()
        val url = urlRegex.find(raw)?.value
        val body = raw.replace(urlRegex, "").trim().trim('"', '“', '”')
        val source = listOfNotNull(subject?.trim()?.takeIf { it.isNotEmpty() && it != body }, url).joinToString(" — ")
        if (body.isEmpty()) return Captured(source = source)
        return if (isSentence(body)) Captured(example = body, source = source)
        else Captured(word = body.replace(edgePunctuation, ""), source = source)
    }

    fun isSentence(text: String): Boolean =
        if (cjkRegex.containsMatchIn(text)) text.count { cjkRegex.matches(it.toString()) } > MAX_CJK_CHARS
        else text.split(Regex("\\s+")).count { it.isNotBlank() } > MAX_WORDS

    /** Tappable words for picking the target word out of a sentence (space-separated languages only). */
    fun wordChips(sentence: String): List<String> =
        if (cjkRegex.containsMatchIn(sentence)) emptyList()
        else sentence.split(Regex("\\s+")).map { it.replace(edgePunctuation, "") }.filter { it.isNotBlank() }.distinct()
}
