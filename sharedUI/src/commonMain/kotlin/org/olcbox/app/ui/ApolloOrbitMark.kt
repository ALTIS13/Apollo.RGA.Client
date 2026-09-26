package org.olcbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

/** Approved v2 SVG geometry, drawn natively so Android never tries to decode an SVG resource. */
@Composable
internal fun ApolloOrbitMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 512f, size.height / 512f, pivot = Offset.Zero) }) {
            drawCircle(Color(0xFF07182F), radius = 238f, center = Offset(256f, 256f))
            withTransform({ rotate(-24f, pivot = Offset(256f, 256f)) }) {
                drawOval(
                    brush = Brush.linearGradient(
                        0f to Color(0xFF33E6FF),
                        0.52f to Color(0xFF3187FF),
                        1f to Color(0xFF9B5CFF),
                        start = Offset(96f, 120f), end = Offset(416f, 392f)
                    ),
                    topLeft = Offset(52f, 148f), size = Size(408f, 216f),
                    style = Stroke(width = 14f)
                )
            }
            val letter = Path().apply {
                fillType = PathFillType.EvenOdd
                moveTo(256f, 126f)
                lineTo(151f, 366f)
                lineTo(196f, 366f)
                lineTo(219f, 311f)
                lineTo(293f, 311f)
                lineTo(316f, 366f)
                lineTo(361f, 366f)
                close()
                moveTo(256f, 219f)
                lineTo(276f, 274f)
                lineTo(236f, 274f)
                close()
            }
            drawPath(letter, Color.White)
            drawCircle(Color(0xFF45E3FF), radius = 11f, center = Offset(382f, 156f))
        }
    }
}
