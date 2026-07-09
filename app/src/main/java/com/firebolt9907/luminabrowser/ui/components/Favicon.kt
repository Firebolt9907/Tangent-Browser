package com.firebolt9907.luminabrowser.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest

/**
 * A site favicon for list rows (bookmarks, history). Loads the origin's `/favicon.ico` and shows a
 * themed globe while loading or when the site has none, so every row has a consistent leading glyph
 * the way Safari's lists do. It lives inside LazyColumn items, so only visible rows fetch, and Coil
 * caches by URL — scrolling doesn't refetch. The favicon is fetched from the site itself (no
 * third-party favicon service), so it leaks nothing a normal page visit wouldn't.
 */
@Composable
fun Favicon(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val host = remember(url) {
        // Prefer the parsed authority; fall back to treating the string itself as a host so a bare
        // origin like "github.com" (no scheme, e.g. a saved-login origin) still resolves.
        runCatching { url.toUri().host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: url.trim()
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .takeIf { it.contains('.') && !it.contains(' ') }
    }
    if (host == null) {
        GlobeGlyph(size, modifier)
        return
    }
    val context = LocalContext.current
    val request = remember(host) {
        ImageRequest.Builder(context)
            .data("https://$host/favicon.ico")
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        if (painter.state is AsyncImagePainter.State.Success) {
            SubcomposeAsyncImageContent()
        } else {
            GlobeGlyph(size)
        }
    }
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
