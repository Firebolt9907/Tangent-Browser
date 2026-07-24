package com.firebolt9907.tangentbrowser.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.decode.SvgDecoder
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import com.firebolt9907.tangentbrowser.ui.components.Favicon
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.filled.Warning


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
    /**
     * Long-press on an in-article link → Safari/Arc-style "peek" glance. Reports the link href plus
     * the press point in **window** coordinates (so the host's morph can grow the card out of the
     * touch point). Fires only when the press lands on a link; long-pressing plain prose still starts
     * text selection as before.
     */
    onLinkLongPress: (String, Offset) -> Unit = { _, _ -> },
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
    // An SVG-capable Coil loader for reader rich content: charts/diagrams serialized to
    // data:image/svg+xml, and math renders (MediaWiki/MathJax) which are SVG. The app has no global
    // ImageLoaderFactory, so the singleton loader (used for photos) can't decode SVG — build one here
    // and pass it only to the rich renders.
    val svgContext = LocalContext.current
    val svgImageLoader = remember(svgContext) {
        ImageLoader.Builder(svgContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    // On a dark reader theme, math renders (black glyphs on transparent) would be invisible; invert
    // them to white. Charts/diagrams keep their real colors (meaningful) and instead sit on a light
    // card. Derived from the actual background luminance so it's right for System/White/Sepia/Dark.
    val readerIsDark = backgroundColor.luminance() < 0.5f
    val equationDarkFilter = remember(readerIsDark) {
        if (readerIsDark) {
            ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )))
        } else null
    }
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
            com.firebolt9907.tangentbrowser.util.TangentLog.debug("ReaderScrollCapture") {
                "register callback: target=${captureTarget?.javaClass?.simpleName} host=${hostView.javaClass.simpleName}"
            }
            captureTarget?.setScrollCaptureCallback(callback)
            captureTarget?.scrollCaptureHint = android.view.View.SCROLL_CAPTURE_HINT_INCLUDE or android.view.View.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS
            hostView.scrollCaptureHint = android.view.View.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS
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

    // Reading-progress (0..1). Provided as a lambda read in the progress bar's draw phase so
    // scrolling the article doesn't recompose ReaderView on every frame; `hasScrolled` gates the
    // bar's presence and flips only at the boundary.
    val readingProgress = remember(scrollState) {
        {
            if (scrollState.maxValue > 0) {
                (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    val hasScrolled by remember { derivedStateOf { scrollState.maxValue > 0 && scrollState.value > 0 } }

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
                    )
                    Spacer(Modifier.height(16.dp))
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

            val groupedBlocks = remember(article.blocks) {
                groupBlocksForRendering(article.blocks)
            }

            // Article Content
            groupedBlocks.forEach { block ->
                when (block) {
                    is ReaderBlock.Paragraph -> {
                        // Inline math: each MATH_SENTINEL in block.text maps, in order, to a rendered
                        // equation drawn on the baseline via Compose inlineContent. Charts/equations
                        // decode through the SVG-capable loader; black glyphs invert on dark themes.
                        val mathContext = LocalContext.current
                        val mathInline: Map<String, InlineTextContent> =
                            if (block.inlineMath.isEmpty()) emptyMap()
                            else block.inlineMath.mapIndexed { i, m ->
                                val aspect = if (m.width != null && m.height != null && m.width > 0 && m.height > 0)
                                    m.width.toFloat() / m.height.toFloat() else 2.2f
                                val heightEm = 1.15f
                                "m$i" to InlineTextContent(
                                    Placeholder(
                                        width = (heightEm * aspect).em,
                                        height = heightEm.em,
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                                    )
                                ) {
                                    AsyncImage(
                                        model = readerImageRequest(mathContext, m.src, article.sourceUrl),
                                        imageLoader = svgImageLoader,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        colorFilter = equationDarkFilter,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }.toMap()
                        val mathCounter = intArrayOf(0)
                        // Append a text chunk, turning each sentinel into an inline-math placeholder
                        // (advancing through block.inlineMath in order). Runs even inside link text so
                        // the counter never desyncs from the sentinel order.
                        val appendMath: androidx.compose.ui.text.AnnotatedString.Builder.(String) -> Unit = { s ->
                            var i = 0
                            while (i < s.length) {
                                val j = s.indexOf(MATH_SENTINEL, i)
                                if (j == -1) { append(s.substring(i)); i = s.length }
                                else {
                                    if (j > i) append(s.substring(i, j))
                                    appendInlineContent("m${mathCounter[0]}", "□")
                                    mathCounter[0]++
                                    i = j + 1
                                }
                            }
                        }
                        val hasMath = block.inlineMath.isNotEmpty()
                        if (block.links.isNotEmpty() || hasMath) {
                            val annotatedString = buildAnnotatedString {
                                var currentIndex = 0
                                for (link in block.links) {
                                    val index = block.text.indexOf(link.text, currentIndex)
                                    if (index != -1) {
                                        this.appendMath(block.text.substring(currentIndex, index))
                                        val start = this.length
                                        this.appendMath(link.text)
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
                                    this.appendMath(block.text.substring(currentIndex))
                                }
                            }
                          if (block.links.isEmpty()) {
                            // Math but no links: keep normal text selection (inside the outer
                            // SelectionContainer), just add the inline equations.
                            Text(
                                text = annotatedString,
                                inlineContent = mathInline,
                                fontSize = (18.sp * scale),
                                lineHeight = (30.sp * scale),
                                fontFamily = fontFamily,
                                color = textColor,
                                style = proseStyle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 22.dp)
                            )
                          } else {
                            // Layout + window position of this paragraph, captured so the tap /
                            // long-press detector can map the press point → text offset → link
                            // annotation, and hand the host a window-space origin for the peek morph.
                            val layoutResult = remember(annotatedString) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                            var paragraphCoords by remember(annotatedString) {
                                mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)
                            }
                            // Own both gestures ourselves: a tap on a link navigates, a long-press
                            // peeks. Text selection is disabled *only* on link-bearing paragraphs
                            // (DisableSelection) because the SelectionContainer's own long-press
                            // selection fires on the same timeout and can't be suppressed by
                            // consumption after the fact — it would highlight the link *and* peek.
                            // Link-free prose keeps normal selection.
                            DisableSelection {
                                Text(
                                    text = annotatedString,
                                    inlineContent = mathInline,
                                    onTextLayout = { layoutResult.value = it },
                                    style = proseStyle.copy(
                                        fontSize = (18.sp * scale),
                                        lineHeight = (30.sp * scale),
                                        fontFamily = fontFamily,
                                        color = textColor
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 22.dp)
                                        .onGloballyPositioned { paragraphCoords = it }
                                        .pointerInput(annotatedString) {
                                            detectTapGestures(
                                                onTap = { pos ->
                                                    val layout = layoutResult.value
                                                        ?: return@detectTapGestures
                                                    val offset = layout.getOffsetForPosition(pos)
                                                    annotatedString
                                                        .getStringAnnotations("url", offset, offset)
                                                        .firstOrNull()?.let { onLinkClick(it.item) }
                                                },
                                                onLongPress = { pos ->
                                                    val layout = layoutResult.value
                                                        ?: return@detectTapGestures
                                                    val offset = layout.getOffsetForPosition(pos)
                                                    val link = annotatedString
                                                        .getStringAnnotations("url", offset, offset)
                                                        .firstOrNull() ?: return@detectTapGestures
                                                    val windowPos = paragraphCoords
                                                        ?.localToWindow(pos) ?: pos
                                                    onLinkLongPress(link.item, windowPos)
                                                },
                                            )
                                        }
                                )
                            }
                          }
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
                                .padding(start = 16.dp + (block.depth * 20).dp, bottom = 18.dp)
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
                                .padding(top = 12.dp, bottom = 24.dp)
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
                    is ReaderBlock.Figure -> {
                        val context = LocalContext.current
                        var figFailed by remember(block.src) { mutableStateOf(false) }
                        if (!figFailed) {
                            if (block.equation) {
                                // Display equation (dedicated line): centred, sized to content,
                                // inverted to white on dark themes so the black glyphs stay visible.
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val eqH = block.height
                                    val eqW = block.width
                                    AsyncImage(
                                        model = readerImageRequest(context, block.src, article.sourceUrl),
                                        imageLoader = svgImageLoader,
                                        contentDescription = block.caption,
                                        contentScale = ContentScale.Fit,
                                        colorFilter = equationDarkFilter,
                                        onError = { figFailed = true },
                                        // MediaWiki serves math at a small intrinsic px size; scale
                                        // ~1.7x with the reader font so equations track body text.
                                        modifier = Modifier
                                            .let {
                                                if (eqW != null && eqH != null && eqW > 0 && eqH > 0) {
                                                    it.height((eqH * 1.7f * scale).dp)
                                                        .widthIn(max = maxOf(1, (eqW * 6)).dp)
                                                } else it.heightIn(max = (40 * scale).dp)
                                            }
                                    )
                                }
                            } else {
                                // Chart / diagram: keep true colors on a light card so it stays
                                // legible on any theme; reserve aspect ratio; tap to zoom.
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp, bottom = 24.dp)
                                ) {
                                    val w = block.width
                                    val h = block.height
                                    AsyncImage(
                                        model = readerImageRequest(context, block.src, article.sourceUrl),
                                        imageLoader = svgImageLoader,
                                        contentDescription = block.caption,
                                        contentScale = ContentScale.Fit,
                                        onError = { figFailed = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .let { if (w != null && h != null && w > 0 && h > 0) it.aspectRatio(w.toFloat() / h.toFloat()) else it }
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFFFFFFF))
                                            .padding(12.dp)
                                            .clickable { zoomedImage = block.src }
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
                            }
                        }
                    }
                    is CarouselBlock -> {
                        val images = block.images
                        val pagerState = rememberPagerState(pageCount = { images.size })
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 24.dp)
                        ) {
                            val aspectRatios = images.mapNotNull { img ->
                                if (img.width != null && img.height != null && img.width > 0 && img.height > 0) {
                                    img.width.toFloat() / img.height.toFloat()
                                } else {
                                    null
                                }
                            }
                            val carouselAspectRatio = aspectRatios.firstOrNull() ?: 1.5f
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(carouselAspectRatio)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(codeBackgroundColor)
                            ) {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { pageIndex ->
                                    val img = images[pageIndex]
                                    val context = LocalContext.current
                                    var imageFailed by remember(img.src) { mutableStateOf(false) }
                                    
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { zoomedImage = img.src },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (imageFailed) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center,
                                                modifier = Modifier.padding(16.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Warning,
                                                    contentDescription = "Failed to load image",
                                                    tint = mutedTextColor,
                                                    modifier = Modifier.size(48.dp)
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    text = "Image failed to load",
                                                    fontSize = (14.sp * scale),
                                                    color = mutedTextColor,
                                                    fontFamily = fontFamily
                                                )
                                            }
                                        } else {
                                            AsyncImage(
                                                model = readerImageRequest(context, img.src, article.sourceUrl),
                                                contentDescription = img.caption,
                                                contentScale = ContentScale.Fit,
                                                onError = { imageFailed = true },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }

                                // Page Counter Pill (Top Right)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(12.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${pagerState.currentPage + 1} / ${images.size}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = fontFamily
                                    )
                                }

                                // Interactive Pill Dot Indicator (Bottom Center)
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 12.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    repeat(images.size) { index ->
                                        val active = pagerState.currentPage == index
                                        val width = if (active) 12.dp else 6.dp
                                        val color = if (active) Color.White else Color.White.copy(alpha = 0.5f)
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 2.5.dp)
                                                .size(width = width, height = 6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(color)
                                        )
                                    }
                                }
                            }

                            // Dynamic Caption Crossfade Below Image
                            val currentImage = images[pagerState.currentPage]
                            Crossfade(targetState = currentImage.caption) { caption ->
                                caption?.let { text ->
                                    Text(
                                        text = text,
                                        fontSize = (13.sp * scale),
                                        lineHeight = (18.sp * scale),
                                        fontStyle = FontStyle.Italic,
                                        fontFamily = fontFamily,
                                        color = mutedTextColor,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp, start = 4.dp, end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Site attribution footer — "Article provided by [favicon] TechCrunch".
            val attributionHost = remember(article.sourceUrl) { readerHost(article.sourceUrl) }
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
                    Favicon(url = "https://$attributionHost", size = 16.dp)
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
                        // The SVG-capable loader also decodes ordinary bitmaps, so charts (SVG data
                        // URIs) and photos both zoom correctly through it.
                        model = readerImageRequest(context, src, article.sourceUrl),
                        imageLoader = svgImageLoader,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    )
                }
            }
        }

        // Reading Progress Bar — full-width bar scaled from the left edge in the draw phase, so a
        // scroll frame only repaints it (no ReaderView recomposition, no relayout).
        if (hasScrolled && !capturing) {
            Box(
                modifier = Modifier
                    .padding(horizontal = topPadding * 0.8f)
                    .fillMaxWidth()
                    .height(3.dp)
                    .graphicsLayer {
                        scaleX = readingProgress()
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(accentColor)
                    .align(Alignment.TopStart)
            )
        }

        ReaderTableOfContents(
            headings = headings,
            headingOffsets = headingOffsets,
            showTOC = showTOC,
            onShowTOC = { showTOC = true },
            onDismiss = { showTOC = false },
            isButtonVisible = isTOCButtonVisible && !capturing,
            scrollState = scrollState,
            statusBarTop = statusBarTop,
            statusBarTopPx = statusBarTopPx,
            navBarBottom = navBarBottom,
            density = density,
            fontFamily = fontFamily,
            textColor = textColor,
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            codeBackgroundColor = codeBackgroundColor,
        )
    }
}

private data class CarouselBlock(val images: List<ReaderBlock.Image>)

private fun groupBlocksForRendering(blocks: List<ReaderBlock>): List<Any> {
    val result = mutableListOf<Any>()
    val tempImages = mutableListOf<ReaderBlock.Image>()

    val flushImages = {
        if (tempImages.isNotEmpty()) {
            if (tempImages.size == 1) {
                result.add(tempImages[0])
            } else {
                result.add(CarouselBlock(tempImages.toList()))
            }
            tempImages.clear()
        }
    }

    for (block in blocks) {
        if (block is ReaderBlock.Image) {
            tempImages.add(block)
        } else {
            flushImages()
            result.add(block)
        }
    }
    flushImages()
    return result
}

