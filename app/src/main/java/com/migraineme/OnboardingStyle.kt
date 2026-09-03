package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The onboarding look, lifted from the store screenshots: Poppins headline with
 * ONE pink word, a handwritten sub line, pill buttons. Used by the welcome /
 * how-it-works / choice pages, the coach cards and the AI setup questionnaire.
 * Style only — none of these helpers own state or navigation.
 */
object ObStyle {
    val Pink = Color(0xFFE888F8)
    val Lavender = Color(0xFFE0CDFA)
    val Body = Color(0xFFEBE4FA)
    val Muted = Color(0xFFAA96D2)
    val CardFill = Color(0xFF301E4A)
    val CardLine = Color(0xFFC8B2F0)
    val Ink = Color(0xFF28143C)           // text on a pink button
    val Miss = Color(0xFFFFBE78)          // "you miss" line on the choice cards

    val Poppins = FontFamily(
        Font(R.font.poppins_regular, FontWeight.Normal),
        Font(R.font.poppins_medium, FontWeight.Medium),
        Font(R.font.poppins_semibold, FontWeight.SemiBold),
        Font(R.font.poppins_bold, FontWeight.Bold),
    )
    val Hand = FontFamily(Font(R.font.caveat, FontWeight.Bold))

    fun headline(size: TextUnit = 34.sp) = TextStyle(
        fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = size,
        lineHeight = size * 1.12f, color = Color.White
    )
    fun hand(size: TextUnit = 22.sp) = TextStyle(
        fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = size,
        lineHeight = size * 1.2f, color = Lavender
    )
    fun body(size: TextUnit = 15.sp) = TextStyle(
        fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = size,
        lineHeight = size * 1.4f, color = Body
    )
    fun label(size: TextUnit = 12.sp) = TextStyle(
        fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = size,
        lineHeight = size * 1.3f, color = Lavender
    )
    fun button(size: TextUnit = 15.sp) = TextStyle(
        fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = size
    )

    /**
     * "Where *HIGH* starts." -> HIGH in pink. The asterisks travel through
     * translation as plain characters, so a translated string keeps its
     * accent word wherever the translator put the stars.
     */
    fun pinkMarkup(text: String, base: Color = Color.White, accent: Color = Pink): AnnotatedString =
        buildAnnotatedString {
            var pink = false
            text.split("*").forEachIndexed { i, part ->
                if (i > 0) pink = !pink
                if (part.isNotEmpty()) withStyle(SpanStyle(color = if (pink) accent else base)) { append(part) }
            }
        }
}

@Composable
fun ObHeadline(text: String, modifier: Modifier = Modifier, size: TextUnit = 34.sp, align: TextAlign = TextAlign.Center) {
    Text(ObStyle.pinkMarkup(text), style = ObStyle.headline(size), textAlign = align, modifier = modifier)
}

@Composable
fun ObHand(text: String, modifier: Modifier = Modifier, size: TextUnit = 22.sp, align: TextAlign = TextAlign.Center) {
    Text(text, style = ObStyle.hand(size), textAlign = align, modifier = modifier)
}

@Composable
fun ObPillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true, textSize: TextUnit = 15.sp, vPad: androidx.compose.ui.unit.Dp = 10.dp) {
    if (filled) {
        Button(
            onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = ObStyle.Pink, contentColor = ObStyle.Ink,
                disabledContainerColor = ObStyle.Pink.copy(alpha = 0.35f), disabledContentColor = ObStyle.Ink.copy(alpha = 0.6f)),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = vPad), modifier = modifier
        ) { Text(label, style = ObStyle.button(textSize), maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            border = BorderStroke(1.5.dp, ObStyle.CardLine),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = vPad), modifier = modifier
        ) { Text(label, style = ObStyle.button(textSize), maxLines = 1) }
    }
}

/** Numbered point, the way the choice cards list what each path gives you. */
@Composable
fun ObNumberedPoint(n: Int, text: String, accent: Color = ObStyle.Pink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(20.dp).padding(top = 1.dp).border(1.5.dp, accent, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("$n", style = ObStyle.label(11.sp).copy(color = accent, fontWeight = FontWeight.Bold)) }
        Spacer(Modifier.width(10.dp))
        Text(text, style = ObStyle.body(14.sp), modifier = Modifier.weight(1f))
    }
}

/** The five step dots above a How It Works screen. */
@Composable
fun ObDots(count: Int, active: Int) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == active) 9.dp else 6.dp).background(if (i == active) ObStyle.Pink else ObStyle.Muted.copy(alpha = 0.6f), CircleShape))
        }
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * The store-screenshot background: a staggered lattice of faint circles, each
 * carrying a Brainy log icon at low alpha, over the purple glow. Drawn once per
 * layout pass, no animation.
 */
private val latticeIcons = listOf(
    R.drawable.brainy_trig_stress, R.drawable.brainy_trig_alcohol, R.drawable.brainy_rel_ice,
    R.drawable.brainy_trig_storm, R.drawable.brainy_trig_caffeine, R.drawable.brainy_trig_menstruation,
    R.drawable.brainy_rel_darkness, R.drawable.brainy_trig_screen_time, R.drawable.brainy_rel_water,
    R.drawable.brainy_trig_noise,
)

@Composable
fun ObLatticeBackground(modifier: Modifier = Modifier, dim: Boolean = false) {
    val painters = latticeIcons.map { androidx.compose.ui.res.painterResource(it) }
    androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
        // Glow: dark edge with a soft purple core, slightly above centre.
        drawRect(Color(0xFF1E1330))
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color(0xFF422A68).copy(alpha = 0.85f), Color(0xFF422A68).copy(alpha = 0f)),
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.4f),
                radius = size.width * 0.9f
            ),
            radius = size.width * 0.9f,
            center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.4f)
        )
        val d = 88.dp.toPx()
        val stepX = 104.dp.toPx()
        val stepY = 100.dp.toPx()
        var row = 0
        var y = -d * 0.4f
        var k = 0
        while (y < size.height + d) {
            var x = (if (row % 2 == 1) stepX / 2f else 0f) - d * 0.45f
            while (x < size.width + d) {
                drawCircle(Color.White.copy(alpha = if (dim) 0.02f else 0.035f), radius = d / 2f, center = androidx.compose.ui.geometry.Offset(x + d / 2f, y + d / 2f))
                val p = painters[k % painters.size]
                val iw = d * 0.62f
                val ih = iw * (p.intrinsicSize.height / p.intrinsicSize.width).let { if (it.isNaN() || it <= 0f) 1f else it }
                translate(left = x + (d - iw) / 2f, top = y + (d - ih) / 2f) {
                    with(p) { draw(androidx.compose.ui.geometry.Size(iw, ih), alpha = if (dim) 0.07f else 0.16f) }
                }
                k++
                x += stepX
            }
            row++
            y += stepY
        }
    }
}
