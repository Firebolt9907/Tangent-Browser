package com.firebolt9907.luminabrowser.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 7

/**
 * Onboarding pager host. Shows a welcome hero, the three feature/permission pages
 * (Privacy / Search engine / Default browser, defined in sibling files), and a finish page.
 * [onFinish] is invoked when the user taps Skip or completes the flow with "Get started".
 */
@Composable
fun LuminaOnboarding(onFinish: () -> Unit) {
    val context = LocalContext.current
    val components = remember(context) {
        (context.applicationContext as com.firebolt9907.luminabrowser.LuminaApplication).components
    }
    // Firefox sign-in runs in a card *over* onboarding (not by dismissing it), so the user lands back
    // on this page to finish the remaining steps once signed in. Non-null while the OAuth flow is up.
    var signInUrl by remember { mutableStateOf<String?>(null) }

    LuminaBrowserTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
            val scope = rememberCoroutineScope()

            val currentPage by remember { derivedStateOf { pagerState.currentPage } }
            val isLastPage by remember { derivedStateOf { pagerState.currentPage == PAGE_COUNT - 1 } }

            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                // Top row: Back affordance (hidden on page 0), keeps space reserved for layout stability.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(visible = currentPage > 0) {
                        IconButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { page ->
                    val pageModifier = Modifier.fillMaxSize()
                    when (page) {
                        0 -> WelcomePage(pageModifier)
                        1 -> PrivacyPage(pageModifier)
                        2 -> SearchEnginePage(pageModifier)
                        3 -> AppearancePage(pageModifier)
                        4 -> DefaultBrowserPage(pageModifier)
                        5 -> FirefoxSyncPage(
                            onSignInUrl = { url -> signInUrl = url },
                            modifier = pageModifier,
                        )
                        else -> FinishPage(pageModifier)
                    }
                }

                // Bottom navigation: Skip | indicator dots | Next / Get started.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.width(72.dp)) {
                        if (!isLastPage) {
                            TextButton(onClick = onFinish) {
                                Text("Skip")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (index in 0 until PAGE_COUNT) {
                            PageIndicatorDot(selected = index == currentPage)
                            if (index < PAGE_COUNT - 1) Spacer(modifier = Modifier.width(6.dp))
                        }
                    }

                    Box(
                        modifier = Modifier.width(120.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Button(onClick = {
                            if (isLastPage) {
                                onFinish()
                            } else {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        }) {
                            Text(if (isLastPage) "Get started" else "Next")
                        }
                    }
                }
            }

            // Sign-in card floated over onboarding (never dismisses it). onIntercept completes the
            // OAuth flow on the success redirect and the card dismisses itself, returning the user to
            // this page — now signed in — to finish the remaining steps.
            signInUrl?.let { url ->
                com.firebolt9907.luminabrowser.ui.content.UtilityWebCard(
                    request = com.firebolt9907.luminabrowser.ui.content.UtilityWebRequest.Url(
                        title = "Firefox Sign In",
                        url = url,
                    ),
                    engine = components.engine,
                    onIntercept = { navUrl ->
                        components.firefoxSyncController.handleOAuthRedirect(navUrl)
                    },
                    onDismiss = { signInUrl = null },
                )
            }
            }
        }
    }
}

@Composable
private fun PageIndicatorDot(selected: Boolean) {
    val width by animateDpAsState(
        targetValue = if (selected) 22.dp else 8.dp,
        animationSpec = spring(),
        label = "dotWidth",
    )
    val color by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "dotColor",
    )
    Box(
        modifier = Modifier
            .height(8.dp)
            .width(width)
            .clip(SquircleShape(4.dp))
            .background(color),
    )
}

/**
 * Shared centered layout for the pages defined in this file: icon on top, headline, supporting text.
 */
@Composable
private fun OnboardingHero(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    iconBackground: Color,
    headline: String,
    supporting: String,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
        ) {
            Surface(
                shape = SquircleShape(36.dp),
                color = iconBackground,
                modifier = Modifier.size(120.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WelcomePage(modifier: Modifier) {
    OnboardingHero(
        modifier = modifier,
        icon = Icons.Rounded.Explore,
        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
        iconBackground = MaterialTheme.colorScheme.primaryContainer,
        headline = "Welcome to Lumina",
        supporting = "A fast, private browser that gets out of your way.",
    )
}

@Composable
private fun FinishPage(modifier: Modifier) {
    OnboardingHero(
        modifier = modifier,
        icon = Icons.Rounded.CheckCircle,
        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
        iconBackground = MaterialTheme.colorScheme.tertiaryContainer,
        headline = "You're all set",
        supporting = "Everything's ready. Tap Get started to begin browsing with Lumina.",
    )
}
