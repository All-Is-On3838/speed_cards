package com.speedcards.data.template

/** `[sound:file.m4a]` tags, the same format Anki uses, so .apkg audio maps over directly. */
object Sounds {
    private val soundRegex = Regex("""\[sound:([^\]]+)\]""")

    fun tag(fileName: String) = "[sound:$fileName]"

    fun extract(text: String): List<String> = soundRegex.findAll(text).map { it.groupValues[1].trim() }.toList()

    fun strip(text: String): String =
        soundRegex.replace(text, "").lines().joinToString("\n") { it.trimEnd() }.trim()

    /** Replaces the text part of a field while keeping its sound tags (at the end). */
    fun withText(field: String, newText: String): String = join(newText, extract(field))

    fun join(text: String, sounds: List<String>): String =
        (listOf(text.trim()) + sounds.map(::tag)).filter { it.isNotEmpty() }.joinToString(" ")
}
