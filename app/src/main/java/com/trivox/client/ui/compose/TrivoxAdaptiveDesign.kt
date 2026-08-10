package com.trivox.client.ui.compose

// TRIVOX_V27_ADAPTIVE_CUPERTINO_UI

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

internal class ContinuousCornerShape private constructor(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize
) : CornerBasedShape(
    topStart = topStart,
    topEnd = topEnd,
    bottomEnd = bottomEnd,
    bottomStart = bottomStart
) {
    constructor(radius: Dp) : this(
        topStart = CornerSize(radius),
        topEnd = CornerSize(radius),
        bottomEnd = CornerSize(radius),
        bottomStart = CornerSize(radius)
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): CornerBasedShape = ContinuousCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart
    )

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        val maxRadius = min(size.width, size.height) / 2f

        val topLeft = (
            if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd
        ).coerceIn(0f, maxRadius)
        val topRight = (
            if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart
        ).coerceIn(0f, maxRadius)
        val bottomRight = (
            if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart
        ).coerceIn(0f, maxRadius)
        val bottomLeft = (
            if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd
        ).coerceIn(0f, maxRadius)

        if (
            topLeft <= 0f &&
            topRight <= 0f &&
            bottomRight <= 0f &&
            bottomLeft <= 0f
        ) {
            return Outline.Rectangle(
                androidx.compose.ui.geometry.Rect(
                    0f,
                    0f,
                    size.width,
                    size.height
                )
            )
        }

        fun control(radius: Float): Float = radius * 0.46f

        val topLeftControl = control(topLeft)
        val topRightControl = control(topRight)
        val bottomRightControl = control(bottomRight)
        val bottomLeftControl = control(bottomLeft)

        val path = Path().apply {
            moveTo(topLeft, 0f)

            lineTo(size.width - topRight, 0f)
            cubicTo(
                size.width - topRight + topRightControl,
                0f,
                size.width,
                topRight - topRightControl,
                size.width,
                topRight
            )

            lineTo(size.width, size.height - bottomRight)
            cubicTo(
                size.width,
                size.height - bottomRight + bottomRightControl,
                size.width - bottomRight + bottomRightControl,
                size.height,
                size.width - bottomRight,
                size.height
            )

            lineTo(bottomLeft, size.height)
            cubicTo(
                bottomLeft - bottomLeftControl,
                size.height,
                0f,
                size.height - bottomLeft + bottomLeftControl,
                0f,
                size.height - bottomLeft
            )

            lineTo(0f, topLeft)
            cubicTo(
                0f,
                topLeft - topLeftControl,
                topLeft - topLeftControl,
                0f,
                topLeft,
                0f
            )
            close()
        }

        return Outline.Generic(path)
    }
}

internal val TrivoxOuterShape: Shape = ContinuousCornerShape(24.dp)
internal val TrivoxInnerShape: Shape = ContinuousCornerShape(14.dp)
internal val TrivoxCompactShape: Shape = ContinuousCornerShape(8.dp)

internal object TrivoxUiTokens {
    val pagePadding = 14.dp
    val groupSpacing = 14.dp
    val rowSpacing = 10.dp
    val buttonShape: Shape = TrivoxInnerShape
    val buttonContentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    val systemBlueLight = Color(0xFF007AFF)
    val systemBlueDark = Color(0xFF0A84FF)
    val systemGreenLight = Color(0xFF34C759)
    val systemGreenDark = Color(0xFF30D158)
    val systemGray6Light = Color(0xFFF2F2F7)
    val systemGray6Dark = Color(0xFF1C1C1E)
}

@Composable
internal fun TrivoxGlassBar(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary
    val glassTop = lerp(surface, primary, 0.045f)
    val glassBottom = lerp(surface, primary, 0.018f)
    val edge = lerp(
        MaterialTheme.colorScheme.outlineVariant,
        primary,
        0.18f
    ).copy(alpha = 0.50f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        glassTop.copy(alpha = 0.90f),
                        glassBottom.copy(alpha = 0.78f)
                    )
                )
            )
            .drawBehind {
                drawLine(
                    color = edge,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Square
                )
            },
        content = content
    )
}

