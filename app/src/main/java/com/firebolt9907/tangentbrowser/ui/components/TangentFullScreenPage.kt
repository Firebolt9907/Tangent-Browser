package com.firebolt9907.tangentbrowser.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A full-window destination for management workflows, with navigation outside scrolling content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TangentFullScreenPage(
    title: String,
    onBack: () -> Unit,
    scrollable: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets(0.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxSize()
                            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        content = content,
                    )
                    overlay()
                }
            }
        }
    }
}
