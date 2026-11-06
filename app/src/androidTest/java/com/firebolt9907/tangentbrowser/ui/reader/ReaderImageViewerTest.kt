package com.firebolt9907.tangentbrowser.ui.reader

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import coil.ImageLoader
import coil.decode.SvgDecoder
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderImageViewerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val first = "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='1200' height='600'><rect width='1200' height='600' fill='teal'/></svg>"
    private val second = "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='600' height='1200'><rect width='600' height='1200' fill='orange'/></svg>"

    @Test fun pinchZoomGalleryAndClose() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) ReaderImageViewer(
                    images = listOf(ReaderGalleryImage(first, "Wide diagram"), ReaderGalleryImage(second, "Tall diagram")),
                    initialSrc = first,
                    sourceUrl = null,
                    imageLoader = remember { ImageLoader.Builder(compose.activity).components { add(SvgDecoder.Factory()) }.build() },
                    onOpenImage = {},
                    onDismiss = { visible.value = false },
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("Wide diagram") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Image loaded")).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithContentDescription("Wide diagram").performTouchInput {
            pinch(start0 = Offset(width * 0.4f, height / 2f), end0 = Offset(width * 0.2f, height / 2f),
                start1 = Offset(width * 0.6f, height / 2f), end1 = Offset(width * 0.8f, height / 2f))
        }
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithContentDescription("Zoom in").assertDoesNotExist()
        compose.onNodeWithContentDescription("Zoom out").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reset zoom to fit").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wide diagram").performTouchInput {
            pinch(start0 = Offset(width * 0.2f, height / 2f), end0 = Offset(width * 0.45f, height / 2f),
                start1 = Offset(width * 0.8f, height / 2f), end1 = Offset(width * 0.55f, height / 2f))
        }
        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithContentDescription("Previous image").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next image").performClick()
        compose.onNodeWithText("2 / 2").assertExists()
        compose.onNodeWithText("Tall diagram").assertExists()
        compose.onNodeWithContentDescription("Next image").assertIsNotEnabled()
        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithContentDescription("Close image viewer").performClick()
        compose.runOnIdle { assertTrue(!visible.value) }
    }
    @Test fun articlePinchReflowsText() {
        ReaderManager.instance.setFontSize(100)
        compose.setContent {
            val size by ReaderManager.instance.fontSize.collectAsState()
            MaterialTheme {
                ReaderView(
                    article = remember { ReaderArticle("Reader zoom test", null, listOf(ReaderBlock.Paragraph("Readable article content. ".repeat(30)))) },
                    theme = ReaderTheme.White,
                    fontSizePercent = size,
                    fontName = "System",
                )
            }
        }
        compose.onNodeWithText("Reader zoom test").performTouchInput {
            pinch(start0 = Offset(width * 0.4f, height / 2f), end0 = Offset(width * 0.2f, height / 2f),
                start1 = Offset(width * 0.6f, height / 2f), end1 = Offset(width * 0.8f, height / 2f))
        }
        compose.runOnIdle {
            assertTrue("Font size was ${ReaderManager.instance.fontSize.value}", ReaderManager.instance.fontSize.value > 100)
            assertTrue(ReaderManager.instance.fontSize.value <= 200)
            ReaderManager.instance.setFontSize(100)
        }
    }

}
