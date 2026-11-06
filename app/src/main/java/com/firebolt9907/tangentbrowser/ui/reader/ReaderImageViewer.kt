package com.firebolt9907.tangentbrowser.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import com.firebolt9907.tangentbrowser.ui.shareUrl
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Native gallery shared by both reader surfaces. All transforms are local to this dialog. */
@Composable
internal fun ReaderImageViewer(
    images: List<ReaderGalleryImage>,
    initialSrc: String,
    sourceUrl: String?,
    imageLoader: ImageLoader,
    onOpenImage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = images.indexOfFirst { it.src == initialSrc }.coerceAtLeast(0)) { images.size }
    val image = images[pager.currentPage]
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var intrinsic by remember { mutableStateOf(Size.Zero) }
    var controlsVisible by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(220)) }
    LaunchedEffect(pager.currentPage) {
        zoom = 1f
        pan = Offset.Zero
        intrinsic = Size.Zero
        controlsVisible = true
    }

    fun dismiss() {
        if (closing) return
        closing = true
        scope.launch {
            entrance.animateTo(0f, tween(180))
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = entrance.value }
                .background(Color.Black)
                .safeDrawingPadding(),
        ) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = zoom <= 1.01f && !closing,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 0.94f + entrance.value * 0.06f
                    scaleY = scaleX
                },
            ) { page ->
                val entry = images[page]
                var loading by remember(entry.src) { mutableStateOf(true) }
                var failed by remember(entry.src) { mutableStateOf(false) }
                var retry by remember(entry.src) { mutableIntStateOf(0) }
                val active = page == pager.currentPage
                var decodedSize by remember(entry.src) { mutableStateOf(Size.Zero) }
                var pageSize by remember(entry.src) { mutableStateOf(Size.Zero) }
                LaunchedEffect(active, decodedSize, pageSize) {
                    if (active) {
                        intrinsic = decodedSize
                        viewport = pageSize
                        pan = clampReaderImagePan(pan, readerImagePanBounds(viewport, intrinsic, zoom))
                    }
                }
                val request = remember(entry.src, retry) { readerImageRequest(context, entry.src, sourceUrl).newBuilder().size(4096).build() }
                Box(
                    Modifier.fillMaxSize().clipToBounds()
                        .onSizeChanged {
                            pageSize = Size(it.width.toFloat(), it.height.toFloat())
                        }
                        .readerPinch(entry.src,
                            onZoom = { centroid, delta, factor ->
                                if (active && !loading && !failed && !closing) {
                                    val next = (zoom * factor).coerceIn(1f, 6f)
                                    val focus = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
                                    pan = clampReaderImagePan(
                                        (pan - focus) * (next / zoom) + focus + delta,
                                        readerImagePanBounds(viewport, intrinsic, next),
                                    )
                                    zoom = next
                                }
                            },
                            canPan = { active && zoom > 1.01f },
                        )
                        .pointerInput(entry.src, loading, failed) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = request,
                        imageLoader = imageLoader,
                        contentDescription = entry.caption ?: "Image ${page + 1} of ${images.size}",
                        contentScale = ContentScale.Fit,
                        onLoading = { loading = true; failed = false },
                        onSuccess = {
                            loading = false
                            failed = false
                            val drawable = it.result.drawable
                            decodedSize = Size(drawable.intrinsicWidth.coerceAtLeast(1).toFloat(), drawable.intrinsicHeight.coerceAtLeast(1).toFloat())
                        },
                        onError = { loading = false; failed = true },
                        modifier = Modifier.fillMaxSize().semantics {
                            stateDescription = if (loading) "Loading image" else if (failed) "Image failed to load" else "Image loaded"
                        }.graphicsLayer {
                            if (active) {
                                scaleX = zoom; scaleY = zoom
                                translationX = pan.x; translationY = pan.y
                            }
                        }.background(if (entry.lightBackground) Color.White else Color.Transparent),
                    )
                    if (loading) CircularProgressIndicator(color = Color.White)
                    if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn’t load this image", color = Color.White)
                        TextButton(onClick = { loading = true; failed = false; retry++ }) { Text("Retry", color = Color.White) }
                    }
                }
            }
            AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Surface(color = Color.Black.copy(alpha = 0.75f), contentColor = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ViewerButton(Icons.Default.Close, "Close image viewer", onClick = ::dismiss)
                        Text("${pager.currentPage + 1} / ${images.size}", Modifier.weight(1f))
                        // Embedded SVG data is meaningful only inside the reader; don't launch/share it.
                        if (image.src.startsWith("https://") || image.src.startsWith("http://")) {
                            ViewerButton(Icons.Default.Share, "Share image link", onClick = { shareUrl(context, image.src, onMessage = { message -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show() }) })
                            ViewerButton(Icons.AutoMirrored.Filled.OpenInNew, "Open original image", onClick = {
                                onOpenImage(image.src)
                                dismiss()
                            })
                        }
                    }
                }
            }
            AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Surface(color = Color.Black.copy(alpha = 0.8f), contentColor = Color.White, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        image.caption?.takeIf { it.isNotBlank() }?.let {
                            Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            ViewerButton(Icons.Default.ChevronLeft, "Previous image", pager.currentPage > 0, onClick = {
                                scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                            })
                            Text(
                                "${(zoom * 100).roundToInt()}%",
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = Color.White,
                            )
                            ViewerButton(Icons.Default.ChevronRight, "Next image", pager.currentPage < images.lastIndex, onClick = {
                                scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                            })
                        }
                        Text("Pinch to zoom · Swipe at fit to browse", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f))
    }
}
