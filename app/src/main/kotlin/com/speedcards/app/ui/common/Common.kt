package com.speedcards.app.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import com.speedcards.data.db.Deck
import com.speedcards.data.db.DeckKind
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.template.Sounds
import com.speedcards.data.template.TemplateRenderer

/** Pinyin tone colours (the common Pleco/MDBG scheme): 1 red, 2 green, 3 blue, 4 purple, neutral grey. */
val ToneColors = listOf(Color(0xFF8E8E8E), Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA))

/**
 * Turns renderer markers into styled text: highlighted cloze spans are bold and coloured, secondary text
 * (e.g. Traditional characters under Simplified) is smaller and muted, pinyin syllables get tone colours.
 */
fun cardAnnotatedString(text: String, highlight: Color, muted: Color = Color.Gray): AnnotatedString = buildAnnotatedString {
    var i = 0
    val buffer = StringBuilder()
    val styles = ArrayDeque<SpanStyle>()
    fun flush() {
        if (buffer.isEmpty()) return
        val style = styles.fold(SpanStyle()) { acc, s -> acc.merge(s) }
        withStyle(style) { append(buffer) }
        buffer.clear()
    }
    while (i < text.length) {
        when (val c = text[i]) {
            TemplateRenderer.HIGHLIGHT_START -> { flush(); styles.addLast(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) }
            TemplateRenderer.SECONDARY_START -> { flush(); styles.addLast(SpanStyle(color = muted, fontSize = 0.7.em)) }
            TemplateRenderer.TONE_START -> {
                flush()
                val tone = text.getOrNull(i + 1)?.digitToIntOrNull() ?: 0
                styles.addLast(SpanStyle(color = ToneColors[tone.coerceIn(0, 4)]))
                i++ // skip the tone digit
            }
            TemplateRenderer.HIGHLIGHT_END, TemplateRenderer.SECONDARY_END, TemplateRenderer.TONE_END -> {
                flush(); styles.removeLastOrNull()
            }
            else -> buffer.append(c)
        }
        i++
    }
    flush()
}

private val dividerRegex = Regex("(?m)^---$")
private val paragraphRegex = Regex("\n\\s*\n")

/** Folder holding card media, for showing `[img:file]` images. */
val LocalMediaDir = staticCompositionLocalOf<File?> { null }

private val imageRegex = Regex("""\[img:([^\]]+)\]""")

/** Renders a card side; lines that are exactly "---" become dividers. Sound tags are hidden, images shown. */
@Composable
fun CardText(text: String, modifier: Modifier = Modifier) {
    val blocks = Sounds.strip(text).split(dividerRegex).map { it.trim('\n') }.filter { it.isNotBlank() }
    val highlight = MaterialTheme.colorScheme.primary
    val mediaDir = LocalMediaDir.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 16.dp))
            // Split around images so each shows on its own line.
            var last = 0
            val parts = mutableListOf<Pair<Boolean, String>>()
            imageRegex.findAll(block).forEach { m ->
                parts += false to block.substring(last, m.range.first)
                parts += true to m.groupValues[1]
                last = m.range.last + 1
            }
            parts += false to block.substring(last)
            parts.forEach { (isImage, value) ->
                if (isImage) CardImage(mediaDir?.let { File(it, value) })
                else if (value.isNotBlank()) {
                    // First block (the prompt) is largest; after the divider the first paragraph is the answer,
                    // later paragraphs (examples, notes) are smaller so long cards stay readable.
                    value.trim('\n').split(paragraphRegex).filter { it.isNotBlank() }.forEachIndexed { p, para ->
                        Text(
                            cardAnnotatedString(para.trim('\n'), highlight, MaterialTheme.colorScheme.outline),
                            style = when {
                                i == 0 -> MaterialTheme.typography.headlineMedium
                                p == 0 -> MaterialTheme.typography.titleLarge
                                else -> MaterialTheme.typography.bodyLarge
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = if (p > 0) 12.dp else 0.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardImage(file: File?) {
    val bitmap = remember(file) {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching {
                // Downsample large photos so they don't use lots of memory.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.padding(vertical = 8.dp).heightIn(max = 280.dp))
    } else {
        Text("[image missing]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let(optionLabel).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** Deck dropdown showing "Parent › Child" names. */
@Composable
fun DeckDropdown(
    decks: List<Deck>,
    selectedId: Long?,
    onSelect: (Deck) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Deck",
    includeInbox: Boolean = true,
) {
    val shown = decks.filter { includeInbox || it.kind != DeckKind.INBOX }
        .sortedBy { DeckRepository.fullName(decks, it).lowercase() }
    Dropdown(
        label = label,
        options = shown,
        selected = shown.firstOrNull { it.id == selectedId },
        optionLabel = { DeckRepository.fullName(decks, it) },
        onSelect = onSelect,
        modifier = modifier,
    )
}

@Composable
fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = (if (decimal) value.toDoubleOrNull() else value.toIntOrNull()) == null,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}
