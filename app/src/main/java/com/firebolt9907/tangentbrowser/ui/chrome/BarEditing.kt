package com.firebolt9907.tangentbrowser.ui.chrome

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.state.BarMode
import com.firebolt9907.tangentbrowser.state.BarUiState
import com.firebolt9907.tangentbrowser.ui.TangentHaptics
import com.firebolt9907.tangentbrowser.ui.theme.TangentBrowserTheme
import kotlinx.coroutines.delay

@Composable
internal fun BarEditing(
    state: BarUiState,
    onEditingTextChange: (String) -> Unit,
    onEditingSubmit: () -> Unit,
    onEditingCancel: () -> Unit,
    modifier: Modifier = Modifier,
    controlsEnabled: Boolean = true,
    // Applied to the editing field so it pairs with BarExpanded's address region as a shared
    // element (supplied by SafariBar's SharedTransitionLayout). Default no-op for previews.
    addressSharedModifier: Modifier = Modifier,
    haptics: TangentHaptics? = null,
    // Mic tap → system voice search. Null hides the mic (no recognizer installed).
    onVoiceInput: (() -> Unit)? = null,
    // Top URL suggestion. When its display form starts with what the user has typed, the remainder
    // is inline-completed into the field as a pre-selected suffix (desktop-browser autocomplete):
    // continuing to type overtypes it, Backspace rejects it, Enter accepts it.
    inlineAutocomplete: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // `userText` is what the user actually typed; `fieldValue` is what's shown, which may carry a
    // selected autocomplete suffix beyond `userText`. `suppressAutocomplete` guards the beat after a
    // deletion so a backspace that rejects the completion isn't immediately re-completed.
    var userText by remember { mutableStateOf(state.editingText) }
    var suppressAutocomplete by remember { mutableStateOf(true) }
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.editingText,
                selection = TextRange(0, state.editingText.length),
            ),
        )
    }
    fun showValue(user: String, suffix: String?) {
        fieldValue = if (suffix.isNullOrEmpty()) {
            TextFieldValue(user, TextRange(user.length))
        } else {
            val full = user + suffix
            TextFieldValue(full, TextRange(user.length, full.length))
        }
    }
    fun selectAllText() {
        fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
    }

    // External edits to the field text (entering editing on a URL, the Clear button, a committed
    // suggestion) reset the field and select-all; don't auto-complete over that until the next
    // keystroke re-enables it.
    LaunchedEffect(state.editingText) {
        if (state.editingText != userText) {
            userText = state.editingText
            suppressAutocomplete = true
            fieldValue = TextFieldValue(
                text = state.editingText,
                selection = TextRange(0, state.editingText.length),
            )
        }
    }
    // When a fresh top suggestion arrives after a keystroke, apply (or retract) the inline
    // completion — but only when the field currently shows exactly the user's text (no active
    // manual selection to stomp) and the last edit wasn't a deletion.
    LaunchedEffect(inlineAutocomplete) {
        if (suppressAutocomplete || userText.isEmpty()) return@LaunchedEffect
        if (fieldValue.text == userText && fieldValue.selection.collapsed) {
            autocompleteSuffix(userText, inlineAutocomplete)?.let { showValue(userText, it) }
        }
    }
    LaunchedEffect(controlsEnabled) {
        if (controlsEnabled) {
            focusRequester.requestFocus()
            // Re-assert after the bar's enter transition settles. When editing is entered
            // programmatically mid-animation (e.g. a new tab from an edge swipe, where
            // `controlsEnabled` never toggles), the first request can be dropped during the
            // `AnimatedContent` transition and the keyboard never opens.
            delay(60)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // The input capsule spans the full bar width. Cancel is NOT here — it floats over the
    // suggestions sheet (AddressSheet), bottom-right, so the list can show to its left rather than a
    // dedicated Cancel row eating horizontal space. The in-capsule Clear (✕) still means "empty the
    // field" (distinct from the sheet's Cancel = "leave editing").
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            // A distinctly lighter fill than the address sheet (`surface`) behind it, so the input
            // reads as its own raised pill floating above the suggestions rather than blending in.
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                // Force the capsule's height here rather than on the field: the field carries the
                // shared-element modifier whose morphing bounds override its own min-height, so the
                // pill height must be pinned on this container instead.
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                // Borderless + transparent fill so the field reads as one pill with the Surface
                // behind it (iOS-style), rather than an outlined box nested inside a rounded card.
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                )
                // NB: this is a BasicTextField wrapped in the OutlinedTextField decoration (not
                // `OutlinedTextField` itself) purely so we can control `contentPadding`. The stock
                // OutlinedTextField hard-codes a 16.dp horizontal inset, and dragging the caret into
                // that trailing inset never reaches the scroll viewport's edge — so Android's
                // horizontal auto-scroll never fires (the same failure mode a `trailingIcon` causes,
                // which is why the Clear button is a Row sibling below, not a decoration). Shrinking
                // `end` to 4.dp lets the text viewport run to the field's own right edge, so a caret
                // drag there scrolls the content like every other Android text field.
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { value ->
                        // Derive the user-typed text from the edit. Typing over the selected
                        // completion collapses the selection, so `value.text` already IS the new
                        // user text. A forward-growing edit re-enables autocomplete; anything else
                        // (backspace, mid-string edit, select-all replace) shows the raw edit and
                        // suppresses completion for this beat.
                        val newText = value.text
                        val grewAtEnd = newText.length > userText.length && newText.startsWith(userText)
                        userText = newText
                        onEditingTextChange(newText)
                        if (grewAtEnd) {
                            // The previous suggestion belongs to the previous query. Wait for a
                            // fresh result before offering its suffix again.
                            suppressAutocomplete = false
                            showValue(newText, null)
                        } else {
                            suppressAutocomplete = true
                            fieldValue = value
                        }
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier
                        .weight(1f)
                        // Shared-element counterpart to BarExpanded's address region — its bounds
                        // morph from the pill's URL label as the editing modal opens/closes.
                        .then(addressSharedModifier)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) selectAllText()
                        }
                        .pointerInput(controlsEnabled) {
                            if (controlsEnabled) {
                                detectTapGestures(onTap = { selectAllText() })
                            }
                        }
                        // The stock OutlinedTextField enforces a 56.dp min height as its INNERMOST
                        // modifier; the raw BasicTextField + DecorationBox does not. Must come last
                        // (after the shared-element modifier, whose morphing bounds would otherwise
                        // squash the capsule to the short URL-label height it animates from).
                        .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onEditingSubmit() }),
                    interactionSource = interactionSource,
                    decorationBox = { innerTextField ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = fieldValue.text,
                            innerTextField = innerTextField,
                            enabled = controlsEnabled,
                            singleLine = true,
                            visualTransformation = VisualTransformation.None,
                            interactionSource = interactionSource,
                            placeholder = { Text("Search or enter address") },
                            colors = fieldColors,
                            // Trim the trailing inset so the caret can reach the viewport edge and
                            // auto-scroll (see note above); keep the leading inset for breathing room.
                            contentPadding = OutlinedTextFieldDefaults.contentPadding(
                                start = 12.dp,
                                end = 4.dp,
                            ),
                        )
                    },
                )
                if (fieldValue.text.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            haptics?.remove()
                            userText = ""
                            suppressAutocomplete = true
                            fieldValue = TextFieldValue("")
                            onEditingTextChange("")
                            focusRequester.requestFocus()
                        },
                        enabled = controlsEnabled,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear address",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else if (onVoiceInput != null) {
                    // Empty field: offer voice search in the slot the Clear (✕) button would occupy.
                    IconButton(
                        onClick = { haptics?.softClick(); onVoiceInput() },
                        enabled = controlsEnabled,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Voice search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The inline-autocomplete completion for [typed] toward [source], or null when [source] doesn't
 * continue what the user typed. Matches against progressively friendlier display forms of the URL
 * (full, scheme-stripped, www-stripped) so typing "goog" completes "https://www.google.com" to the
 * visible "google.com". Case-insensitive match; the returned suffix preserves the source's own case.
 */
private fun autocompleteSuffix(typed: String, source: String?): String? {
    if (source.isNullOrEmpty() || typed.isEmpty()) return null
    val noScheme = source.substringAfter("://", source)
    val candidates = listOf(source, noScheme, noScheme.removePrefix("www."))
    for (candidate in candidates) {
        if (candidate.length > typed.length && candidate.startsWith(typed, ignoreCase = true)) {
            return candidate.substring(typed.length)
        }
    }
    return null
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BarEditingPreview() {
    TangentBrowserTheme(darkTheme = true) {
        BarEditing(
            state = BarUiState("apple.com", 4, BarMode.Editing, "apple.com"),
            onEditingTextChange = {},
            onEditingSubmit = {},
            onEditingCancel = {},
        )
    }
}
