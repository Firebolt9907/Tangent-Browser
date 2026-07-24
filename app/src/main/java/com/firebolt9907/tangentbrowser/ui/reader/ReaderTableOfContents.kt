package com.firebolt9907.tangentbrowser.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * The reader's Table-of-Contents affordance, extracted from [ReaderView]: a floating outline button
 * pinned top-right, plus the bottom-sheet-styled dialog it opens. A [BoxScope] extension so the
 * button can keep aligning itself to the reader surface's top-right corner. Tapping a heading
 * dismisses the dialog and smooth-scrolls the article to that heading's captured offset.
 */
@Composable
internal fun BoxScope.ReaderTableOfContents(
    headings: List<ReaderBlock.Heading>,
    headingOffsets: Map<ReaderBlock.Heading, Float>,
    showTOC: Boolean,
    onShowTOC: () -> Unit,
    onDismiss: () -> Unit,
    isButtonVisible: Boolean,
    scrollState: ScrollState,
    statusBarTop: Dp,
    statusBarTopPx: Float,
    navBarBottom: Dp,
    density: Density,
    fontFamily: FontFamily,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color,
    codeBackgroundColor: Color,
) {
    // Floating Table of Contents Button
    AnimatedVisibility(
        visible = headings.size >= 2 && isButtonVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = statusBarTop + 8.dp, end = 16.dp)
    ) {
        IconButton(
            onClick = onShowTOC,
            modifier = Modifier
                .size(40.dp)
                .shadow(4.dp, CircleShape)
                .background(
                    color = codeBackgroundColor,
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Table of Contents",
                tint = textColor
            )
        }
    }

    // Table of Contents Dialog (styled like a bottom sheet)
    if (showTOC) {
        val coroutineScope = rememberCoroutineScope()
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        onClick = onDismiss,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(backgroundColor)
                        .clickable(
                            onClick = {},
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        )
                        .padding(bottom = navBarBottom)
                ) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Table of Contents",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = fontFamily,
                            color = textColor
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = textColor
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(borderColor)
                    )

                    // Outline List
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        headings.forEach { heading ->
                            val indent = when (heading.level) {
                                1 -> 0.dp
                                2 -> 16.dp
                                3 -> 32.dp
                                else -> 48.dp
                            }
                            val textStyle = when (heading.level) {
                                1 -> TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                2 -> TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                else -> TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
                            }

                            Text(
                                text = heading.text,
                                style = textStyle,
                                fontFamily = fontFamily,
                                color = textColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        val offset = headingOffsets[heading]
                                        if (offset != null) {
                                            coroutineScope.launch {
                                                val target = (offset - statusBarTopPx - with(density) { 16.dp.toPx() }).coerceAtLeast(0f)
                                                scrollState.animateScrollTo(target.toInt())
                                            }
                                        }
                                    }
                                    .padding(start = indent, top = 12.dp, bottom = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
