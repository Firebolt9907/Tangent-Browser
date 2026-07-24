package com.firebolt9907.tangentbrowser.ui.passwords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.Favicon
import com.firebolt9907.tangentbrowser.sync.PlacesLoginsStorage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordsSheet(
    loginsStorage: PlacesLoginsStorage,
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
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    // Re-read storage and project to metadata. The `List<Login>` from list() is not retained past
    // this map, so its decrypted passwords don't linger.
    suspend fun reload(): List<LoginMeta> =
        runCatching { loginsStorage.list().map { LoginMeta(it.guid, it.origin, it.username, it.password.length) } }
            .getOrDefault(emptyList())

    LaunchedEffect(Unit) {
        // Guard against storage/decryption errors so a read failure shows an empty list rather
        // than crashing this screen (and never clearing the loading state).
        entries = reload()
        loading = false
    }

    TangentModalSheet(onDismissRequest = onDismiss) {
        SheetHeader(
            title = "Passwords",
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        )

        Spacer(Modifier.height(12.dp))

        SheetSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search passwords...",
        )

        Spacer(Modifier.height(8.dp))

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 24.dp),
            )
        } else {
            val filtered = if (searchQuery.isBlank()) entries else entries.filter {
                it.origin.contains(searchQuery, ignoreCase = true) ||
                    it.username.contains(searchQuery, ignoreCase = true)
            }

            if (filtered.isEmpty()) {
                if (searchQuery.isNotBlank()) {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "No matching passwords",
                        description = "No saved logins match \"$searchQuery\".",
                    )
                } else {
                    EmptyState(
                        icon = Icons.Default.Lock,
                        title = "No saved passwords",
                        description = "Logins you save will appear here. Add one with the button below.",
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(filtered, key = { it.guid }) { meta ->
                        // Swipe end-to-start to delete — opens the confirm dialog (sensitive data,
                        // so it's confirmed, not immediate); the row springs back until confirmed.
                        // Keeps only reveal + copy on the row instead of a third trash icon.
                        SwipeToRemoveRow(
                            onRemove = { showDeleteConfirm = meta.guid },
                            settleOnRemove = false,
                        ) {
                            PasswordRow(
                                meta = meta,
                                // On-demand, transient decryption: fetch this one record's password
                                // only when the row needs it (reveal/copy), never for the whole list.
                                fetchPassword = { runCatching { loginsStorage.get(meta.guid)?.password }.getOrNull() },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Add Password")
                }
            }
        }
    }

    if (showAddDialog) {
        AddPasswordDialog(
            onDismiss = { showAddDialog = false },
            onSave = { entry ->
                scope.launch {
                    // Don't let an add failure (e.g. invalid record) crash the screen; refresh
                    // the list afterward so the UI reflects whatever actually persisted.
                    runCatching { loginsStorage.add(entry) }
                    entries = reload()
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
                        runCatching { loginsStorage.delete(guid) }
                        entries = reload()
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
)

@Composable
private fun PasswordRow(
    meta: LoginMeta,
    fetchPassword: suspend () -> String?,
) {
    // The one place a plaintext password can live in this screen: only while the user is actively
    // revealing THIS row, and cleared the moment they hide it or the row leaves composition. The
    // list itself never holds decrypted passwords.
    var revealed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    ListItem(
        leadingContent = { Favicon(meta.origin) },
        headlineContent = {
            Text(
                text = meta.origin.takeLastWhile { it != '/' }
                    .ifEmpty { meta.origin }
                    .removePrefix("www."),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                IconButton(onClick = {
                    if (revealed != null) {
                        revealed = null
                    } else {
                        scope.launch { revealed = fetchPassword() }
                    }
                }) {
                    Icon(
                        if (revealed != null) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (revealed != null) "Hide" else "Show",
                    )
                }
                IconButton(onClick = {
                    // Decrypt transiently just for the copy; don't retain it in state.
                    scope.launch {
                        val password = fetchPassword()
                        if (password != null) {
                            copyPasswordToClipboard(context, password)
                            Toast.makeText(context, "Password copied", Toast.LENGTH_SHORT).show()
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
            Column {
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
                    modifier = Modifier.fillMaxWidth(),
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
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
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
