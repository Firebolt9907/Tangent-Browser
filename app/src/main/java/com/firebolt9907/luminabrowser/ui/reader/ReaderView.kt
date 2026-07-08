package com.firebolt9907.luminabrowser.ui.reader

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.shadow

@Composable
fun ReaderView(
    article: ReaderArticle,
    theme: ReaderTheme,
    fontSizePercent: Int,
    fontName: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    /** Reports vertical scroll deltas (px): positive = scrolling down. Drives the pill auto-hide. */
    onScrollDelta: (Float) -> Unit = {},
    /**
     * Reports a rendered bitmap of the reader surface. The host stores it as this tab's thumbnail so
     * the tab grid and the tab-switch carousel show the reader content instead of the (invisible)
     * underlying web page, whose engine capture would be blank / stale while reader mode is on.
     */
    onSnapshot: (android.graphics.Bitmap) -> Unit = {},
    onLinkClick: (String) -> Unit = {},
) {
    val backgroundColor = when (theme) {
        ReaderTheme.System -> MaterialTheme.colorScheme.surface
        ReaderTheme.White -> Color(0xFFFFFFFF)
        ReaderTheme.Sepia -> Color(0xFFF4ECD8)
        ReaderTheme.Dark -> Color(0xFF1C1C1E)
    }

    val textColor = when (theme) {
        ReaderTheme.System -> MaterialTheme.colorScheme.onSurface
        ReaderTheme.White -> Color(0xFF1C1C1E)
        ReaderTheme.Sepia -> Color(0xFF3C301D)
        ReaderTheme.Dark -> Color(0xFFE5E5EA)
    }

    val linkColor = when (theme) {
        ReaderTheme.System -> MaterialTheme.colorScheme.primary
        ReaderTheme.White -> Color(0xFF0066CC)
        ReaderTheme.Sepia -> Color(0xFF1A5276)
        ReaderTheme.Dark -> Color(0xFF4DA3FF)
    }

    val mutedTextColor = when (theme) {
        ReaderTheme.System -> MaterialTheme.colorScheme.onSurfaceVariant
        ReaderTheme.White -> Color(0xFF8E8E93)
        ReaderTheme.Sepia -> Color(0xFF7E6F5C)
        ReaderTheme.Dark -> Color(0xFF8E8E93)
    }

    val borderColor = when (theme) {
        ReaderTheme.System -> MaterialTheme.colorScheme.outlineVariant
        ReaderTheme.White -> Color(0xFFD1D1D6)
        ReaderTheme.Sepia -> Color(0xFFC4B69F)
        ReaderTheme.Dark -> Color(0xFF3A3A3C)
    }

    // A subtly tinted panel behind code blocks so they read as monospace code, not prose.
    val codeBackgroundColor = when (theme) {
        ReaderTheme.System -> MaterialTheme.colorScheme.surfaceVariant
        ReaderTheme.White -> Color(0xFFF2F2F7)
        ReaderTheme.Sepia -> Color(0xFFEAE0C8)
        ReaderTheme.Dark -> Color(0xFF2C2C2E)
    }

    val fontFamily = getFontFamily(fontName)
    val scale = fontSizePercent / 100f

    // Reading-optimized text shaping: LineBreak.Paragraph does higher-quality (whole-paragraph)
    // line breaking and Hyphens.Auto reduces ragged right edges — the configuration Compose
    // recommends for long-form reading, closer to Safari Reader's typographic polish.
    val proseStyle = remember {
        TextStyle(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto)
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var columnCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val headingOffsets = remember(article) { mutableMapOf<ReaderBlock.Heading, Float>() }

    // Edge-to-edge: the background fills the whole screen (behind the status and gesture bars), and
    // the safe-area insets are applied as padding *inside* the scroll so the article content scrolls
    // under the bars rather than being clipped at the safe-area edges. The bottom inset clears the
    // gesture bar; the floating pill auto-hides on scroll-down, so we don't reserve its height here.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBarTopPx = with(density) { statusBarTop.toPx() }

    // Forward scroll deltas so the host can hide the floating pill when reading downward.
    LaunchedEffect(scrollState) {
        var last = scrollState.value
        snapshotFlow { scrollState.value }.collect { value ->
            onScrollDelta((value - last).toFloat())
            last = value
        }
    }

    // Render the reader surface into a graphics layer we can sample into a Bitmap for the tab
    // thumbnail. Capture once the new content has laid out, then again after a beat so async images
    // (AsyncImage) have a chance to load into the shot. Recording into a graphics layer on every
    // frame is wasted GPU work during normal reading-scroll, so we only route drawing through the
    // layer while a capture is armed (briefly after the article/theme/font changes); the rest of
    // the time we draw content directly. This keeps the common scroll path allocation-free.
    val captureLayer = rememberGraphicsLayer()
    var captureArmed by remember { mutableStateOf(true) }

    // System long-screenshot ("Capture more") support for reader mode. While a scroll capture is in
    // progress we keep the graphics layer armed (so the callback can sample the viewport) and hide
    // the reader's own overlays so they don't get stitched into every tile. See ReaderScrollCapture.
    val capturing by ReaderScrollCapture.capturing.collectAsState()
    val hostView = LocalView.current
    val captureScope = rememberCoroutineScope()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        // Register on the activity's content view, NOT the AndroidComposeView: Compose's own
        // scroll-capture support overrides onScrollCaptureSearch on its view and otherwise wins the
        // search (redrawing the whole composition, so the floating pill gets stitched into every
        // tile). We do two things while reader mode is active:
        //   1. Register our callback on the content FrameLayout (default search picks it up) — it
        //      renders from the reader layer, so the pill is excluded by construction.
        //   2. Set the AndroidComposeView's hint to EXCLUDE so its (and thus Compose's) target is
        //      dropped from the search entirely, leaving ours as the only candidate. Excluding a
        //      child does not exclude the ancestor content view, so our target survives.
        val captureTarget = hostView.rootView?.findViewById<android.view.View>(android.R.id.content)
        DisposableEffect(captureTarget, hostView, scrollState) {
            val callback = if (captureTarget != null) ReaderScrollCaptureCallback(
                hostView = hostView,
                scrollState = scrollState,
                scope = captureScope,
                captureViewport = {
                    runCatching { captureLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()
                },
            ) else null
            android.util.Log.d(
                "ReaderScrollCapture",
                "register callback: target=${captureTarget?.javaClass?.simpleName} host=${hostView.javaClass.simpleName}",
            )
            captureTarget?.setScrollCaptureCallback(callback)
            captureTarget?.scrollCaptureHint = android.view.View.SCROLL_CAPTURE_HINT_INCLUDE
            hostView.scrollCaptureHint = android.view.View.SCROLL_CAPTURE_HINT_EXCLUDE
            onDispose {
                captureTarget?.setScrollCaptureCallback(null)
                captureTarget?.scrollCaptureHint = android.view.View.SCROLL_CAPTURE_HINT_AUTO
                hostView.scrollCaptureHint = android.view.View.SCROLL_CAPTURE_HINT_AUTO
                ReaderScrollCapture.setCapturing(false)
            }
        }
    }

    // Tapping an article image opens it fullscreen (Safari-parity); null = no overlay.
    var zoomedImage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(article, theme, fontName, fontSizePercent) {
        captureArmed = true
        withFrameNanos {}
        withFrameNanos {}
        runCatching { onSnapshot(captureLayer.toImageBitmap().asAndroidBitmap()) }
        delay(800)
        runCatching { onSnapshot(captureLayer.toImageBitmap().asAndroidBitmap()) }
        captureArmed = false
    }

    // Scroll progress calculation
    val progress = if (scrollState.maxValue > 0) {
        (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val accentColor = when (theme) {
        ReaderTheme.System -> MaterialTheme.colorScheme.primary
        ReaderTheme.White -> Color(0xFF007AFF)
        ReaderTheme.Sepia -> Color(0xFF9E7E56)
        ReaderTheme.Dark -> Color(0xFF0A84FF)
    }

    val headings = remember(article) {
        article.blocks.filterIsInstance<ReaderBlock.Heading>().filter { it.text.isNotBlank() }
    }

    var showTOC by remember { mutableStateOf(false) }

    var isTOCButtonVisible by remember { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        var lastValue = scrollState.value
        snapshotFlow { scrollState.value }.collect { value ->
            val delta = value - lastValue
            if (delta > 10) {
                isTOCButtonVisible = false
            } else if (delta < -10) {
                isTOCButtonVisible = true
            }
            if (value < 50) {
                isTOCButtonVisible = true
            }
            lastValue = value
        }
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .fillMaxSize()
            .drawWithContent {
                if (captureArmed || capturing) {
                    captureLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(captureLayer)
                } else {
                    drawContent()
                }
            }
    ) {
        // Let users select and copy article text — a basic expectation in a reader view.
        SelectionContainer {
        Column(
            modifier = Modifier
                // Cap the line length on wide screens (landscape / tablets) so reading text
                // stays at a comfortable measure instead of spanning edge to edge. On phones in
                // portrait the screen is narrower than the cap, so this is a no-op there.
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .onGloballyPositioned { columnCoordinates = it }
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(top = statusBarTop + 16.dp, bottom = navBarBottom + 40.dp)
        ) {
            // Title
            Text(
                text = article.title,
                fontSize = (28.sp * scale),
                lineHeight = (36.sp * scale),
                fontWeight = FontWeight.Bold,
                fontFamily = fontFamily,
                color = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // Author card — visually distinct from article body (Safari-parity).
            val readMinutes = remember(article) { estimateReadMinutes(article) }
            val sourceHost = remember(article.sourceUrl) { readerHost(article.sourceUrl) }
            if (article.byline != null || sourceHost != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    // Thin separator above the author card.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(borderColor)
                            .padding(bottom = 16.dp)
                    )
                    // Author name — larger and bolder than the old muted italic line.
                    article.byline?.let { byline ->
                        Text(
                            text = byline,
                            fontSize = (16.sp * scale),
                            lineHeight = (22.sp * scale),
                            fontWeight = FontWeight.Medium,
                            fontFamily = fontFamily,
                            color = textColor,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // Source domain + reading time beneath the author name.
                    val readText = if (readMinutes > 0) {
                        if (readMinutes == 1) "1 min read" else "$readMinutes min read"
                    } else null
                    val metaText = listOfNotNull(sourceHost, readText).joinToString("  ·  ")
                    if (metaText.isNotEmpty()) {
                        Text(
                            text = metaText,
                            fontSize = (13.sp * scale),
                            lineHeight = (18.sp * scale),
                            fontFamily = fontFamily,
                            color = mutedTextColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                        )
                    }
                    // Thin separator below the author card.
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(borderColor)
                    )
                }
            }

            // Article Content
            article.blocks.forEach { block ->
                when (block) {
                    is ReaderBlock.Paragraph -> {
                        if (block.links.isNotEmpty()) {
                            val annotatedString = buildAnnotatedString {
                                var currentIndex = 0
                                for (link in block.links) {
                                    val index = block.text.indexOf(link.text, currentIndex)
                                    if (index != -1) {
                                        append(block.text.substring(currentIndex, index))
                                        val start = this.length
                                        append(link.text)
                                        val end = this.length
                                        addStringAnnotation(
                                            tag = "url",
                                            annotation = link.href,
                                            start = start,
                                            end = end
                                        )
                                        addStyle(
                                            style = SpanStyle(
                                                color = linkColor,
                                                textDecoration = TextDecoration.Underline
                                            ),
                                            start = start,
                                            end = end
                                        )
                                        currentIndex = index + link.text.length
                                    }
                                }
                                if (currentIndex < block.text.length) {
                                    append(block.text.substring(currentIndex))
                                }
                            }
                            ClickableText(
                                text = annotatedString,
                                onClick = { offset ->
                                    annotatedString.getStringAnnotations("url", offset, offset)
                                        .firstOrNull()?.let { onLinkClick(it.item) }
                                },
                                style = proseStyle.copy(
                                    fontSize = (18.sp * scale),
                                    lineHeight = (30.sp * scale),
                                    fontFamily = fontFamily,
                                    color = textColor
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 22.dp)
                            )
                        } else {
                            Text(
                                text = block.text,
                                fontSize = (18.sp * scale),
                                lineHeight = (30.sp * scale),
                                fontFamily = fontFamily,
                                color = textColor,
                                style = proseStyle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 22.dp)
                            )
                        }
                    }
                    is ReaderBlock.Heading -> {
                        val headerSize = when (block.level) {
                            1 -> 24.sp
                            2 -> 22.sp
                            3 -> 20.sp
                            else -> 18.sp
                        }
                        val headerLineHeight = when (block.level) {
                            1 -> 32.sp
                            2 -> 30.sp
                            3 -> 28.sp
                            else -> 26.sp
                        }
                        Text(
                            text = block.text,
                            fontSize = (headerSize * scale),
                            lineHeight = (headerLineHeight * scale),
                            fontWeight = FontWeight.Bold,
                            fontFamily = fontFamily,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    columnCoordinates?.let { colCoords ->
                                        val headingY = coords.positionInWindow().y
                                        val colY = colCoords.positionInWindow().y
                                        headingOffsets[block] = headingY - colY + scrollState.value
                                    }
                                }
                                .padding(top = 20.dp, bottom = 14.dp)
                        )
                    }
                    is ReaderBlock.Blockquote -> {
                        if (block.pullQuote) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp, horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "\u201C",
                                    fontSize = (48.sp * scale),
                                    lineHeight = (48.sp * scale),
                                    fontFamily = fontFamily,
                                    color = textColor.copy(alpha = 0.25f),
                                )
                                Text(
                                    text = block.text,
                                    fontSize = (22.sp * scale),
                                    lineHeight = (32.sp * scale),
                                    fontWeight = FontWeight.SemiBold,
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = fontFamily,
                                    color = textColor,
                                    style = proseStyle,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp, horizontal = 4.dp)
                                    .drawBehind {
                                        val strokeWidth = 4.dp.toPx()
                                        drawLine(
                                            color = borderColor,
                                            start = Offset(0f, 0f),
                                            end = Offset(0f, size.height),
                                            strokeWidth = strokeWidth
                                        )
                                    }
                                    .padding(start = 16.dp)
                            ) {
                                Text(
                                    text = block.text,
                                    fontSize = (18.sp * scale),
                                    lineHeight = (28.sp * scale),
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = fontFamily,
                                    color = mutedTextColor,
                                    style = proseStyle
                                )
                            }
                        }
                    }
                    is ReaderBlock.Code -> {
                        // Monospace, tinted, horizontally scrollable so long lines don't wrap or
                        // force the whole article to overflow; whitespace/newlines are preserved.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(codeBackgroundColor)
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = block.text,
                                fontSize = (15.sp * scale),
                                lineHeight = (22.sp * scale),
                                fontFamily = FontFamily.Monospace,
                                color = textColor,
                                softWrap = false,
                            )
                        }
                    }
                    is ReaderBlock.Table -> {
                        // Render each row as a readable line (cells separated), inside a tinted
                        // card. A fixed-width column grid is avoided deliberately: it needs a
                        // device to tune and breaks on wide tables; row lines preserve the data
                        // (key/value tables read as "Label · Value") without layout risk.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(codeBackgroundColor)
                                .padding(12.dp)
                        ) {
                            block.rows.forEachIndexed { rowIndex, row ->
                                val isHeader = block.hasHeader && rowIndex == 0
                                Text(
                                    text = row.joinToString("   ·   "),
                                    fontSize = (15.sp * scale),
                                    lineHeight = (22.sp * scale),
                                    fontFamily = fontFamily,
                                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isHeader) textColor else mutedTextColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                )
                            }
                        }
                    }
                    is ReaderBlock.ListItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Indent nested list items so sub-lists read as a hierarchy.
                                .padding(start = 16.dp + (block.depth * 20).dp, bottom = 10.dp)
                        ) {
                            // Nested unordered items use a hollow bullet to distinguish levels.
                            val prefix = if (block.ordered) "${block.index + 1}. " else if (block.depth > 0) "◦ " else "• "
                            Text(
                                text = prefix,
                                fontSize = (18.sp * scale),
                                lineHeight = (28.sp * scale),
                                fontFamily = fontFamily,
                                color = textColor
                            )
                            Text(
                                text = block.text,
                                fontSize = (18.sp * scale),
                                lineHeight = (28.sp * scale),
                                fontFamily = fontFamily,
                                color = textColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is ReaderBlock.Divider -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp)
                                .height(1.dp)
                                .background(borderColor)
                        )
                    }
                    is ReaderBlock.Image -> {
                        val context = LocalContext.current
                        // Collapse the whole block if the image fails to load — otherwise the
                        // reserved aspect-ratio slot would leave a permanent blank box for a dead
                        // (403/404) image.
                        var imageFailed by remember(block.src) { mutableStateOf(false) }
                        if (!imageFailed) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            val imageRequest = readerImageRequest(context, block.src, article.sourceUrl)
                            // Reserve the image's intrinsic aspect ratio (when known) so the
                            // article doesn't reflow/jump as each image streams in — the slot is
                            // sized correctly before the bitmap arrives.
                            val w = block.width
                            val h = block.height
                            val imageModifier = Modifier
                                .fillMaxWidth()
                                .let { if (w != null && h != null && w > 0 && h > 0) it.aspectRatio(w.toFloat() / h.toFloat()) else it }
                                .clip(RoundedCornerShape(8.dp))
                                // Tap to view fullscreen (Safari Reader parity).
                                .clickable { zoomedImage = block.src }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = block.caption,
                                contentScale = ContentScale.FillWidth,
                                onError = { imageFailed = true },
                                modifier = imageModifier
                            )
                            block.caption?.let { caption ->
                                Text(
                                    text = caption,
                                    fontSize = (13.sp * scale),
                                    lineHeight = (18.sp * scale),
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = fontFamily,
                                    color = mutedTextColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                )
                            }
                        }
                        } // if (!imageFailed)
                    }
                }
            }

            // Site attribution footer — "Article provided by [favicon] TechCrunch".
            val attributionHost = remember(article.sourceUrl) {
                article.sourceUrl?.let { url ->
                    runCatching {
                        java.net.URI(url).host?.removePrefix("www.")?.lowercase()
                    }.getOrNull()
                }
            }
            if (attributionHost != null) {
                Spacer(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(borderColor)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val context = LocalContext.current
                    val faviconUrl = remember(attributionHost) {
                        "https://$attributionHost/favicon.ico"
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(faviconUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = attributionHost,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Article provided by",
                        fontSize = (13.sp * scale),
                        lineHeight = (18.sp * scale),
                        fontFamily = fontFamily,
                        color = mutedTextColor,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = attributionHost,
                        fontSize = (13.sp * scale),
                        lineHeight = (18.sp * scale),
                        fontWeight = FontWeight.Medium,
                        fontFamily = fontFamily,
                        color = textColor,
                    )
                }
            }
        }
        } // SelectionContainer

        // Fullscreen image viewer. A Dialog handles the system Back press for dismissal; tap
        // anywhere dismisses. usePlatformDefaultWidth=false lets it fill the screen.
        zoomedImage?.let { src ->
            val context = LocalContext.current
            Dialog(
                onDismissRequest = { zoomedImage = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.96f))
                        .clickable { zoomedImage = null },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = readerImageRequest(context, src, article.sourceUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    )
                }
            }
        }

        // Reading Progress Bar
        if (progress > 0f && !capturing) {
            Box(
                modifier = Modifier
                    .padding(horizontal = topPadding * 0.8f)
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(accentColor)
                    .align(Alignment.TopStart)
            )
        }

        // Floating Table of Contents Button
        AnimatedVisibility(
            visible = headings.size >= 2 && isTOCButtonVisible && !capturing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = statusBarTop + 8.dp, end = 16.dp)
        ) {
            IconButton(
                onClick = { showTOC = true },
                modifier = Modifier
                    .size(40.dp)
                    .shadow(4.dp, CircleShape)
                    .background(
                        color = codeBackgroundColor,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Table of Contents",
                    tint = textColor
                )
            }
        }

        // Table of Contents Dialog (styled like a bottom sheet)
        if (showTOC) {
            val coroutineScope = rememberCoroutineScope()
            Dialog(
                onDismissRequest = { showTOC = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            onClick = { showTOC = false },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.6f)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .background(backgroundColor)
                            .clickable(
                                onClick = {},
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            )
                            .padding(bottom = navBarBottom)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Table of Contents",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = fontFamily,
                                color = textColor
                            )
                            IconButton(onClick = { showTOC = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = textColor
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(borderColor)
                        )

                        // Outline List
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            headings.forEach { heading ->
                                val indent = when (heading.level) {
                                    1 -> 0.dp
                                    2 -> 16.dp
                                    3 -> 32.dp
                                    else -> 48.dp
                                }
                                val textStyle = when (heading.level) {
                                    1 -> TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    2 -> TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    else -> TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
                                }

                                Text(
                                    text = heading.text,
                                    style = textStyle,
                                    fontFamily = fontFamily,
                                    color = textColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showTOC = false
                                            val offset = headingOffsets[heading]
                                            if (offset != null) {
                                                coroutineScope.launch {
                                                    val target = (offset - statusBarTopPx - with(density) { 16.dp.toPx() }).coerceAtLeast(0f)
                                                    scrollState.animateScrollTo(target.toInt())
                                                }
                                            }
                                        }
                                        .padding(start = indent, top = 12.dp, bottom = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds the Coil request for an article image. Sends a Referer (the article page) and a browser
 * User-Agent so CDNs with hotlink protection — which reject requests carrying no/foreign referer or
 * a non-browser UA — still serve the picture, and crossfades the result in. Shared by the inline
 * image and the fullscreen zoom viewer so both use identical headers/caching.
 */
private fun readerImageRequest(
    context: android.content.Context,
    src: String,
    sourceUrl: String?,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(src)
        .crossfade(true)
        .setHeader("User-Agent", READER_IMAGE_USER_AGENT)
        .apply { refererFor(sourceUrl, src)?.let { setHeader("Referer", it) } }
        .build()

/** A desktop-Chrome UA. Some CDNs 403 non-browser user agents; a realistic one avoids that. */
private const val READER_IMAGE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"

/**
 * The Referer to send when fetching an article image. Prefers the page the article came from (what
 * most hotlink checks allowlist); falls back to the image's own origin so we still send *something*
 * plausible when the source URL is unknown. Returns null only if neither yields a usable origin.
 */
private fun refererFor(sourceUrl: String?, imageSrc: String): String? {
    sourceUrl?.let { if (it.startsWith("http")) return it }
    return runCatching {
        val uri = android.net.Uri.parse(imageSrc)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme != null && host != null) "$scheme://$host/" else null
    }.getOrNull()
}

/**
 * Estimated minutes to read the article at ~220 wpm (a common adult prose reading rate). Counts
 * words in prose blocks (paragraphs, headings, blockquotes, list items); skips images and code,
 * which aren't "read" at prose speed. Returns 0 for trivially short extractions so the header line
 * is suppressed rather than showing a meaningless "1 min read" on a stub.
 */
private fun estimateReadMinutes(article: ReaderArticle): Int {
    var words = 0
    for (block in article.blocks) {
        val text = when (block) {
            is ReaderBlock.Paragraph -> block.text
            is ReaderBlock.Heading -> block.text
            is ReaderBlock.Blockquote -> block.text
            is ReaderBlock.ListItem -> block.text
            is ReaderBlock.Table -> block.rows.joinToString(" ") { it.joinToString(" ") }
            is ReaderBlock.Code -> null
            is ReaderBlock.Divider -> null
            is ReaderBlock.Image -> null
        } ?: continue
        if (text.isNotBlank()) words += text.trim().split(Regex("\\s+")).size
    }
    if (words < 60) return 0
    return Math.max(1, Math.round(words / 220f))
}

/**
 * The display host for the reader header (e.g. "nytimes.com"), stripped of a leading "www." and
 * lowercased. Null when [sourceUrl] is absent or has no host, so the meta line omits it cleanly.
 */
internal fun readerHost(sourceUrl: String?): String? {
    val url = sourceUrl?.takeIf { it.isNotBlank() } ?: return null
    // java.net.URI (not android.net.Uri) so this is testable on the JVM without Robolectric.
    return runCatching {
        java.net.URI(url).host
            ?.removePrefix("www.")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

private fun getFontFamily(fontName: String): FontFamily {
    return when (fontName) {
        "Georgia" -> FontFamily(Typeface.create("georgia", Typeface.NORMAL))
        "Charter" -> FontFamily(Typeface.create("charter", Typeface.NORMAL))
        "Athelas" -> FontFamily(Typeface.create("athelas", Typeface.NORMAL))
        else -> FontFamily.Default
    }
}
