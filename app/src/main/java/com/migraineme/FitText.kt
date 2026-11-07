package com.migraineme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * A label that never loses a letter.
 *
 * In a narrow slot Compose breaks a long word wherever the width runs out
 * ("Acetaminoph" over "en"), and a line limit silently drops whatever follows.
 * Both happen more on smaller phones, with a larger system font and in longer
 * translations. So this wraps at spaces up to [maxLines], and when a word still
 * does not fit, or the lines run out, the text shrinks until it does.
 *
 * Compose 1.7 has no TextAutoSize, hence the measure-and-shrink loop. It stays
 * invisible for the frames it takes to settle so the text never jumps. Shrinking
 * stops at a readable size; a word still too long there ("Dihydroergotamine")
 * gets a proper hyphen instead of being chopped.
 */
@Composable
internal fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val base = fontSize.takeIf { it.isSpecified } ?: style.fontSize.takeIf { it.isSpecified } ?: 14.sp
    var scale by remember(text, base) { mutableStateOf(1f) }
    var settled by remember(text, base) { mutableStateOf(false) }
    val floor = if (maxLines == 1) MIN_SCALE_ONE_LINE else MIN_SCALE
    val fitted = style.copy(
        hyphens = if (scale <= floor) Hyphens.Auto else style.hyphens,
        fontSize = base * scale,
        lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight,
        // labelSmall carries 0.5sp tracking, which stops a long word fitting at any size if left alone.
        letterSpacing = if (style.letterSpacing.isSpecified) style.letterSpacing * scale else style.letterSpacing,
    )
    Text(
        text,
        modifier = modifier.drawWithContent { if (settled) drawContent() },
        color = color,
        style = fitted,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            val needsLess = result.hasVisualOverflow || result.breaksAWord(text)
            if (needsLess && scale > floor) scale = (scale * 0.92f).coerceAtLeast(floor) else settled = true
        },
    )
}

/** A centred icon-tile caption: as many lines as it needs, never split mid-word. */
@Composable
internal fun TileLabel(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    FitText(text, modifier.fillMaxWidth(), color = color, style = style, textAlign = TextAlign.Center)
}

private const val MIN_SCALE = 0.8f
private const val MIN_SCALE_ONE_LINE = 0.6f

private fun TextLayoutResult.breaksAWord(text: String): Boolean {
    for (line in 0 until lineCount - 1) {
        val end = getLineEnd(line)
        if (end in 1 until text.length && text[end - 1].isLetter() && text[end].isLetter()) return true
    }
    return false
}
