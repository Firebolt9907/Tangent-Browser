package com.firebolt9907.tangentbrowser.ui.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.firebolt9907.tangentbrowser.state.BarMode
import com.firebolt9907.tangentbrowser.state.BarUiState
import com.firebolt9907.tangentbrowser.ui.theme.TangentBrowserTheme
import org.junit.Rule
import org.junit.Test

class UrlEditingTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editingCanOpenCloseAndReverseMidTransition() {
        val mode = mutableStateOf(BarMode.Expanded)
        val editingText = mutableStateOf("https://example.com")
        compose.setContent {
            TangentBrowserTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    SafariBar(
                        state = BarUiState("example.com", 1, mode.value,
                            editingText.value, pageUrl = "https://example.com"),
                        onUrlTap = { mode.value = BarMode.Editing },
                        onAaTap = {},
                        onEditingTextChange = { editingText.value = it },
                        onEditingSubmit = { mode.value = BarMode.Expanded },
                        onEditingCancel = { mode.value = BarMode.Expanded },
                        onDragStart = {}, onDragDelta = {}, onDragEnd = { _, _ -> },
                        onReload = {}, showBottomToolbar = true, enableAddressHero = true,
                    )
                }
            }
        }
        compose.onNodeWithText("example.com").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("https://example.com").assertExists()
        compose.onNodeWithText("https://example.com").performTextReplacement("https://mozilla.org")
        compose.onNodeWithText("https://mozilla.org").assertExists()
        compose.onNodeWithContentDescription("Clear address").performClick()
        compose.onNodeWithText("Search or enter address").assertExists()
        compose.runOnIdle { mode.value = BarMode.Expanded }
        compose.waitForIdle()
        compose.onNodeWithText("example.com").assertExists()

        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.runOnIdle { mode.value = BarMode.Editing }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { mode.value = BarMode.Expanded }
            compose.mainClock.advanceTimeBy(80)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("example.com").assertExists()
    }
}
