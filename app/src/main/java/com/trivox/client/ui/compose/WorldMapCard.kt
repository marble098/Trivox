package com.trivox.client.ui.compose

// TRIVOX_V24_NATURAL_EARTH_WORLD_MAP

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import com.trivox.client.network.ConnectionInfoManager
import com.trivox.client.network.ExitConnectionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WorldMapCard(
    connected: Boolean,
    sessionId: Long,
    settings: AppSettings,
    exitIp: String,
    countryCode: String,
    countryName: String,
    flagText: String,
    hideIp: Boolean
) {
    val directInfo by produceState<ExitConnectionInfo?>(
        initialValue = null,
        key1 = connected,
        key2 = sessionId
    ) {
        if (connected) {
            DirectMapInfoCache.clear()
            value = null
            return@produceState
        }

        DirectMapInfoCache
            .getFresh()
            ?.let {
                value = it
                return@produceState
            }

        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    ConnectionInfoManager().fetch(
                        settings.copy(
                            mode = ConnectionMode.VPN
                        ).normalize()
                    )
                }.getOrNull()
                    ?.also(
                        DirectMapInfoCache::put
                    )
            }
    }

    val profileCode =
        remember(
            countryCode,
            flagText
        ) {
            WorldMapGeo.resolveCode(
                explicitCode = countryCode,
                flagOrName = flagText
            )
        }

    val resolvedCode =
        remember(
            connected,
            directInfo?.countryCode,
            profileCode
        ) {
            if (connected) {
                profileCode
            } else {
                WorldMapGeo.resolveCode(
                    explicitCode =
                        directInfo
                            ?.countryCode
                            .orEmpty(),
                    flagOrName =
                        directInfo
                            ?.flagEmoji
                            .orEmpty()
                )
            }
        }

    val rawPoint =
        remember(resolvedCode) {
            WorldMapGeo.pointFor(
                resolvedCode
            )
        }
    val activePoint =
        remember(rawPoint) {
            rawPoint?.toViewportPoint()
        }

    val directPoint =
        remember(
            directInfo?.countryCode
        ) {
            WorldMapGeo
                .pointFor(
                    WorldMapGeo.resolveCode(
                        directInfo
                            ?.countryCode
                            .orEmpty(),
                        directInfo
                            ?.flagEmoji
                            .orEmpty()
                    )
                )
                ?.toViewportPoint()
        }

    var lastDirectPoint by remember {
        mutableStateOf<WorldMapPoint?>(
            null
        )
    }

    LaunchedEffect(
        connected,
        directPoint
    ) {
        if (
            !connected &&
            directPoint != null
        ) {
            lastDirectPoint =
                directPoint
        }
    }

    val flag =
        remember(
            connected,
            resolvedCode,
            directInfo?.flagEmoji,
            flagText
        ) {
            if (connected) {
                WorldMapGeo
                    .codeFromFlag(
                        flagText
                    )
                    ?.let(
                        WorldMapGeo::flagFor
                    )
                    .orEmpty()
                    .ifBlank {
                        WorldMapGeo.flagFor(
                            resolvedCode
                        )
                    }
            } else {
                directInfo
                    ?.flagEmoji
                    .orEmpty()
                    .ifBlank {
                        WorldMapGeo.flagFor(
                            resolvedCode
                        )
                    }
            }
        }

    val shownCountry =
        if (connected) {
            countryName
                .ifBlank {
                    resolvedCode
                        .orEmpty()
                }
        } else {
            directInfo
                ?.country
                .orEmpty()
                .ifBlank {
                    resolvedCode
                        .orEmpty()
                }
        }.ifBlank { "…" }

    val rawIp =
        if (connected) {
            exitIp
        } else {
            directInfo
                ?.ip
                .orEmpty()
        }

    val shownIp =
        when {
            hideIp &&
                rawIp.isNotBlank() ->
                "••••••••"
            rawIp.isNotBlank() ->
                rawIp
            else ->
                "…"
        }

    val focusX =
        remember {
            Animatable(0.50f)
        }
    val focusY =
        remember {
            Animatable(0.50f)
        }
    val zoom =
        remember {
            Animatable(1.0f)
        }
    val reveal =
        remember {
            Animatable(1.0f)
        }

    LaunchedEffect(
        connected,
        sessionId,
        resolvedCode
    ) {
        val target =
            activePoint
                ?: WorldMapPoint(
                    x = 0.50f,
                    y = 0.50f
                )

        reveal.snapTo(0f)

        coroutineScope {
            launch {
                focusX.animateTo(
                    targetValue =
                        target.x,
                    animationSpec =
                        tween(
                            durationMillis =
                                if (connected) {
                                    980
                                } else {
                                    620
                                },
                            easing =
                                FastOutSlowInEasing
                        )
                )
            }
            launch {
                focusY.animateTo(
                    targetValue =
                        target.y,
                    animationSpec =
                        tween(
                            durationMillis =
                                if (connected) {
                                    980
                                } else {
                                    620
                                },
                            easing =
                                FastOutSlowInEasing
                        )
                )
            }
            launch {
                zoom.animateTo(
                    targetValue =
                        when {
                            connected &&
                                activePoint != null ->
                                1.48f
                            activePoint != null ->
                                1.10f
                            else ->
                                1.0f
                        },
                    animationSpec =
                        tween(
                            durationMillis =
                                if (connected) {
                                    1_080
                                } else {
                                    680
                                },
                            easing =
                                FastOutSlowInEasing
                        )
                )
            }
            launch {
                reveal.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = 720,
                            easing =
                                FastOutSlowInEasing
                        )
                )
            }
        }
    }

    val shape =
        RoundedCornerShape(24.dp)

    ElevatedCard(
        modifier =
            Modifier.fillMaxWidth(),
        shape = shape,
        colors =
            CardDefaults
                .elevatedCardColors(
                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surfaceContainerLow
                )
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(214.dp)
                    .clip(shape)
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceContainer
                    )
        ) {
            val oceanColor =
                MaterialTheme
                    .colorScheme
                    .surfaceContainer
            val landColor =
                MaterialTheme
                    .colorScheme
                    .primary
                    .copy(alpha = 0.16f)
            val landEdgeColor =
                MaterialTheme
                    .colorScheme
                    .primary
                    .copy(alpha = 0.38f)
            val gridColor =
                MaterialTheme
                    .colorScheme
                    .outlineVariant
                    .copy(alpha = 0.22f)
            val equatorColor =
                MaterialTheme
                    .colorScheme
                    .outline
                    .copy(alpha = 0.22f)
            val markerColor =
                MaterialTheme
                    .colorScheme
                    .primary
            val routeColor =
                MaterialTheme
                    .colorScheme
                    .tertiary

            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(7.dp)
            ) {
                drawRect(
                    color = oceanColor
                )

                fun map(
                    point: WorldMapPoint
                ): Offset =
                    transformPoint(
                        normalized =
                            Offset(
                                point.x,
                                point.y
                            ),
                        focusX =
                            focusX.value,
                        focusY =
                            focusY.value,
                        zoom =
                            zoom.value
                    )

                for (step in 1..5) {
                    val x =
                        step / 6f
                    val start =
                        transformPoint(
                            Offset(
                                x,
                                0f
                            ),
                            focusX.value,
                            focusY.value,
                            zoom.value
                        )
                    val end =
                        transformPoint(
                            Offset(
                                x,
                                1f
                            ),
                            focusX.value,
                            focusY.value,
                            zoom.value
                        )
                    drawLine(
                        color = gridColor,
                        start = start,
                        end = end,
                        strokeWidth = 1f
                    )
                }

                listOf(
                    -45f,
                    0f,
                    45f
                ).forEach {
                        latitude ->

                    val y =
                        (
                            MAP_MAX_LAT -
                                latitude
                            ) /
                            (
                                MAP_MAX_LAT -
                                    MAP_MIN_LAT
                                )

                    val start =
                        transformPoint(
                            Offset(
                                0f,
                                y
                            ),
                            focusX.value,
                            focusY.value,
                            zoom.value
                        )
                    val end =
                        transformPoint(
                            Offset(
                                1f,
                                y
                            ),
                            focusX.value,
                            focusY.value,
                            zoom.value
                        )

                    drawLine(
                        color =
                            if (latitude == 0f) {
                                equatorColor
                            } else {
                                gridColor
                            },
                        start = start,
                        end = end,
                        strokeWidth =
                            if (latitude == 0f) {
                                1.25f
                            } else {
                                1f
                            }
                    )
                }

                WORLD_MAP_POLYGONS
                    .forEach {
                        polygon ->

                        drawWorldPolygon(
                            points = polygon,
                            fill = landColor,
                            stroke =
                                landEdgeColor,
                            focusX =
                                focusX.value,
                            focusY =
                                focusY.value,
                            zoom =
                                zoom.value
                        )
                    }

                if (
                    connected &&
                    lastDirectPoint != null &&
                    activePoint != null &&
                    lastDirectPoint !=
                        activePoint
                ) {
                    val from =
                        map(
                            checkNotNull(
                                lastDirectPoint
                            )
                        )
                    val to =
                        map(
                            activePoint
                        )
                    val controlX =
                        (
                            from.x +
                                to.x
                            ) /
                            2f
                    val controlY =
                        minOf(
                            from.y,
                            to.y
                        ) -
                            size.height *
                            0.13f

                    val route =
                        Path().apply {
                            moveTo(
                                from.x,
                                from.y
                            )
                            quadraticTo(
                                controlX,
                                controlY,
                                to.x,
                                to.y
                            )
                        }

                    drawPath(
                        path = route,
                        color =
                            routeColor.copy(
                                alpha =
                                    0.18f +
                                        reveal.value *
                                        0.48f
                            ),
                        style =
                            Stroke(
                                width =
                                    1.7.dp
                                        .toPx()
                            )
                    )
                }

                activePoint?.let {
                        target ->

                    val center =
                        map(target)
                    val enter =
                        reveal.value
                            .coerceIn(
                                0f,
                                1f
                            )

                    drawCircle(
                        color =
                            markerColor
                                .copy(
                                    alpha =
                                        0.08f +
                                            enter *
                                            0.05f
                                ),
                        radius =
                            (
                                28f -
                                    enter *
                                    6f
                                ).dp
                                .toPx(),
                        center = center
                    )
                    drawCircle(
                        color =
                            markerColor
                                .copy(
                                    alpha =
                                        0.22f +
                                            enter *
                                            0.13f
                                ),
                        radius =
                            12.dp
                                .toPx(),
                        center = center
                    )
                    drawCircle(
                        color =
                            markerColor,
                        radius =
                            4.5.dp
                                .toPx(),
                        center = center
                    )
                }
            }

            activePoint?.let {
                    point ->

                if (flag.isNotBlank()) {
                    val normalized =
                        Offset(
                            (
                                (
                                    point.x -
                                        focusX.value
                                    ) *
                                    zoom.value +
                                    0.5f
                                ).coerceIn(
                                0f,
                                1f
                            ),
                            (
                                (
                                    point.y -
                                        focusY.value
                                    ) *
                                    zoom.value +
                                    0.5f
                                ).coerceIn(
                                0f,
                                1f
                            )
                        )
                    val markerSize =
                        42.dp
                    val x =
                        (
                            maxWidth *
                                normalized.x -
                                markerSize /
                                2
                            ).coerceIn(
                            0.dp,
                            maxWidth -
                                markerSize
                        )
                    val y =
                        (
                            maxHeight *
                                normalized.y -
                                markerSize -
                                8.dp
                            ).coerceIn(
                            0.dp,
                            maxHeight -
                                markerSize
                        )

                    Box(
                        modifier =
                            Modifier
                                .offset(
                                    x = x,
                                    y = y
                                )
                                .size(
                                    markerSize
                                )
                                .background(
                                    MaterialTheme
                                        .colorScheme
                                        .surface
                                        .copy(
                                            alpha =
                                                0.96f
                                        ),
                                    CircleShape
                                )
                                .border(
                                    width = 1.dp,
                                    color =
                                        markerColor
                                            .copy(
                                                alpha =
                                                    0.58f
                                            ),
                                    shape =
                                        CircleShape
                                ),
                        contentAlignment =
                            Alignment.Center
                    ) {
                        Text(
                            text = flag,
                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium
                        )
                    }
                }
            }

            Row(
                modifier =
                    Modifier
                        .align(
                            Alignment.TopStart
                        )
                        .padding(10.dp)
            ) {
                MapChip(
                    text =
                        buildString {
                            if (
                                flag.isNotBlank()
                            ) {
                                append(flag)
                                append(' ')
                            }
                            append(
                                shownCountry
                            )
                        },
                    maxWidth = 170.dp
                )
            }

            Row(
                modifier =
                    Modifier
                        .align(
                            Alignment.TopEnd
                        )
                        .padding(10.dp)
            ) {
                MapChip(
                    text = shownIp,
                    maxWidth = 154.dp
                )
            }
        }
    }
}