@Composable
internal fun TrivoxInsetGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = TrivoxOuterShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.90f)
            ),
            shadowElevation = 1.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun TrivoxToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // trivox-toggle-v33: crisper 1dp edge, fully rounded track and thumb
    val thumbOffset = animateDpAsState(
        targetValue = if (checked) 25.dp else 3.dp,
        animationSpec = spring(
            dampingRatio = 0.74f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "trivox-toggle-thumb"
    )
    val on = if (MaterialTheme.colorScheme.background == Color.Black) {
        TrivoxUiTokens.systemGreenDark
    } else {
        TrivoxUiTokens.systemGreenLight
    }
    val off = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.86f)
    val edge = if (checked) {
        on
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }

    Box(
        modifier = modifier
            .size(width = 54.dp, height = 32.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .background(color = edge, shape = ContinuousCornerShape(16.dp))
            .padding(1.dp)
            .background(
                color = if (checked) on else off,
                shape = ContinuousCornerShape(15.dp)
            )
            .toggleable(
                value = checked,
                enabled = enabled && onCheckedChange != null,
                role = Role.Switch,
                onValueChange = { onCheckedChange?.invoke(it) }
            )
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset.value, y = 3.dp)
                .size(24.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = ContinuousCornerShape(12.dp),
                    clip = false
                )
                .background(Color.White, ContinuousCornerShape(12.dp))
        )
    }
}

@Composable
internal fun Modifier.trivoxSpringPress(
    enabled: Boolean = true
): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.968f else 1f,
        animationSpec = spring(
            dampingRatio = 0.58f,
            stiffness = 620f
        ),
        label = "trivox-press-scale"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (!enabled) {
                pressed = false
                return@pointerInput
            }

            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                try {
                    waitForUpOrCancellation()
                } finally {
                    pressed = false
                }
            }
        }
}

@Composable
internal fun TrivoxAutoFitButtonText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 15.sp,
    minFontSize: TextUnit = 9.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.labelLarge
) {
    BoxWithConstraints(modifier = modifier) {
        var resolvedSize by remember(text, maxWidth, maxFontSize, minFontSize) {
            mutableStateOf(maxFontSize)
        }
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = style,
            fontSize = resolvedSize,
            fontWeight = fontWeight,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if ((result.didOverflowWidth || result.didOverflowHeight) && resolvedSize.value > minFontSize.value) {
                    resolvedSize = (resolvedSize.value - 0.75f).coerceAtLeast(minFontSize.value).sp
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberTrivoxTopBarScrollBehavior(): TopAppBarScrollBehavior =
    TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.trivoxNestedScroll(
    behavior: TopAppBarScrollBehavior
): Modifier = nestedScroll(behavior.nestedScrollConnection)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrivoxLargeTopBar(
    title: String,
    previousTitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior
) {
    /*
     * Material3 LargeTopAppBar keeps internal large-title spacing even when
     * very small height values are supplied. Own the geometry directly so
     * AppCompat already positions this Compose host below the real system status bar.
     * Never add the same inset a second time.
     */
    val expandedHeight = 34.dp
    val collapsedHeight = 28.dp
    val density = LocalDensity.current

    SideEffect {
        scrollBehavior.state.heightOffsetLimit = -with(density) {
            (expandedHeight - collapsedHeight).toPx()
        }
    }

    val collapsed =
        scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
    val currentHeight =
        expandedHeight -
            ((expandedHeight - collapsedHeight) * collapsed)

    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary
    val glassSurface = lerp(surface, primary, 0.035f)
    val glassScrolledSurface = lerp(surface, primary, 0.060f)
    val barSurface = lerp(
        glassSurface,
        glassScrolledSurface,
        collapsed
    )
    val glassEdge = lerp(
        MaterialTheme.colorScheme.outlineVariant,
        primary,
        0.18f
    ).copy(alpha = 0.78f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barSurface.copy(alpha = 0.94f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(currentHeight)
                .drawBehind {
                    drawLine(
                        color = glassEdge,
                        start = androidx.compose.ui.geometry.Offset(
                            0f,
                            size.height - 0.5f
                        ),
                        end = androidx.compose.ui.geometry.Offset(
                            size.width,
                            size.height - 0.5f
                        ),
                        strokeWidth = 1.dp.toPx()
                    )
                }
        ) {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                    contentPadding = PaddingValues(
                        horizontal = 10.dp,
                        vertical = 0.dp
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "‹",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        previousTitle
                            ?.takeIf(String::isNotBlank)
                            ?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                    }
                }
            }

            Text(
                text = title,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(
                        start = if (onBack == null) 24.dp else 104.dp,
                        end = 24.dp,
                        top = 4.dp,
                        bottom = 4.dp
                    )
                    .graphicsLayer {
                        alpha =
                            (1f - collapsed * 1.45f)
                                .coerceIn(0f, 1f)
                        val scale = 1f - (0.040f * collapsed)
                        scaleX = scale
                        scaleY = scale
                    },
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 28.sp,
                    lineHeight = 32.sp
                ),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = title,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 8.dp)
                    .graphicsLayer {
                        alpha =
                            ((collapsed - 0.18f) / 0.82f)
                                .coerceIn(0f, 1f)
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
