package com.tracking.familyorbit.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

val OrbitNavy = Color(0xFF071A27)
val OrbitSurface = Color(0xFF102A38)
val OrbitLime = Color(0xFFC9FF4A)
val OrbitMint = Color(0xFF72E8C0)
val OrbitSky = Color(0xFF86C9FF)
val OrbitText = Color(0xFFF2F7F7)
val OrbitMuted = Color(0xFF9FB3BC)
val OrbitDanger = Color(0xFFFF8B7D)

private val OrbitColors = darkColorScheme(
    primary = OrbitLime,
    onPrimary = OrbitNavy,
    secondary = OrbitMint,
    onSecondary = OrbitNavy,
    tertiary = OrbitSky,
    background = OrbitNavy,
    onBackground = OrbitText,
    surface = OrbitSurface,
    onSurface = OrbitText,
    surfaceVariant = Color(0xFF183847),
    onSurfaceVariant = OrbitMuted,
    error = OrbitDanger,
)

@Composable
fun FamilyOrbitTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = OrbitColors, content = content)
}

@Composable
fun OrbitMark(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = OrbitLime,
        contentColor = OrbitNavy,
        shape = RoundedCornerShape(15.dp),
    ) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val lineWidth = size.minDimension * 0.09f
            val orbitRadius = size.minDimension * 0.30f
            drawCircle(
                color = OrbitNavy,
                radius = size.minDimension * 0.18f,
                style = Stroke(width = lineWidth),
            )
            drawCircle(color = OrbitNavy, radius = size.minDimension * 0.055f)
            drawCircle(
                color = OrbitNavy.copy(alpha = 0.52f),
                radius = orbitRadius,
                style = Stroke(width = lineWidth * 0.52f),
            )
            drawCircle(
                color = OrbitNavy,
                radius = size.minDimension * 0.075f,
                center = Offset(center.x + orbitRadius * 0.78f, center.y - orbitRadius * 0.62f),
            )
        }
    }
}
