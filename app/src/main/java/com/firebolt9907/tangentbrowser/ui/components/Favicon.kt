package com.firebolt9907.tangentbrowser.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.TangentApplication
import mozilla.components.browser.icons.Icon as MozIcon
import mozilla.components.browser.icons.IconRequest

/**
 * A site favicon for list rows (bookmarks, history, synced tabs, passwords, address suggestions).
 * Resolves the icon through mozilla [mozilla.components.browser.icons.BrowserIcons] — the same
 * engine the tab overview uses — which parses the site's declared `<link rel=icon>` / web-manifest
 * icons and a bundled known-site list, not just `/favicon.ico`, so far more rows get a real glyph.
 * Icons are fetched from the site itself (no third-party favicon service, leaks nothing a normal
 * page visit wouldn't) and are memory+disk cached, so scrolling and revisits don't refetch. When the
 * site has no real icon (BrowserIcons would only *generate* a placeholder) or the URL can't resolve,
 * we show a themed globe instead, so every row keeps a consistent leading glyph the way Safari does.
 */
@Composable
fun Favicon(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val requestUrl = remember(url) { faviconRequestUrl(url) }
    if (requestUrl == null) {
        GlobeGlyph(size, modifier)
        return
    }
    val context = LocalContext.current
    val components = remember(context) {
        (context.applicationContext as TangentApplication).components
    }
    // null while loading or when there's no real site icon → globe fallback below.
    val bitmap by produceState<Bitmap?>(initialValue = null, requestUrl, components) {
        value = runCatching {
            components.icons.loadIcon(IconRequest(requestUrl)).await()
        }.getOrNull()
            ?.takeIf { it.source != MozIcon.Source.GENERATOR }
            ?.bitmap
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp)),
        )
    } else {
        GlobeGlyph(size, modifier)
    }
}

/**
 * Turn a row's URL (or bare origin) into a URL BrowserIcons can resolve. Login origins are often a
 * bare host (`github.com`, no scheme) — assume https so they still resolve; reject anything without
 * a dotted host so `about:blank` and search terms fall through to the globe.
 */
private fun faviconRequestUrl(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.contains("://")) return trimmed
    val host = trimmed.substringBefore('/').substringBefore('?')
    return if (host.contains('.') && !host.contains(' ')) "https://$trimmed" else null
}

@Composable
private fun GlobeGlyph(size: Dp, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Outlined.Public,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}
