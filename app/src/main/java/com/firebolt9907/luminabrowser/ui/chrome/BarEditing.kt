package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState
import com.firebolt9907.luminabrowser.ui.LuminaHaptics
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme
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
    haptics: LuminaHaptics? = null,
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

    // Cancel lives OUTSIDE the input capsule (iOS-Safari style) so it reads unambiguously as
    // "leave editing", while the in-capsule Clear (✕) reads as "empty the field". Keeping them in
    // separate containers — rather than side-by-side inside one pill — is the clear distinction.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            // A distinctly lighter fill than the address sheet (`surface`) behind it, so the input
            // reads as its own raised pill floating above the suggestions rather than blending in.
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
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
                            suppressAutocomplete = false
                            showValue(newText, autocompleteSuffix(newText, inlineAutocomplete))
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
                        },
                    singleLine = true,
                    placeholder = { Text("Search or enter address") },
                    // NB: the Clear button is deliberately a Row sibling, NOT `trailingIcon`. As a
                    // decoration it eats the field's right inset, and dragging the caret into that
                    // inset never reaches the text viewport's edge — so horizontal auto-scroll never
                    // fires. As a sibling the text viewport runs to the field's own right edge and a
                    // caret drag there scrolls the content.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onEditingSubmit() }),
                    shape = RoundedCornerShape(24.dp),
                    // Borderless + transparent fill so the field reads as one pill with the Surface
                    // behind it (iOS-style), rather than an outlined box nested inside a rounded card.
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
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
        TextButton(onClick = { haptics?.softClick(); onEditingCancel() }, enabled = controlsEnabled) { Text("Cancel") }
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
    LuminaBrowserTheme(darkTheme = true) {
        BarEditing(
            state = BarUiState("apple.com", 4, BarMode.Editing, "apple.com"),
            onEditingTextChange = {},
            onEditingSubmit = {},
            onEditingCancel = {},
        )
    }
}
