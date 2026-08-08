package com.trivox.client.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import com.trivox.client.network.ConnectionInfoManager
import com.trivox.client.network.ExitConnectionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WorldMapCard(
    connected: Boolean,
    settings: AppSettings,
    mode: ConnectionMode?,
    countryCode: String,
    countryName: String,
    flagText: String
) {
    val profileCode = remember(countryCode, flagText) {
        WorldMapGeo.resolveCode(countryCode, flagText)
    }

    val routeInfo by produceState<ExitConnectionInfo?>(
        initialValue = null,
        key1 = connected,
        key2 = mode,
        key3 = profileCode
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val lookupSettings = settings.copy(
                    mode = if (connected) {
                        mode ?: settings.mode
                    } else {
                        ConnectionMode.VPN
                    }
                ).normalize()
                ConnectionInfoManager().fetch(lookupSettings)
            }.getOrNull()
        }
    }

    val resolvedCode = remember(connected, routeInfo?.countryCode, profileCode) {
        val actual = routeInfo?.countryCode.orEmpty()
        WorldMapGeo.resolveCode(
            explicitCode = actual.ifBlank {
                if (connected) profileCode.orEmpty() else ""
            },
            flagOrName = if (connected) flagText else routeInfo?.flagEmoji.orEmpty()
        )
    }
    val point = remember(resolvedCode) { WorldMapGeo.pointFor(resolvedCode) }
    val flag = remember(resolvedCode, routeInfo?.flagEmoji) {
        routeInfo?.flagEmoji.orEmpty().ifBlank { WorldMapGeo.flagFor(resolvedCode) }
    }
    val shownCountry = routeInfo?.country.orEmpty().ifBlank {
        if (connected) countryName.ifBlank { resolvedCode.orEmpty() }
        else resolvedCode.orEmpty()
    }.ifBlank { "--" }
    val shownIp = routeInfo?.ip.orEmpty().ifBlank { "--" }

    val focusX = remember { Animatable(0.50f) }
    val focusY = remember { Animatable(0.50f) }
    val zoom = remember { Animatable(1.04f) }

    LaunchedEffect(connected, resolvedCode) {
        val target = point ?: WorldMapPoint(0.50f, 0.50f)
        val duration = if (connected) 1050 else 650
        focusX.animateTo(target.x, tween(duration, easing = FastOutSlowInEasing))
        focusY.animateTo(target.y, tween(duration, easing = FastOutSlowInEasing))
        zoom.animateTo(
            if (connected) 1.82f else if (point != null) 1.24f else 1.04f,
            spring(dampingRatio = 0.78f, stiffness = 165f)
        )
    }

    val shape = RoundedCornerShape(24.dp)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
        ) {
            val landColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.17f)
            val coastColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
            val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            val markerColor = MaterialTheme.colorScheme.primary

            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                for (step in 1..5) {
                    val x = size.width * step / 6f
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                }
                for (step in 1..3) {
                    val y = size.height * step / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                }

                fun transform(base: Offset): Offset {
                    val focus = Offset(size.width * focusX.value, size.height * focusY.value)
                    return Offset(
                        (base.x - focus.x) * zoom.value + size.width / 2f,
                        (base.y - focus.y) * zoom.value + size.height / 2f
                    )
                }

                WORLD_SHAPES.forEach { geo ->
                    drawGeoShape(geo, landColor, coastColor, ::transform)
                }

                point?.let { active ->
                    val center = transform(Offset(size.width * active.x, size.height * active.y))
                    drawCircle(markerColor.copy(alpha = 0.10f), 30.dp.toPx(), center)
                    drawCircle(markerColor.copy(alpha = 0.26f), 16.dp.toPx(), center)
                    drawCircle(markerColor, 5.dp.toPx(), center)
                }
            }

            point?.let { active ->
                if (flag.isNotBlank()) {
                    val displayX = ((active.x - focusX.value) * zoom.value + 0.5f).coerceIn(0f, 1f)
                    val displayY = ((active.y - focusY.value) * zoom.value + 0.5f).coerceIn(0f, 1f)
                    val markerSize = 42.dp
                    val x = (maxWidth * displayX - markerSize / 2).coerceIn(0.dp, maxWidth - markerSize)
                    val y = (maxHeight * displayY - markerSize - 7.dp).coerceIn(0.dp, maxHeight - markerSize)
                    Box(
                        modifier = Modifier
                            .offset(x, y)
                            .size(markerSize)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.60f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(flag, fontSize = 23.sp)
                    }
                }
            }

            Row(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                MapChip(buildString {
                    if (flag.isNotBlank()) {
                        append(flag)
                        append(' ')
                    }
                    append(shownCountry)
                })
            }
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                MapChip(shownIp)
            }
        }
    }
}

