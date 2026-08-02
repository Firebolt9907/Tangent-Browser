package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.firebolt9907.tangentbrowser.ui.theme.headlineSmallEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.titleMediumEmphasized
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.state.compactHost
import mozilla.components.concept.storage.Login
import kotlin.math.abs

/**
 * The "pick a saved login to fill" sheet shown for [mozilla.components.concept.engine.prompt.PromptRequest.SelectLoginPrompt].
 * A Material 3 modal bottom sheet: each stored credential as a card with a color-keyed letter tile,
 * the username, the site host, and a masked password. Tapping a row runs the biometric gate (in
 * [PromptHandler]) before the credential is filled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectLoginSheet(
    logins: List<Login>,
    onSelect: (Login) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Fill password",
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Unlock to fill your saved credentials",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(logins, key = { it.guid }) { login ->
                    LoginRow(login = login, onClick = { onSelect(login) })
                }
            }
        }
    }
}

@Composable
private fun LoginRow(login: Login, onClick: () -> Unit) {
    val host = compactHost(login.origin).ifBlank { login.origin }
    val primary = login.username.ifBlank { host }.ifBlank { "Saved login" }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LetterTile(label = primary)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (login.username.isNotBlank() && host.isNotBlank()) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "•".repeat(login.password.length.coerceIn(6, 12)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun LetterTile(label: String) {
    val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    val hue = (abs(label.hashCode()) % 360).toFloat()
    val tile = Color.hsl(hue, 0.45f, 0.55f)
    val onTile = if (tile.luminance() > 0.5f) Color.Black else Color.White
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tile),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = onTile,
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
    }
}

/**
 * `javascript:` payload injected into the page shortly after a login is filled, to press the form's
 * submit control — the "click login after autofill" behavior.
 *
 * Conservative on purpose: it only acts on a form that actually contains a filled password field, so
 * it never submits a stray search box. It prefers an explicit submit control inside that form, then
 * falls back to `form.requestSubmit()`. Runs a couple of delayed attempts because Gecko fills the
 * fields asynchronously after the prompt is confirmed.
 */
internal fun buildLoginSubmitScript(): String {
    val js = """
        (function(){
          function trySubmit(){
            var pw = document.querySelector('input[type=password]');
            if(!pw || !pw.value) return false;
            var form = pw.form;
            if(!form) return false;
            var btn = form.querySelector('button[type=submit], input[type=submit], button:not([type])');
            if(!btn){
              var cands = form.querySelectorAll('button, input[type=button], [role=button]');
              for(var i=0;i<cands.length;i++){
                var t=(cands[i].innerText||cands[i].value||'').toLowerCase();
                if(/log\s?in|sign\s?in|continue|submit|next/.test(t)){ btn=cands[i]; break; }
              }
            }
            if(btn){ btn.click(); return true; }
            if(form.requestSubmit){ form.requestSubmit(); return true; }
            form.submit();
            return true;
          }
          var tries=0;
          var iv=setInterval(function(){
            tries++;
            if(trySubmit() || tries>6){ clearInterval(iv); }
          }, 250);
        })();
    """.trimIndent().replace("\n", "")
    return "javascript:$js"
}
