// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser

// The four transport glyphs that material-icons-core does not carry (Pause, Stop, SkipPrevious, SkipNext), drawn from the
// standard Material 24dp outlines so the card needs no material-icons-extended dependency (~30 MB of icons, plan 07-08).

private fun glyph(name: String, path: String): ImageVector = materialIcon(name) {
    addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
}

internal val PauseIcon: ImageVector by lazy { glyph("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z") }
internal val StopIcon: ImageVector by lazy { glyph("Stop", "M6,6h12v12H6z") }
internal val SkipPreviousIcon: ImageVector by lazy { glyph("SkipPrevious", "M6,6h2v12L6,18zM9.5,12l8.5,6L18,6z") }
internal val SkipNextIcon: ImageVector by lazy { glyph("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2L18,6h-2z") }