@Composable
private fun MapChip(
    text: String,
    maxWidth:
        androidx.compose.ui.unit.Dp
) {
    Surface(
        modifier =
            Modifier.widthIn(
                max = maxWidth
            ),
        shape =
            RoundedCornerShape(50),
        color =
            MaterialTheme
                .colorScheme
                .surface
                .copy(
                    alpha = 0.92f
                ),
        tonalElevation = 2.dp
    ) {
        Text(
            text = text,
            modifier =
                Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 6.dp
                ),
            maxLines = 1,
            overflow =
                TextOverflow.Ellipsis,
            style =
                MaterialTheme
                    .typography
                    .labelSmall,
            fontWeight =
                FontWeight.SemiBold
        )
    }
}

private fun WorldMapPoint.toViewportPoint():
    WorldMapPoint {
    val longitude =
        x * 360f -
            180f
    val latitude =
        90f -
            y * 180f

    return WorldMapPoint(
        x =
            (
                (
                    longitude +
                        180f
                    ) /
                    360f
                ).coerceIn(
                0f,
                1f
            ),
        y =
            (
                (
                    MAP_MAX_LAT -
                        latitude
                    ) /
                    (
                        MAP_MAX_LAT -
                            MAP_MIN_LAT
                        )
                ).coerceIn(
                0f,
                1f
            )
    )
}

