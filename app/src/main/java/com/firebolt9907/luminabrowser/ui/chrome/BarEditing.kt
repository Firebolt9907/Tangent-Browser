package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme

@Composable
internal fun BarEditing(
    state: BarUiState,
    onEditingTextChange: (String) -> Unit,
    onEditingSubmit: () -> Unit,
    onEditingCancel: () -> Unit,
    modifier: Modifier = Modifier,
    controlsEnabled: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
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
        if (controlsEnabled) focusRequester.requestFocus()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onEditingSubmit() }),
                shape = RoundedCornerShape(24.dp),
            )
            TextButton(onClick = onEditingCancel, enabled = controlsEnabled) { Text("Cancel") }
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
