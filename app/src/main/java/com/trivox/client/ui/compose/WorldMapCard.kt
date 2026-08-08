package com.trivox.client.ui.compose

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.trivox.client.R

@Composable
internal fun WorldMapCard(
    activity: Activity,
    connected: Boolean,
    countryCode: String,
    countryName: String,
    flagText: String
) {
    val resolvedCode =
        remember(
            countryCode,
            flagText
        ) {
            WorldMapGeo.resolveCode(
                countryCode,
                flagText
            )
        }
    val point =
        remember(resolvedCode) {
            WorldMapGeo.pointFor(
                resolvedCode
            )
        }
    val flag =
        remember(
            resolvedCode,
            flagText
        ) {
            WorldMapGeo
                .codeFromFlag(flagText)
                ?.let {
                    WorldMapGeo.flagFor(it)
                }
                .orEmpty()
                .ifBlank {
                    WorldMapGeo.flagFor(
                        resolvedCode
                    )
                }
        }

    val landColor =
        MaterialTheme
            .colorScheme
            .primary
            .copy(alpha = 0.16f)
    val coastColor =
        MaterialTheme
            .colorScheme
            .primary
            .copy(alpha = 0.30f)
    val gridColor =
        MaterialTheme
            .colorScheme
            .outlineVariant
            .copy(alpha = 0.33f)
    val markerColor =
        MaterialTheme
            .colorScheme
            .primary

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults
                .elevatedCardColors(
                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surfaceContainerLow
                )
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 14.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text(
                        activity.getString(
                            R.string
                                .v22_world_map_title
                        ),
                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.SemiBold
                    )
                    Text(
                        if (
                            connected &&
                            point != null
                        ) {
                            countryName
                                .ifBlank {
                                    resolvedCode
                                        .orEmpty()
                                }
                                .ifBlank {
                                    activity.getString(
                                        R.string
                                            .v22_world_map_resolving
                                    )
                                }
                        } else if (connected) {
                            activity.getString(
                                R.string
                                    .v22_world_map_resolving
                            )
                        } else {
                            activity.getString(
                                R.string
                                    .v22_world_map_waiting
                            )
                        },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                        maxLines = 1,
                        overflow =
                            TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color =
                        if (connected) {
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                        } else {
                            MaterialTheme
                                .colorScheme
                                .surfaceContainerHighest
                        }
                ) {
                    Text(
                        if (connected) {
                            activity.getString(
                                R.string
                                    .v22_world_map_live
                            )
                        } else {
                            activity.getString(
                                R.string
                                    .v22_world_map_idle
                            )
                        },
                        modifier =
                            Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 5.dp
                            ),
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        fontWeight =
                            FontWeight.SemiBold
                    )
                }
            }

            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .background(
                            MaterialTheme
                                .colorScheme
                                .surfaceContainer,
                            RoundedCornerShape(18.dp)
                        )
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                ) {
                    for (step in 1..5) {
                        val x =
                            size.width *
                                step / 6f
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end =
                                Offset(
                                    x,
                                    size.height
                                ),
                            strokeWidth = 1f
                        )
                    }
                    for (step in 1..3) {
                        val y =
                            size.height *
                                step / 4f
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end =
                                Offset(
                                    size.width,
                                    y
                                ),
                            strokeWidth = 1f
                        )
                    }

                    WORLD_SHAPES
                        .forEach { shape ->
                            drawGeoShape(
                                shape,
                                fill = landColor,
                                stroke = coastColor
                            )
                        }

                    if (
                        connected &&
                        point != null
                    ) {
                        val center =
                            Offset(
                                size.width *
                                    point.x,
                                size.height *
                                    point.y
                            )
                        drawCircle(
                            color =
                                markerColor
                                    .copy(
                                        alpha = 0.13f
                                    ),
                            radius =
                                24.dp.toPx(),
                            center = center
                        )
                        drawCircle(
                            color =
                                markerColor
                                    .copy(
                                        alpha = 0.30f
                                    ),
                            radius =
                                13.dp.toPx(),
                            center = center
                        )
                        drawCircle(
                            color = markerColor,
                            radius = 5.dp.toPx(),
                            center = center
                        )
                    }
                }

                if (
                    connected &&
                    point != null &&
                    flag.isNotBlank()
                ) {
                    val markerSize = 38.dp
                    val x =
                        (
                            maxWidth * point.x -
                                markerSize / 2
                            ).coerceIn(
                                0.dp,
                                maxWidth -
                                    markerSize
                            )
                    val y =
                        (
                            maxHeight * point.y -
                                markerSize -
                                7.dp
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
                                .size(markerSize)
                                .background(
                                    MaterialTheme
                                        .colorScheme
                                        .surface,
                                    CircleShape
                                )
                                .border(
                                    width = 1.dp,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .primary
                                            .copy(
                                                alpha = 0.55f
                                            ),
                                    shape = CircleShape
                                ),
                        contentAlignment =
                            Alignment.Center
                    ) {
                        Text(
                            flag,
                            fontSize = 21.sp
                        )
                    }
                }
            }

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    activity.getString(
                        R.string
                            .v22_world_map_offline
                    ),
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Surface(
                    modifier =
                        Modifier.size(5.dp),
                    shape = CircleShape,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                ) {}
            }
        }
    }
}