@Composable
private fun MapChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.91f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun DrawScope.drawGeoShape(
    points: List<Pair<Double, Double>>,
    fill: Color,
    stroke: Color,
    transform: (Offset) -> Offset
) {
    if (points.size < 3) return
    val path = Path()
    points.forEachIndexed { index, point ->
        val mapped = transform(project(point.first, point.second))
        if (index == 0) path.moveTo(mapped.x, mapped.y)
        else path.lineTo(mapped.x, mapped.y)
    }
    path.close()
    drawPath(path, fill)
    drawPath(path, stroke, style = Stroke(width = 1.2f))
}

private fun DrawScope.project(longitude: Double, latitude: Double): Offset =
    Offset(
        size.width * ((longitude + 180.0) / 360.0).toFloat(),
        size.height * ((90.0 - latitude) / 180.0).toFloat()
    )

private val WORLD_SHAPES = listOf(
    listOf(
        -168.0 to 72.0, -150.0 to 69.0, -134.0 to 58.0, -126.0 to 50.0,
        -121.0 to 44.0, -117.0 to 32.0, -112.0 to 24.0, -96.0 to 18.0,
        -82.0 to 25.0, -81.0 to 31.0, -75.0 to 39.0, -85.0 to 46.0,
        -95.0 to 52.0, -112.0 to 56.0, -130.0 to 58.0, -146.0 to 61.0,
        -160.0 to 65.0
    ),
    listOf(
        -82.0 to 12.0, -74.0 to 5.0, -71.0 to -5.0, -63.0 to -17.0,
        -56.0 to -26.0, -56.0 to -35.0, -52.0 to -55.0, -66.0 to -54.0,
        -75.0 to -18.0, -80.0 to -4.0
    ),
    listOf(
        -10.0 to 71.0, 3.0 to 71.0, 20.0 to 69.0, 31.0 to 62.0,
        41.0 to 56.0, 30.0 to 48.0, 22.0 to 43.0, 14.0 to 36.0,
        4.0 to 37.0, -3.0 to 42.0, -7.0 to 50.0
    ),
    listOf(
        -17.0 to 37.0, -6.0 to 34.0, 9.0 to 35.0, 24.0 to 32.0,
        35.0 to 32.0, 43.0 to 12.0, 51.0 to 11.0, 48.0 to -2.0,
        43.0 to -12.0, 40.0 to -24.0, 31.0 to -34.0, 16.0 to -35.0,
        7.0 to -29.0, 1.0 to -19.0, -5.0 to 4.0
    ),
    listOf(
        28.0 to 71.0, 48.0 to 66.0, 67.0 to 55.0, 78.0 to 50.0,
        98.0 to 54.0, 116.0 to 51.0, 127.0 to 43.0, 140.0 to 39.0,
        146.0 to 30.0, 136.0 to 19.0, 122.0 to 8.0, 109.0 to 3.0,
        100.0 to 8.0, 92.0 to 18.0, 79.0 to 22.0, 70.0 to 28.0,
        59.0 to 30.0, 46.0 to 34.0, 35.0 to 39.0
    ),
    listOf(
        110.0 to -10.0, 153.0 to -11.0, 151.0 to -39.0,
        144.0 to -44.0, 129.0 to -39.0, 115.0 to -35.0
    ),
    listOf(
        -53.0 to 82.0, -22.0 to 78.0, -29.0 to 70.0,
        -42.0 to 62.0, -58.0 to 63.0
    )
)
