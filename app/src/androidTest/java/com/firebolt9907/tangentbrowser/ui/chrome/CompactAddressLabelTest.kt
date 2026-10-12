package com.firebolt9907.tangentbrowser.ui.chrome

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.state.BarMode
import com.firebolt9907.tangentbrowser.state.BarUiState
import com.firebolt9907.tangentbrowser.ui.theme.TangentBrowserTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CompactAddressLabelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun githubFitsAtDefaultTextSize() = checkLabel("github.com", 1f)

    @Test
    fun githubFitsAtLargeTextSize() = checkLabel("github.com", 2f)

    @Test
    fun wideGlyphsFitAtLargeTextSize() = checkLabel("www.mwm.com", 1.5f)

    @Test
    fun hiddenSecurityIconDoesNotTruncateHttpHost() = checkLabel("github.com", 2f, "http")

    @Test
    fun fractionalDensityDoesNotLosePaddingPixels() = checkLabel("github.com", 2f, displayDensity = 2.625f)

    private fun checkLabel(
        host: String,
        fontScale: Float,
        scheme: String = "https",
        displayDensity: Float? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(displayDensity ?: density.density, fontScale)) {
                TangentBrowserTheme {
                    Box(Modifier.width(360.dp)) {
                        BarExpanded(
                            state = BarUiState(host, 1, BarMode.Compact, "", pageUrl = "$scheme://$host"),
                            onUrlTap = {},
                            onAaTap = {},
                            onDragStart = {},
                            onDragDelta = {},
                            onDragEnd = { _, _ -> },
                            onReload = {},
                            interactive = false,
                            collapseProgress = { 1f },
                        )
                    }
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(host, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        compose.runOnIdle {
            assertFalse("Address text has no layout", results.isEmpty())
            assertFalse("$host is truncated at font scale $fontScale", results.single().isLineEllipsized(0))
            assertFalse("$host overflows at font scale $fontScale", results.single().didOverflowWidth)
        }
    }
}