private fun DrawScope.drawGeoShape(
    points: List<Pair<Double, Double>>,
    fill: Color,
    stroke: Color
) {
    if (points.size < 3) return
    val path = Path()
    points.forEachIndexed {
            index,
            point ->
        val projected =
            project(
                longitude =
                    point.first,
                latitude =
                    point.second
            )
        if (index == 0) {
            path.moveTo(
                projected.x,
                projected.y
            )
        } else {
            path.lineTo(
                projected.x,
                projected.y
            )
        }
    }
    path.close()
    drawPath(
        path = path,
        color = fill
    )
    drawPath(
        path = path,
        color = stroke,
        style = Stroke(width = 1.25f)
    )
}

private fun DrawScope.project(
    longitude: Double,
    latitude: Double
): Offset =
    Offset(
        x =
            size.width *
                (
                    (longitude + 180.0) /
                        360.0
                    ).toFloat(),
        y =
            size.height *
                (
                    (90.0 - latitude) /
                        180.0
                    ).toFloat()
    )

private val WORLD_SHAPES =
    listOf(
        listOf(
            -168.0 to 72.0,
            -145.0 to 70.0,
            -125.0 to 55.0,
            -105.0 to 50.0,
            -82.0 to 26.0,
            -97.0 to 16.0,
            -116.0 to 30.0,
            -130.0 to 47.0,
            -158.0 to 57.0
        ),
        listOf(
            -82.0 to 12.0,
            -66.0 to 8.0,
            -50.0 to -5.0,
            -36.0 to -11.0,
            -52.0 to -55.0,
            -70.0 to -48.0,
            -80.0 to -15.0
        ),
        listOf(
            -10.0 to 71.0,
            29.0 to 72.0,
            45.0 to 55.0,
            35.0 to 35.0,
            10.0 to 35.0,
            -10.0 to 45.0
        ),
        listOf(
            -18.0 to 37.0,
            15.0 to 37.0,
            50.0 to 12.0,
            42.0 to -35.0,
            20.0 to -35.0,
            -10.0 to 0.0
        ),
        listOf(
            28.0 to 72.0,
            178.0 to 69.0,
            160.0 to 51.0,
            145.0 to 35.0,
            120.0 to 20.0,
            100.0 to 8.0,
            75.0 to 20.0,
            55.0 to 31.0,
            35.0 to 40.0
        ),
        listOf(
            110.0 to -10.0,
            155.0 to -10.0,
            151.0 to -45.0,
            115.0 to -45.0
        ),
        listOf(
            -52.0 to 82.0,
            -20.0 to 75.0,
            -35.0 to 60.0,
            -60.0 to 61.0
        )
    )
