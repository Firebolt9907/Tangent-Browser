package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The page-level feedback channel: transient confirmations for actions taken *on the current page*
 * (copy a link, save an image, run OCR over one) rather than inside a sheet.
 *
 * These used to be `android.widget.Toast`s, which are unthemed, drawn in their own window outside
 * the app's surfaces, can't carry a retry, and on modern Android are frequently suppressed
 * altogether while a modal sheet is up — so the confirmation for an action taken *from* a sheet
 * could silently never appear. This is the M3 answer: one [SnackbarHostState] living above the
 * pill, themed with the rest of the app.
 *
 * Distinct from the Now-Bar banner stack ([rememberBannerStackState]), which is for messages the
 * user is expected to *answer* (a permission request, an incoming tab). Feedback here states what
 * already happened and gets out of the way.
 */
@Stable
internal class PageFeedback(private val scope: CoroutineScope) {
    val hostState = SnackbarHostState()

    /**
     * Show [message], replacing anything currently on screen.
     *
     * The replacement is the point: several of these actions narrate themselves in two beats
     * ("Copying image…" then "Image copied"), and [SnackbarHostState.showSnackbar] *queues* rather
     * than replaces — so without the dismiss the result would wait out the whole progress message.
     */
    fun show(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        hostState.currentSnackbarData?.dismiss()
        scope.launch {
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

@Composable
internal fun rememberPageFeedback(scope: CoroutineScope): PageFeedback =
    remember(scope) { PageFeedback(scope) }

/**
 * Draws [feedback] above the bottom chrome. [bottomPadding] should be the pill/toolbar inset, so a
 * message never lands under the bar it was triggered from.
 */
@Composable
internal fun PageFeedbackHost(feedback: PageFeedback, bottomPadding: Dp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(
            hostState = feedback.hostState,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = bottomPadding + 8.dp),
        )
    }
}
