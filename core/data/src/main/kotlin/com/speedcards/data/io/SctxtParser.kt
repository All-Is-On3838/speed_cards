package com.speedcards.data.io

/**
 * Speed Cards text format (`.sctxt`) — easy to write by hand on a computer, and it can carry links.
 *
 * ```
 * # Lines starting with # are comments.
 * @deck Spanish::Gym          ← settings that apply to the notes below
 * @type Vocab
 * @tags gym
 * @languages es-MX en-US      ← language you're learning, then your language (for the current @deck)
 *
 * id: gym-barra               ← optional; lets you re-import edits without duplicates
 * Word: la barra
 * Meaning: barbell
 * Example: Levanto la barra.
 *   Indented lines continue the previous field.
 * tags: equipment
 * links: collocation [[las mancuernas]]; synonym [[gym-pesa]]
 *
 * Word: las mancuernas        ← a blank line (or ---) starts the next note
 * Meaning: dumbbells
 * ```
 * `links` targets are an id or the first field of another card. Field names match the note type's fields.
 */
object SctxtParser {
    class ParseException(message: String) : Exception(message)

    private val reserved = setOf("id", "tags", "links", "deck", "type")
    private val keyLine = Regex("""^([^\s:][^:]*?)\s*:\s?(.*)$""")
    private val linkTarget = Regex("""\[\[([^\]]+)\]\]""")

    fun parse(text: String, defaultDeck: String = "Default", defaultType: String = "Basic"): ImportDocument {
        var deck = defaultDeck
        var type = defaultType
        var tags = emptyList<String>()
        val deckSettings = mutableMapOf<String, DeckSettings>()
        val notes = mutableListOf<ImportNote>()

        var fields = linkedMapOf<String, String>()
        var meta = mutableMapOf<String, String>()
        var lastKey: String? = null
        var startLine = 0

        fun flush() {
            if (fields.isNotEmpty() || meta.isNotEmpty()) {
                val noteTags = tags + meta["tags"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
                notes += ImportNote(
                    id = meta["id"]?.trim()?.ifEmpty { null },
                    deck = meta["deck"]?.trim() ?: deck,
                    type = meta["type"]?.trim() ?: type,
                    fields = fields.mapValues { it.value.trim() },
                    tags = noteTags.distinct(),
                    links = parseLinks(meta["links"].orEmpty()),
                    line = startLine,
                )
            }
            fields = linkedMapOf(); meta = mutableMapOf(); lastKey = null
        }

        text.removePrefix("﻿").lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.trimEnd()
            when {
                line.isBlank() || line.trim() == "---" -> flush()
                line.startsWith("#") -> {}
                line.startsWith("@") -> {
                    flush()
                    val directive = line.drop(1).substringBefore(' ').lowercase()
                    val value = line.substringAfter(' ', "").trim()
                    when (directive) {
                        "deck" -> deck = value
                        "type" -> type = value
                        "tags" -> tags = value.split(Regex("\\s+")).filter { it.isNotBlank() }
                        "languages" -> {
                            val parts = value.split(Regex("\\s+"))
                            deckSettings[deck] = DeckSettings(parts.getOrNull(0), parts.getOrNull(1))
                        }
                        else -> throw ParseException("Line $lineNo: unknown setting @$directive")
                    }
                }
                raw.startsWith(" ") || raw.startsWith("\t") -> {
                    val key = lastKey ?: throw ParseException("Line $lineNo: indented line doesn't continue a field")
                    if (key in fields) fields[key] = fields.getValue(key) + "\n" + line.trim()
                    else meta[key] = meta.getValue(key) + " " + line.trim()
                }
                else -> {
                    val m = keyLine.find(line) ?: throw ParseException("Line $lineNo: expected \"Field: value\"")
                    if (fields.isEmpty() && meta.isEmpty()) startLine = lineNo
                    val key = m.groupValues[1].trim()
                    val value = m.groupValues[2]
                    if (key.lowercase() in reserved) { meta[key.lowercase()] = value; lastKey = key.lowercase() }
                    else { fields[key] = value; lastKey = key }
                }
            }
        }
        flush()
        return ImportDocument(notes, deckSettings)
    }

    /** "synonym [[a]] [[b]]; conjugation of [[hablar]]" */
    fun parseLinks(value: String): List<ImportLink> =
        value.split(';').flatMap { part ->
            val type = part.substringBefore("[[").trim()
            if (type.isEmpty()) emptyList()
            else linkTarget.findAll(part).map { ImportLink(type, it.groupValues[1].trim()) }.toList()
        }

    /** Writes notes back out in the same format (used for exporting decks). */
    fun write(notes: List<ImportNote>): String = buildString {
        var deck: String? = null
        var type: String? = null
        for (n in notes) {
            if (n.deck != deck) { appendLine("@deck ${n.deck}"); deck = n.deck }
            if (n.type != type) { appendLine("@type ${n.type}"); type = n.type }
            appendLine()
            n.id?.let { appendLine("id: $it") }
            n.fields.forEach { (k, v) ->
                if (v.isEmpty()) return@forEach
                val lines = v.lines()
                appendLine("$k: ${lines.first()}")
                lines.drop(1).forEach { appendLine("  $it") }
            }
            if (n.tags.isNotEmpty()) appendLine("tags: ${n.tags.joinToString(" ")}")
            if (n.links.isNotEmpty()) {
                appendLine("links: " + n.links.groupBy { it.type }.entries.joinToString("; ") { (t, ls) ->
                    "$t " + ls.joinToString(" ") { "[[${it.target}]]" }
                })
            }
        }
    }
}
