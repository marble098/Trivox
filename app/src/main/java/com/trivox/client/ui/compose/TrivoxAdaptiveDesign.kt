package com.trivox.client.ui.compose

// TRIVOX_V27_ADAPTIVE_CUPERTINO_UI

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
internal val TrivoxInnerShape: Shape = ContinuousCornerShape(16.dp)
internal val TrivoxCompactShape: Shape = ContinuousCornerShape(12.dp)

internal object TrivoxUiTokens {
    val pagePadding = 16.dp
    val groupSpacing = 16.dp
    val rowSpacing = 12.dp
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
                    strokeWidth = 1f,
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
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)
            ),
            shadowElevation = 0.dp,
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
    val thumbOffset = animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = 0.78f,
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

    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.48f }
            .background(
                color = if (checked) on else off,
                shape = ContinuousCornerShape(16.dp)
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
                .offset(x = thumbOffset.value, y = 2.dp)
                .size(27.dp)
                .background(Color.White, ContinuousCornerShape(14.dp))
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
    val collapsed = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary
    val glassSurface = lerp(surface, primary, 0.035f)
    val glassScrolledSurface = lerp(surface, primary, 0.055f)
    val glassEdge = lerp(
        MaterialTheme.colorScheme.outlineVariant,
        primary,
        0.16f
    ).copy(alpha = 0.44f)

    LargeTopAppBar(
        modifier = Modifier.drawBehind {
            drawLine(
                color = glassEdge,
                start = androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f),
                strokeWidth = 1f
            )
        },
        // v28.2: device-tested compact geometry. Keep status-bar safety
        // and the large-title transition, but remove the remaining tall
        // header in both expanded and collapsed states.
        collapsedHeight = 44.dp,
        expandedHeight = 56.dp,
        title = {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            alpha = (1f - collapsed * 1.35f).coerceIn(0f, 1f)
                            val scale = 1f - (0.08f * collapsed)
                            scaleX = scale
                            scaleY = scale
                        },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            alpha = ((collapsed - 0.22f) / 0.78f).coerceIn(0f, 1f)
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
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
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = glassSurface.copy(alpha = 0.86f),
            scrolledContainerColor = glassScrolledSurface.copy(alpha = 0.94f),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.primary
        ),
        scrollBehavior = scrollBehavior
    )
}
