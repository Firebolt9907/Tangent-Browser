package com.firebolt9907.tangentbrowser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.graphics.Color
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.firebolt9907.tangentbrowser.ui.theme.titleLargeEmphasized
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks for the bottom sheets so every surface (Settings, Bookmarks, History,
 * Downloads, Passwords, Sync, Add-ons) reads with the same header, section labels, and grouped
 * cards. Presentation only — behavior stays in each sheet.
 */

/**
 * The modal container every content sheet opens in: `skipPartiallyExpanded` so the first upward
 * drag scrolls the inner list instead of expanding a half-detent (a nested-scroll bug each sheet
 * used to fix separately), zero window insets, and the standard padded content column.
 * [scrollable] adds a vertical scroll for static-content sheets (Settings); list sheets bring
 * their own LazyColumn. Behavior stays in each sheet — this is just the shared shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TangentModalSheet(
    onDismissRequest: () -> Unit,
    bottomPadding: Dp = 28.dp,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismissRequest,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 20.dp)
                .padding(bottom = bottomPadding),
            content = content,
        )
    }
}

/**
 * Consistent sheet title row: an optional leading icon, an emphasized title, and an optional trailing
 * action (e.g. a "Clear all" button or a busy spinner). Every sheet's header goes through this so the
 * icon spacing, title weight, and trailing alignment stay identical across surfaces.
 */
@Composable
fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(10.dp))
        }
        // M3 Expressive's emphasized cut of titleLarge (Medium, not the base Regular) — a sheet
        // title is exactly the "assert the hierarchy" case the emphasized styles exist for. This
        // was previously an ad-hoc iOS-18 large-title Bold, a heavier weight than the spec's.
        // Weighted so the trailing content sits flush right (and a long title ellipsizes rather
        // than shoving it off).
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/**
 * The in-sheet search box (Bookmarks, History, Passwords). Autocorrect and auto-capitalization are
 * off — these are filter queries, not prose — and a clear button appears once there's input. The
 * keyboard shows a "Search" action that just dismisses the keyboard (filtering is live as you type),
 * so the user can reveal the results the keyboard was covering.
 */
@Composable
fun SheetSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

/**
 * Wraps a list row so it can be removed with a horizontal swipe (end-to-start), the iOS/Safari
 * convention — replacing the persistent trailing trash-can [IconButton] that otherwise sits on
 * every single row and clutters the list. Reveals a red delete affordance as the row is dragged
 * and fires [onRemove] (with a haptic) once the swipe crosses the threshold. Swipe-back
 * (start-to-end) is disabled so a stray drag can't trigger some other action.
 *
 * [content] should be the row itself (typically a [ListItem]); give it an opaque background so the
 * red delete layer only shows while swiping. With [settleOnRemove] true (the default) the caller
 * must drop the item from its data on [onRemove] — once it leaves composition the settled dismiss
 * state goes with it. Set [settleOnRemove] false when the swipe should only *trigger* removal
 * (e.g. open a confirmation dialog): [onRemove] still fires, but the row springs back so it isn't
 * left blank if the user cancels — the dialog is then responsible for the actual delete + reload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToRemoveRow(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    settleOnRemove: Boolean = true,
    content: @Composable () -> Unit,
) {
    val haptics = rememberTangentHaptics()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                haptics.remove()
                onRemove()
                settleOnRemove
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val swiping = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (swiping) MaterialTheme.colorScheme.errorContainer
                        else Color.Transparent,
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (swiping) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
        content = { content() },
    )
}

/** A small muted section label used above a group of related controls. */
@Composable
fun SheetSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A rounded surface card that groups related rows (Material 3 grouped-list style). */
@Composable
fun SheetCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        content = content,
    )
}

/**
 * A hairline separator between rows inside a [SheetCard]. Inset to the card's content padding so it
 * reads as a grouped-list divider (the iOS / Material-3 settings convention) rather than a full-bleed
 * rule. Place between rows, not before the first or after the last.
 */
@Composable
fun SheetRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}
