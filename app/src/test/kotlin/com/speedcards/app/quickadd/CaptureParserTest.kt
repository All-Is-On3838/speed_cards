package com.speedcards.app.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureParserTest {

    @Test fun singleWordGoesToWordWithoutPunctuation() {
        assertEquals(Captured(word = "carcaj"), CaptureParser.parse("carcaj,"))
        assertEquals(Captured(word = "peso muerto"), CaptureParser.parse("  peso muerto "))
    }

    @Test fun sentenceGoesToExample() {
        val c = CaptureParser.parse("Guardo las flechas en el carcaj.")
        assertEquals("", c.word)
        assertEquals("Guardo las flechas en el carcaj.", c.example)
        assertEquals(listOf("Guardo", "las", "flechas", "en", "el", "carcaj"), CaptureParser.wordChips(c.example))
    }

    @Test fun chromeShareSplitsTitleAndUrl() {
        val c = CaptureParser.parse("\"el carcaj\" https://example.com/arco", subject = "Tiro con arco - Wiki")
        assertEquals("el carcaj", c.word)
        assertEquals("Tiro con arco - Wiki — https://example.com/arco", c.source)
    }

    @Test fun urlOnlyShareLeavesWordEmpty() {
        val c = CaptureParser.parse("https://example.com/page", subject = "Some page")
        assertTrue(c.isEmpty)
        assertEquals("Some page — https://example.com/page", c.source)
    }

    @Test fun chineseUsesCharacterCount() {
        assertEquals("弓箭", CaptureParser.parse("弓箭").word)
        val sentence = CaptureParser.parse("他每天练习射箭。")
        assertEquals("他每天练习射箭。", sentence.example)
        assertTrue(CaptureParser.wordChips(sentence.example).isEmpty())
    }
}
