package com.speedcards.data.io

/**
 * Converts Anki's HTML fields and templates into the plain text this app renders.
 * Images become `[img:file]` markers; `[sound:file]` tags pass through untouched.
 */
object HtmlText {
    private val styleOrScript = Regex("""<(style|script)[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val br = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val blockEnd = Regex("""</(div|p|li|h[1-6]|tr)\s*>""", RegexOption.IGNORE_CASE)
    private val img = Regex("""<img[^>]*?src\s*=\s*["']?([^"'\s>]+)["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val answerRule = Regex("""<hr[^>]*id\s*=\s*["']?answer["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("""<[^>]+>""")
    private val numericEntity = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val manyNewlines = Regex("""\n{3,}""")
    private val spaces = Regex("""[ \t ]+""")

    fun field(html: String): String = clean(html)

    /** Templates: the answer rule becomes the "---" divider; Anki-only filters are simplified. */
    fun template(html: String): String {
        var t = answerRule.replace(html, "\n---\n")
        t = t.replace(Regex("""\{\{\s*type:[^\}]*\}\}"""), "")
        t = t.replace(Regex("""\{\{\s*tts[^\}]*\}\}"""), "")
        t = t.replace(Regex("""\{\{\s*(?:hint|edit|furigana|kana|kanji):([^\}]+)\}\}""")) { "{{${it.groupValues[1].trim()}}}" }
        return clean(t)
    }

    private fun clean(html: String): String {
        var t = styleOrScript.replace(html, "")
        t = img.replace(t) { "[img:${it.groupValues[1]}]" }
        t = br.replace(t, "\n")
        t = blockEnd.replace(t, "\n")
        t = anyTag.replace(t, "")
        t = decodeEntities(t)
        t = t.lines().joinToString("\n") { spaces.replace(it, " ").trim() }
        return manyNewlines.replace(t, "\n\n").trim()
    }

    fun decodeEntities(text: String): String {
        var t = numericEntity.replace(text) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x") || v.startsWith("X")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'")
        return t.replace("&amp;", "&")
    }
}

/**
 * Rewrites media references after files are stored under new (hashed) names.
 * Handles `[sound:x]`, `[img:x]`, and fields that contain only a bare file name (CSV "audio" columns).
 */
class MediaResolver(private val stored: Map<String, String>) {
    private val sound = Regex("""\[sound:([^\]]+)\]""")
    private val image = Regex("""\[img:([^\]]+)\]""")

    fun resolve(text: String): String {
        if (stored.isEmpty()) return text
        val trimmed = text.trim()
        stored[trimmed]?.let { name -> return if (isImage(trimmed)) "[img:$name]" else "[sound:$name]" }
        var t = sound.replace(text) { m -> stored[m.groupValues[1].trim()]?.let { "[sound:$it]" } ?: m.value }
        t = image.replace(t) { m -> stored[m.groupValues[1].trim()]?.let { "[img:$it]" } ?: m.value }
        return t
    }

    companion object {
        private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")
        private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "3gp", "amr", "webm")

        fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in imageExt
        fun isMedia(name: String) = name.substringAfterLast('.', "").lowercase().let { it in imageExt || it in audioExt }

        fun mimeFor(name: String): String = when (val ext = name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "webm" -> "audio/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            in imageExt -> "image/$ext"
            else -> "application/octet-stream"
        }
    }
}
