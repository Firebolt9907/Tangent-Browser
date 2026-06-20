package com.firebolt9907.luminabrowser.ui.reader

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReaderView(
    article: ReaderArticle,
    theme: ReaderTheme,
    fontSizePercent: Int,
    fontName: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp
) {
    val backgroundColor = when (theme) {
        ReaderTheme.White -> Color(0xFFFFFFFF)
        ReaderTheme.Sepia -> Color(0xFFF4ECD8)
        ReaderTheme.Dark -> Color(0xFF1C1C1E)
    }

    val textColor = when (theme) {
        ReaderTheme.White -> Color(0xFF1C1C1E)
        ReaderTheme.Sepia -> Color(0xFF3C301D)
        ReaderTheme.Dark -> Color(0xFFE5E5EA)
    }

    val mutedTextColor = when (theme) {
        ReaderTheme.White -> Color(0xFF8E8E93)
        ReaderTheme.Sepia -> Color(0xFF7E6F5C)
        ReaderTheme.Dark -> Color(0xFF8E8E93)
    }

    val borderColor = when (theme) {
        ReaderTheme.White -> Color(0xFFD1D1D6)
        ReaderTheme.Sepia -> Color(0xFFC4B69F)
        ReaderTheme.Dark -> Color(0xFF3A3A3C)
    }

    val fontFamily = getFontFamily(fontName)
    val scale = fontSizePercent / 100f

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .background(backgroundColor)
            .fillMaxSize()
    ) {
        // Let users select and copy article text — a basic expectation in a reader view.
        SelectionContainer {
        Column(
            modifier = Modifier
                // Cap the line length on wide screens (landscape / tablets) so reading text
                // stays at a comfortable measure instead of spanning edge to edge. On phones in
                // portrait the screen is narrower than the cap, so this is a no-op there.
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = bottomPadding + 32.dp)
        ) {
            // Title
            Text(
                text = article.title,
                fontSize = (28.sp * scale),
                lineHeight = (36.sp * scale),
                fontWeight = FontWeight.Bold,
                fontFamily = fontFamily,
                color = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // Byline
            article.byline?.let { byline ->
                Text(
                    text = byline,
                    fontSize = (14.sp * scale),
                    lineHeight = (18.sp * scale),
                    fontStyle = FontStyle.Italic,
                    fontFamily = fontFamily,
                    color = mutedTextColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                )
            }

            // Article Content
            article.blocks.forEach { block ->
                when (block) {
                    is ReaderBlock.Paragraph -> {
                        Text(
                            text = block.text,
                            fontSize = (18.sp * scale),
                            lineHeight = (28.sp * scale),
                            fontFamily = fontFamily,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        )
                    }
                    is ReaderBlock.Heading -> {
                        val headerSize = when (block.level) {
                            1 -> 24.sp
                            2 -> 22.sp
                            3 -> 20.sp
                            else -> 18.sp
                        }
                        val headerLineHeight = when (block.level) {
                            1 -> 30.sp
                            2 -> 28.sp
                            3 -> 26.sp
                            else -> 24.sp
                        }
                        Text(
                            text = block.text,
                            fontSize = (headerSize * scale),
                            lineHeight = (headerLineHeight * scale),
                            fontWeight = FontWeight.Bold,
                            fontFamily = fontFamily,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 12.dp)
                        )
                    }
                    is ReaderBlock.Blockquote -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                                .drawBehind {
                                    val strokeWidth = 4.dp.toPx()
                                    drawLine(
                                        color = borderColor,
                                        start = Offset(0f, 0f),
                                        end = Offset(0f, size.height),
                                        strokeWidth = strokeWidth
                                    )
                                }
                                .padding(start = 16.dp)
                        ) {
                            Text(
                                text = block.text,
                                fontSize = (18.sp * scale),
                                lineHeight = (28.sp * scale),
                                fontStyle = FontStyle.Italic,
                                fontFamily = fontFamily,
                                color = mutedTextColor
                            )
                        }
                    }
                    is ReaderBlock.ListItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, bottom = 8.dp)
                        ) {
                            val prefix = if (block.ordered) "${block.index + 1}. " else "• "
                            Text(
                                text = prefix,
                                fontSize = (18.sp * scale),
                                lineHeight = (28.sp * scale),
                                fontFamily = fontFamily,
                                color = textColor
                            )
                            Text(
                                text = block.text,
                                fontSize = (18.sp * scale),
                                lineHeight = (28.sp * scale),
                                fontFamily = fontFamily,
                                color = textColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
        } // SelectionContainer
    }
}

private fun getFontFamily(fontName: String): FontFamily {
    return when (fontName) {
        "Georgia" -> FontFamily(Typeface.create("georgia", Typeface.NORMAL))
        "Charter" -> FontFamily(Typeface.create("charter", Typeface.NORMAL))
        "Athelas" -> FontFamily(Typeface.create("athelas", Typeface.NORMAL))
        else -> FontFamily.Default
    }
}
