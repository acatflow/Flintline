package com.flintline.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 跟 logo（Ember Node 方向 C）同一套色板，深色底 + 琥珀色主题色。
val FlintAmber = Color(0xFFF59E0B)
val FlintAmberBright = Color(0xFFFBBF24)
val FlintBackground = Color(0xFF0B0A09)
val FlintSurface = Color(0xFF17140F)
val FlintSurfaceRaised = Color(0xFF26221C)
val FlintTextPrimary = Color(0xFFF5F1EA)
val FlintTextSecondary = Color(0xFFA39D92)
val FlintTextMuted = Color(0xFF7A756C)

private val FlintLineColorScheme = darkColorScheme(
    primary = FlintAmber,
    secondary = FlintAmberBright,
    background = FlintBackground,
    surface = FlintSurface,
    onBackground = FlintTextPrimary,
    onSurface = FlintTextPrimary,
)

@Composable
fun FlintLineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlintLineColorScheme,
        content = content,
    )
}