private fun DrawScope.transformPoint(
    normalized: Offset,
    focusX: Float,
    focusY: Float,
    zoom: Float
): Offset =
    Offset(
        x =
            (
                normalized.x -
                    focusX
                ) *
                zoom *
                size.width +
                size.width /
                2f,
        y =
            (
                normalized.y -
                    focusY
                ) *
                zoom *
                size.height +
                size.height /
                2f
    )

private fun DrawScope.drawWorldPolygon(
    points: FloatArray,
    fill: Color,
    stroke: Color,
    focusX: Float,
    focusY: Float,
    zoom: Float
) {
    if (points.size < 6) return

    val path =
        Path()

    var index = 0
    while (
        index + 1 <
        points.size
    ) {
        val point =
            transformPoint(
                normalized =
                    Offset(
                        points[index],
                        points[index + 1]
                    ),
                focusX = focusX,
                focusY = focusY,
                zoom = zoom
            )

        if (index == 0) {
            path.moveTo(
                point.x,
                point.y
            )
        } else {
            path.lineTo(
                point.x,
                point.y
            )
        }

        index += 2
    }

    path.close()

    drawPath(
        path = path,
        color = fill
    )
    drawPath(
        path = path,
        color = stroke,
        style =
            Stroke(
                width = 0.85f
            )
    )
}

private object DirectMapInfoCache {
    @Volatile
    private var value:
        ExitConnectionInfo? = null

    @Volatile
    private var storedAt:
        Long = 0L

    fun getFresh():
        ExitConnectionInfo? {
        val current =
            value
                ?: return null

        return current.takeIf {
            System.currentTimeMillis() -
                storedAt <
                TTL_MS
        }
    }

    fun put(
        info: ExitConnectionInfo
    ) {
        value = info
        storedAt =
            System.currentTimeMillis()
    }

    fun clear() {
        value = null
        storedAt = 0L
    }

    private const val TTL_MS =
        120_000L
}

private const val MAP_MIN_LAT =
    -60f
private const val MAP_MAX_LAT =
    85f
