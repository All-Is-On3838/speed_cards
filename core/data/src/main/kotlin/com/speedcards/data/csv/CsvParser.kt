package com.speedcards.data.csv

/** RFC 4180-style parser that also handles TSV, `;` and `|` files exported by spreadsheets. */
object CsvParser {

    private val candidates = listOf(',', '\t', ';', '|')

    /** Picks the separator that splits the first non-empty line into the most columns. */
    fun detectDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        return candidates.maxBy { d -> parseLine(firstLine, d).size }
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        val src = text.removePrefix("﻿")
        while (i < src.length) {
            val c = src[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < src.length && src[i + 1] == '"') { cell.append('"'); i++ }
                    else inQuotes = false
                } else cell.append(c)
            } else when (c) {
                '"' -> if (cell.isEmpty()) inQuotes = true else cell.append(c)
                delimiter -> { row += cell.toString(); cell.clear() }
                '\r' -> {}
                '\n' -> { row += cell.toString(); cell.clear(); rows += row.toList(); row.clear() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row.toList() }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun parseLine(line: String, delimiter: Char) = parse(line, delimiter).firstOrNull().orEmpty()

    /** Escapes a value for writing a CSV file. */
    fun escape(value: String, delimiter: Char = ','): String =
        if (value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' })
            "\"" + value.replace("\"", "\"\"") + "\""
        else value
}
