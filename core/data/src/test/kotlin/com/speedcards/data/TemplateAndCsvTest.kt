package com.speedcards.data

import com.speedcards.data.csv.CsvParser
import com.speedcards.data.db.SchedulingPreset
import com.speedcards.data.repo.CardGenerator
import com.speedcards.data.repo.Defaults
import com.speedcards.data.template.Cloze
import com.speedcards.data.template.TemplateRenderer
import com.speedcards.data.template.TemplateRenderer.HIGHLIGHT_END
import com.speedcards.data.template.TemplateRenderer.HIGHLIGHT_START
import com.speedcards.data.template.TextNormalizer
import com.speedcards.data.util.DayClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TemplateAndCsvTest {

    private val vocab = Defaults.noteTypes.first { it.name == Defaults.NOTE_TYPE_VOCAB }
    private val cloze = Defaults.noteTypes.first { it.name == Defaults.NOTE_TYPE_CLOZE }

    @Test fun rendersFieldsAndFrontSide() {
        val basic = Defaults.noteTypes.first()
        val r = CardGenerator.render(basic, listOf("hola", "hello"), 0)
        assertEquals("hola", r.question)
        assertEquals("hola\n---\nhello", r.answer)
    }

    @Test fun conditionalSectionsDropEmptyFields() {
        val r = CardGenerator.render(vocab, listOf("gato", "cat", "", "", "", ""), 0)
        assertEquals("gato\n---\ncat", r.answer)
        val withExample = CardGenerator.render(vocab, listOf("gato", "cat", "El gato duerme.", "The cat sleeps.", "", ""), 0)
        assertEquals("gato\n---\ncat\n\nEl gato duerme.\nThe cat sleeps.", withExample.answer)
    }

    @Test fun clozeFrontAndBack() {
        val text = "Yo {{c1::hablo}} {{c2::español::language}}"
        assertEquals(setOf(1, 2), Cloze.numbers(text))
        val c2 = CardGenerator.render(cloze, listOf(text, ""), 1)
        assertEquals("Yo hablo ${HIGHLIGHT_START}[language]$HIGHLIGHT_END", c2.question)
        assertEquals("Yo hablo ${HIGHLIGHT_START}español$HIGHLIGHT_END", c2.answer)
        val c1 = CardGenerator.render(cloze, listOf(text, ""), 0)
        assertEquals("Yo ${HIGHLIGHT_START}[…]$HIGHLIGHT_END español", c1.question)
    }

    @Test fun cardOrdsFollowFilledFields() {
        assertEquals(setOf(0, 1), CardGenerator.ordsFor(vocab, listOf("gato", "cat")))
        assertEquals(setOf(0), CardGenerator.ordsFor(vocab, listOf("gato", "")))
        assertEquals(setOf(0, 2), CardGenerator.ordsFor(cloze, listOf("{{c1::a}} {{c3::b}}", "")))
    }

    @Test fun sortKeyNormalizes() {
        assertEquals("el gato", TextNormalizer.sortKey("  El <b>Gato</b> "))
        assertEquals("yo hablo", TextNormalizer.sortKey("Yo {{c1::hablo}}"))
    }

    @Test fun referencedFieldsIgnoresSectionsAndFrontSide() {
        assertEquals(setOf("Meaning"), TemplateRenderer.referencedFields("{{Meaning}}"))
        assertEquals(setOf("Text"), TemplateRenderer.referencedFields("{{cloze:Text}}"))
        assertEquals(setOf("Word", "Notes"), TemplateRenderer.referencedFields("{{FrontSide}}{{Word}}{{#Notes}}{{Notes}}{{/Notes}}"))
    }

    @Test fun csvHandlesQuotesNewlinesAndBom() {
        val text = "﻿word,meaning\n\"hola, amigo\",\"hi \"\"friend\"\"\"\r\n\"multi\nline\",x\n\n"
        assertEquals(
            listOf(listOf("word", "meaning"), listOf("hola, amigo", "hi \"friend\""), listOf("multi\nline", "x")),
            CsvParser.parse(text),
        )
    }

    @Test fun csvDetectsDelimiters() {
        assertEquals('\t', CsvParser.detectDelimiter("a\tb\tc\n1\t2\t3"))
        assertEquals(';', CsvParser.detectDelimiter("a;b;c"))
        assertEquals(',', CsvParser.detectDelimiter("a,b"))
        assertEquals(listOf(listOf("猫", "māo", "cat")), CsvParser.parse("猫\tmāo\tcat"))
    }

    @Test fun csvEscapeRoundTrips() {
        val values = listOf("plain", "a,b", "say \"hi\"", "two\nlines")
        val line = values.joinToString(",") { CsvParser.escape(it) }
        assertEquals(listOf(values), CsvParser.parse(line))
    }

    @Test fun stepsParseAndFormat() {
        assertEquals(listOf(10.0, 60.0, 1440.0), SchedulingPreset.parseSteps("10m 1h 1d"))
        assertEquals(listOf(10.0, 90.0), SchedulingPreset.parseSteps("10, 90 bogus"))
        assertEquals("10m 1h 1d", SchedulingPreset.formatSteps(listOf(10.0, 60.0, 1440.0)))
    }

    @Test fun studyDayRollsOverAt4am() {
        val zone = ZoneId.of("America/New_York")
        fun at(h: Int) = ZonedDateTime.of(2026, 10, 15, h, 0, 0, 0, zone).toInstant().toEpochMilli()
        val clock = DayClock(zone = { zone })
        assertEquals(at(4), clock.startOfDay(at(23)))
        assertEquals(at(4) - 24 * 3600_000L, clock.startOfDay(at(2)))
        assertEquals(at(4) + 24 * 3600_000L, clock.endOfDay(at(10)))
    }
}
