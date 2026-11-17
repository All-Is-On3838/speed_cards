package com.speedcards.data.template

/**
 * Splits tone-marked pinyin into syllables so each can be coloured by tone.
 * Words written together ("cèsuǒ", "fāng'àn", "Xī'ān") are segmented against the list of valid syllables;
 * a syllable may only start with a vowel at the start of a word (otherwise pinyin would use an apostrophe).
 */
object Pinyin {
    private val toneMarks: Map<Char, Pair<Char, Int>> = buildMap {
        fun add(base: Char, marked: String) = marked.forEachIndexed { i, c -> put(c, base to i + 1) }
        add('a', "āáǎà"); add('e', "ēéěè"); add('i', "īíǐì"); add('o', "ōóǒò"); add('u', "ūúǔù"); add('ü', "ǖǘǚǜ")
        add('A', "ĀÁǍÀ"); add('E', "ĒÉĚÈ"); add('I', "ĪÍǏÌ"); add('O', "ŌÓǑÒ"); add('U', "ŪÚǓÙ")
    }

    private const val INITIALS = "b p m f d t n l g k h j q x zh ch sh r z c s y w"
    private const val FINALS =
        "a o e ai ei ao ou an en ang eng ong er i ia ie iao iu ian in iang ing iong u ua uo uai ui uan un uang ueng ü üe üan ün ue"

    /** Valid toneless syllables (ü written as "v" for lookup). */
    private val syllables: Set<String> by lazy {
        val initials = INITIALS.split(' ')
        val finals = FINALS.split(' ')
        val out = HashSet<String>()
        // Zero-initial syllables.
        listOf("a", "o", "e", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "er").forEach { out += it }
        for (ini in initials) for (fin in finals) out += (ini + fin).replace('ü', 'v')
        // Common spelling rules: yi/wu/yu forms, ju/qu/xu, zhi/chi/shi/ri/zi/ci/si.
        out += listOf("yi", "ya", "ye", "yao", "you", "yan", "yin", "yang", "ying", "yong", "yu", "yue", "yuan", "yun",
            "wu", "wa", "wo", "wai", "wei", "wan", "wen", "wang", "weng",
            "zhi", "chi", "shi", "ri", "zi", "ci", "si", "ju", "qu", "xu", "jue", "que", "xue", "juan", "quan", "xuan", "jun", "qun", "xun",
            "lv", "nv", "lve", "nve", "m", "n", "ng", "hm", "hng")
        out
    }

    /** Wraps each syllable as TONE_START + tone digit (0 = neutral) + syllable + TONE_END. */
    fun markTones(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (!isPinyinLetter(text[i])) { sb.append(text[i]); i++; continue }
            var j = i
            while (j < text.length && isPinyinLetter(text[j])) j++
            for (syl in segment(text.substring(i, j))) {
                sb.append(TemplateRenderer.TONE_START).append(toneOf(syl).digitToChar()).append(syl).append(TemplateRenderer.TONE_END)
            }
            i = j
        }
        return sb.toString()
    }

    /** Tone 1–4 from the diacritic, 0 when unmarked (neutral). */
    fun toneOf(syllable: String): Int = syllable.firstNotNullOfOrNull { toneMarks[it]?.second } ?: 0

    /** Splits a run of letters into syllables; falls back to the whole run when it can't be parsed. */
    fun segment(word: String): List<String> {
        val plain = word.map { c -> (toneMarks[c]?.first ?: c).lowercaseChar().let { if (it == 'ü') 'v' else it } }.joinToString("")
        val n = plain.length
        // best[k] = fewest syllables covering plain[0, k), with the split points.
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val prev = IntArray(n + 1) { -1 }
        best[0] = 0
        for (start in 0 until n) {
            if (best[start] == Int.MAX_VALUE) continue
            for (len in 1..minOf(6, n - start)) {
                val piece = plain.substring(start, start + len)
                if (piece !in syllables) continue
                if (start > 0 && piece[0] in "aoe") continue // would need an apostrophe
                val end = start + len
                if (best[start] + 1 < best[end]) { best[end] = best[start] + 1; prev[end] = start }
            }
        }
        if (best[n] == Int.MAX_VALUE) return listOf(word)
        val cuts = mutableListOf<Int>()
        var k = n
        while (k > 0) { cuts += k; k = prev[k] }
        var from = 0
        return cuts.reversed().map { to -> word.substring(from, to).also { from = to } }
    }

    private fun isPinyinLetter(c: Char) = c.isLetter() && c.code < 0x2E80 // excludes CJK
}
