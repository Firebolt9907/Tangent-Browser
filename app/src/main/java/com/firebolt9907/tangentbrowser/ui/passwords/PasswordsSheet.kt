package com.firebolt9907.tangentbrowser.ui.passwords

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.components.SheetSearchField
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SwipeToRemoveRow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.theme.labelSmallEmphasized
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.SheetContentPhase
import com.firebolt9907.tangentbrowser.ui.components.SheetContentSwitch
import com.firebolt9907.tangentbrowser.ui.components.Favicon
import com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenController
import com.firebolt9907.tangentbrowser.ui.bitwarden.BitwardenConnectSection
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.LoginsStorage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordsSheet(
    loginsStorage: LoginsStorage,
    bitwardenController: BitwardenController,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberTangentHaptics()
    // Hold only non-secret metadata (origin, username, password length) for the list — never the
    // decrypted passwords themselves. list() briefly decrypts every record, but we map to metadata
    // and drop the Login objects immediately, so the plaintext passwords become GC-eligible right
    // after load instead of sitting in Compose memory the whole time the sheet is open. A single
    // password is decrypted on demand only when the user reveals or copies that one row (see
    // PasswordRow), and is never stashed in long-lived state. This bounds plaintext-in-memory to the
    // one secret the user is actively looking at.
    var entries by remember { mutableStateOf<List<LoginMeta>>(emptyList()) }
    // Copy/reveal feedback is a themed snackbar anchored inside the sheet, not a Toast: a Toast is
    // drawn outside the sheet, unthemed, and can't report the failure case at all (a decryption
    // failure used to look identical to a successful copy of nothing).
    val snackbarHostState = remember { SnackbarHostState() }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    // Re-read storage and project to metadata. The `List<Login>` from list() is not retained past
    // this map, so its decrypted passwords don't linger.
    // Guarded so a storage/decryption error can't crash this screen (or leave it loading forever).
    // The failure is remembered rather than swallowed: an unreadable vault used to render as
    // "No saved passwords", which is a different — and alarming — claim, with no way to retry.
    // On failure the last good list is kept; only a genuinely empty one reaches the error state.
    suspend fun refresh() {
        val result = runCatching {
            loginsStorage.list().map {
                LoginMeta(it.guid, it.origin, it.username, it.password.length, it.guid.startsWith("bw:"))
            }
        }
        loadFailed = result.isFailure
        result.getOrNull()?.let { entries = it }
        loading = false
    }

    fun retry() {
        loading = true
        scope.launch { refresh() }
    }

    fun notify(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    TangentModalSheet(onDismissRequest = onDismiss) {
        // The snackbar overlays the sheet's content rather than sitting after it in the column, so a
        // message doesn't shove the list and the Add button around as it comes and goes.
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetHeader(
                    title = "Passwords",
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                )

                Spacer(Modifier.height(12.dp))

                // Bitwarden as a second login source. Connecting/syncing refreshes the merged list
                // below.
                BitwardenConnectSection(
                    controller = bitwardenController,
                    onVaultChanged = { scope.launch { refresh() } },
                )

                Spacer(Modifier.height(12.dp))

                SheetSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search passwords...",
                )

                Spacer(Modifier.height(8.dp))

                val filtered = if (searchQuery.isBlank()) entries else entries.filter {
                    it.origin.contains(searchQuery, ignoreCase = true) ||
                        it.username.contains(searchQuery, ignoreCase = true)
                }

                // Spinner → empty/list is a cross-fade, not a swap: see SheetContentSwitch.
                val phase = when {
                    loading -> SheetContentPhase.Loading
                    filtered.isEmpty() -> SheetContentPhase.Empty
                    else -> SheetContentPhase.Content
                }
                SheetContentSwitch(phase, modifier = Modifier.fillMaxWidth()) { current ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        when (current) {
                            SheetContentPhase.Loading -> CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(vertical = 24.dp),
                            )

                            SheetContentPhase.Empty -> if (loadFailed) {
                                EmptyState(
                                    icon = Icons.Default.ErrorOutline,
                                    title = "Couldn't load passwords",
                                    description = "Something went wrong unlocking your saved logins.",
                                    action = {
                                        Button(onClick = { retry() }) { Text("Try again") }
                                    },
                                )
                            } else if (searchQuery.isNotBlank()) {
                                EmptyState(
                                    icon = Icons.Default.Search,
                                    title = "No matching passwords",
                                    description = "No saved logins match \"$searchQuery\".",
                                )
                            } else {
                                EmptyState(
                                    icon = Icons.Default.Lock,
                                    title = "No saved passwords",
                                    description = "Logins you save will appear here. Add one with " +
                                        "the button below.",
                                )
                            }

                            // Capped, not fixed: the Add button below must stay reachable when the
                            // list is long, but a short list should shrink to fit rather than
                            // reserve 320dp of empty space above it.
                            SheetContentPhase.Content -> LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                            ) {
                                items(filtered, key = { it.guid }) { meta ->
                                    // Swipe end-to-start to delete — opens the confirm dialog
                                    // (sensitive data, so it's confirmed, not immediate); the row
                                    // springs back until confirmed. Keeps only reveal + copy on the
                                    // row instead of a third trash icon.
                                    SwipeToRemoveRow(
                                        onRemove = { showDeleteConfirm = meta.guid },
                                        settleOnRemove = false,
                                    ) {
                                        PasswordRow(
                                            meta = meta,
                                            // On-demand, transient decryption: fetch this one
                                            // record's password only when the row needs it
                                            // (reveal/copy), never for the whole list.
                                            fetchPassword = {
                                                runCatching {
                                                    loginsStorage.get(meta.guid)?.password
                                                }.getOrNull()
                                            },
                                            onNotify = { message ->
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        message = message,
                                                        duration = SnackbarDuration.Short,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        if (current != SheetContentPhase.Loading) {
                            Spacer(Modifier.height(8.dp))

                            // The sheet's one primary action, so it's a filled Button rather than
                            // an outlined one, full-width and bottom-anchored — the easiest place
                            // to reach one-handed.
                            Button(
                                onClick = { haptics.softClick(); showAddDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add Password")
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showAddDialog) {
        AddPasswordDialog(
            onDismiss = { showAddDialog = false },
            onSave = { entry ->
                scope.launch {
                    // Don't let an add failure (e.g. invalid record) crash the screen; refresh
                    // the list afterward so the UI reflects whatever actually persisted, and say
                    // so when nothing did — a rejected record otherwise just never appears.
                    val saved = runCatching { loginsStorage.add(entry) }
                    refresh()
                    if (saved.isFailure) notify("Couldn't save that password")
                }
                showAddDialog = false
            },
        )
    }

    showDeleteConfirm?.let { guid ->
        val entry = entries.find { it.guid == guid }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete password?") },
            text = {
                Text("Remove saved password for ${entry?.origin ?: "this site"}?")
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.remove()
                    scope.launch {
                        val deleted = runCatching { loginsStorage.delete(guid) }
                        refresh()
                        if (deleted.isFailure) notify("Couldn't delete that password")
                    }
                    showDeleteConfirm = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Non-secret projection of a stored login shown in the list. Deliberately does NOT carry the
 * password — only its length, so the row can render the right number of masking dots without holding
 * the plaintext. The real password is fetched on demand via [PasswordRow]'s `fetchPassword`.
 */
private data class LoginMeta(
    val guid: String,
    val origin: String,
    val username: String,
    val passwordLength: Int,
    val isBitwarden: Boolean = false,
)

@Composable
private fun PasswordRow(
    meta: LoginMeta,
    fetchPassword: suspend () -> String?,
    onNotify: (String) -> Unit,
) {
    // The one place a plaintext password can live in this screen: only while the user is actively
    // revealing THIS row, and cleared the moment they hide it or the row leaves composition. The
    // list itself never holds decrypted passwords.
    var revealed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = rememberTangentHaptics()

    ListItem(
        leadingContent = { Favicon(meta.origin) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = meta.origin.takeLastWhile { it != '/' }
                        .ifEmpty { meta.origin }
                        .removePrefix("www."),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (meta.isBitwarden) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Bitwarden",
                        style = MaterialTheme.typography.labelSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = meta.username.ifEmpty { "(no username)" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = revealed ?: "•".repeat(meta.passwordLength.coerceAtMost(20)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row {
                // Reveal is a two-state control, so it's an IconToggleButton: TalkBack announces it
                // as checked/unchecked and it picks up M3's checked state layer, neither of which a
                // plain IconButton swapping its own icon can offer.
                IconToggleButton(
                    checked = revealed != null,
                    onCheckedChange = { wantVisible ->
                        haptics.toggle(wantVisible)
                        if (!wantVisible) {
                            revealed = null
                        } else {
                            scope.launch {
                                val password = fetchPassword()
                                if (password != null) revealed = password
                                else onNotify("Couldn't unlock that password")
                            }
                        }
                    },
                ) {
                    Icon(
                        if (revealed != null) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Show password",
                    )
                }
                IconButton(onClick = {
                    // Decrypt transiently just for the copy; don't retain it in state.
                    scope.launch {
                        val password = fetchPassword()
                        if (password != null) {
                            copyPasswordToClipboard(context, password)
                            haptics.softClick()
                            onNotify("Password copied")
                        } else {
                            onNotify("Couldn't unlock that password")
                        }
                    }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy password")
                }
            }
        },
    )
}

/**
 * Copy a password to the clipboard, flagging the clip as sensitive (Android 13+) so the OS redacts
 * it from the clipboard preview and excludes it from clipboard history — important for a password
 * manager. Uses the platform clipboard because Compose's ClipboardManager can't set the flag.
 */
private fun copyPasswordToClipboard(context: Context, password: String) {
    val clip = ClipData.newPlainText("password", password)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    manager?.setPrimaryClip(clip)
}

@Composable
private fun AddPasswordDialog(
    onDismiss: () -> Unit,
    onSave: (mozilla.components.concept.storage.LoginEntry) -> Unit,
) {
    var origin by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    // Three fields deep in a dialog: focus the first one so the keyboard is already up, rather than
    // costing a tap before anything can be typed.
    val originFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { originFocus.requestFocus() }

    val canSave = origin.isNotBlank() && password.isNotBlank()
    val save = {
        val normalizedOrigin = if (origin.startsWith("http://") || origin.startsWith("https://")) {
            origin
        } else {
            "https://$origin"
        }
        onSave(
            mozilla.components.concept.storage.LoginEntry(
                origin = normalizedOrigin,
                formActionOrigin = normalizedOrigin,
                httpRealm = "",
                usernameField = "",
                passwordField = "",
                username = username,
                password = password,
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Password") },
        text = {
            // Scrollable so three text fields plus their labels stay reachable at large font scales
            // once the IME has eaten half the dialog.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = origin,
                    onValueChange = { origin = it },
                    label = { Text("Site URL") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(originFocus),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconToggleButton(
                            checked = showPassword,
                            onCheckedChange = { showPassword = it },
                        ) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Show password",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = save,
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
