package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
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
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.editingText,
                selection = TextRange(0, state.editingText.length),
            ),
        )
    }
    fun selectAllText() {
        fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
    }

    LaunchedEffect(state.editingText) {
        if (state.editingText != fieldValue.text) {
            fieldValue = TextFieldValue(
                text = state.editingText,
                selection = TextRange(0, state.editingText.length),
            )
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(28.dp),
        // A distinctly lighter fill than the address sheet (`surface`) behind it, so the input reads
        // as its own raised pill floating above the suggestions rather than blending into the sheet.
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { value ->
                    fieldValue = value
                    onEditingTextChange(value.text)
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
                trailingIcon = if (fieldValue.text.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = {
                                haptics?.remove()
                                fieldValue = TextFieldValue("")
                                onEditingTextChange("")
                                focusRequester.requestFocus()
                            },
                            enabled = controlsEnabled,
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear address")
                        }
                    }
                } else null,
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
            TextButton(onClick = { haptics?.softClick(); onEditingCancel() }, enabled = controlsEnabled) { Text("Cancel") }
        }
    }
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
