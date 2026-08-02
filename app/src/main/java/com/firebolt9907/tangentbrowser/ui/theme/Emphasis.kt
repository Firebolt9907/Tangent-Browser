package com.firebolt9907.tangentbrowser.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The **Material 3 Expressive** emphasized type styles.
 *
 * Expressive adds a second, heavier cut of every type role. The emphasized cut is not a replacement
 * for the base role — it is its sibling, used deliberately where a surface needs to assert
 * hierarchy (a sheet title, an empty-state headline, a primary action label). Reaching for a raw
 * `FontWeight` instead is how weights drift: this app had a `titleLarge` at `Bold` (700) in one
 * shared component and a `titleMedium` at `SemiBold` (600) in another, straddling a spec that
 * places them at Medium (500) and Bold (700) respectively.
 *
 * The weights below are the library's own `TypeScaleTokens.*EmphasizedWeight` values, read out of
 * the material3 1.4.0 artifact (they exist there, but — like the rest of Expressive — are
 * `internal`; see [ExpressiveMotion] for the same story on motion). The pattern is regular:
 *
 * - display / headline / body / **titleLarge** → `Medium` (500)
 * - label / **titleMedium** / **titleSmall** → `Bold` (700)
 *
 * Only `fontWeight` is overridden; size, line height, tracking and family are inherited from the
 * theme's base role, so these follow any change to [Typography] rather than pinning a second copy
 * of the scale. (The spec also tightens tracking slightly on a few emphasized roles — deliberately
 * not reproduced here, because inheriting is what keeps these correct when the type scale moves.)
 *
 * Usage: `MaterialTheme.typography.titleLargeEmphasized` in place of a `style` + `fontWeight` pair.
 */
val Typography.displayLargeEmphasized: TextStyle
    get() = displayLarge.copy(fontWeight = FontWeight.Medium)

val Typography.displayMediumEmphasized: TextStyle
    get() = displayMedium.copy(fontWeight = FontWeight.Medium)

val Typography.displaySmallEmphasized: TextStyle
    get() = displaySmall.copy(fontWeight = FontWeight.Medium)

val Typography.headlineLargeEmphasized: TextStyle
    get() = headlineLarge.copy(fontWeight = FontWeight.Medium)

val Typography.headlineMediumEmphasized: TextStyle
    get() = headlineMedium.copy(fontWeight = FontWeight.Medium)

val Typography.headlineSmallEmphasized: TextStyle
    get() = headlineSmall.copy(fontWeight = FontWeight.Medium)

val Typography.titleLargeEmphasized: TextStyle
    get() = titleLarge.copy(fontWeight = FontWeight.Medium)

val Typography.titleMediumEmphasized: TextStyle
    get() = titleMedium.copy(fontWeight = FontWeight.Bold)

val Typography.titleSmallEmphasized: TextStyle
    get() = titleSmall.copy(fontWeight = FontWeight.Bold)

val Typography.bodyLargeEmphasized: TextStyle
    get() = bodyLarge.copy(fontWeight = FontWeight.Medium)

val Typography.bodyMediumEmphasized: TextStyle
    get() = bodyMedium.copy(fontWeight = FontWeight.Medium)

val Typography.bodySmallEmphasized: TextStyle
    get() = bodySmall.copy(fontWeight = FontWeight.Medium)

val Typography.labelLargeEmphasized: TextStyle
    get() = labelLarge.copy(fontWeight = FontWeight.Bold)

val Typography.labelMediumEmphasized: TextStyle
    get() = labelMedium.copy(fontWeight = FontWeight.Bold)

val Typography.labelSmallEmphasized: TextStyle
    get() = labelSmall.copy(fontWeight = FontWeight.Bold)
