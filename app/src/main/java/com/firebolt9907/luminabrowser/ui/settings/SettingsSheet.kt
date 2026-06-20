package com.firebolt9907.luminabrowser.ui.settings

import android.widget.Toast
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    globalPrefsStore: GlobalPreferencesStore,
    onDismiss: () -> Unit,
    onSettingChanged: () -> Unit,
    onClearSitePermissions: () -> Unit = {},
    bottomInsetDp: Dp = 0.dp,
) {
    var trackingProtection by remember { mutableStateOf(globalPrefsStore.trackingProtection) }
    var blockPopUps by remember { mutableStateOf(globalPrefsStore.blockPopUps) }
    var javaScriptEnabled by remember { mutableStateOf(globalPrefsStore.javaScriptEnabled) }
    var httpsOnly by remember { mutableStateOf(globalPrefsStore.httpsOnly) }
    var globalPrivacyControl by remember { mutableStateOf(globalPrefsStore.globalPrivacyControl) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(bottom = bottomInsetDp) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Privacy & Security",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            SettingsToggle(
                title = "Enhanced Tracking Protection",
                subtitle = "Block trackers, fingerprinters, and cryptominers",
                checked = trackingProtection,
                onCheckedChange = {
                    trackingProtection = it
                    globalPrefsStore.trackingProtection = it
                    onSettingChanged()
                },
            )

            SettingsToggle(
                title = "Global Privacy Control",
                subtitle = "Tell sites not to sell or share your data",
                checked = globalPrivacyControl,
                onCheckedChange = {
                    globalPrivacyControl = it
                    globalPrefsStore.globalPrivacyControl = it
                    onSettingChanged()
                },
            )

            SettingsToggle(
                title = "HTTPS-Only Mode",
                subtitle = "Require secure connections for all sites",
                checked = httpsOnly,
                onCheckedChange = {
                    httpsOnly = it
                    globalPrefsStore.httpsOnly = it
                    onSettingChanged()
                },
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Content",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            SettingsToggle(
                title = "Block Pop-ups",
                subtitle = "Prevent sites from opening new windows",
                checked = blockPopUps,
                onCheckedChange = {
                    blockPopUps = it
                    globalPrefsStore.blockPopUps = it
                    onSettingChanged()
                },
            )

            SettingsToggle(
                title = "JavaScript",
                subtitle = "Enable JavaScript on web pages",
                checked = javaScriptEnabled,
                onCheckedChange = {
                    javaScriptEnabled = it
                    globalPrefsStore.javaScriptEnabled = it
                    onSettingChanged()
                },
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Site permissions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val context = LocalContext.current
            TextButton(
                onClick = {
                    onClearSitePermissions()
                    Toast.makeText(
                        context,
                        "Cleared remembered site permissions",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        text = "Clear remembered site permissions",
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()
            }
            if (versionName != null) {
                Text(
                    text = "Lumina Browser $versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Row handles the toggle (and a11y); switch is visual-only to avoid double-toggle.
        Switch(checked = checked, onCheckedChange = null)
    }
}
