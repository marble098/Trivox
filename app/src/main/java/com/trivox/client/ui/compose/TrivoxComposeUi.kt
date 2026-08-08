package com.trivox.client.ui.compose

// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trivox.client.R
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.ThemeMode
import kotlinx.coroutines.delay

private val TrivoxFont = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

private val TrivoxTypography = Typography(
    bodyLarge = TextStyle(fontFamily = TrivoxFont, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = TrivoxFont, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = TrivoxFont, fontSize = 11.sp, lineHeight = 16.sp),
    titleLarge = TextStyle(fontFamily = TrivoxFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = TrivoxFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = TrivoxFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = TrivoxFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelMedium = TextStyle(fontFamily = TrivoxFont, fontWeight = FontWeight.Medium, fontSize = 11.sp)
)

private val TrivoxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TrivoxTheme(activity: Activity, content: @Composable () -> Unit) {
    val themeMode = SettingsRepository(activity).load().themeMode
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF9AB7FF),
            onPrimary = Color(0xFF002E6D),
            primaryContainer = Color(0xFF1B417C),
            onPrimaryContainer = Color(0xFFD9E2FF),
            secondary = Color(0xFF82D5CA),
            onSecondary = Color(0xFF003731),
            secondaryContainer = Color(0xFF164E48),
            onSecondaryContainer = Color(0xFFB9F0E8),
            background = Color(0xFF090D14),
            onBackground = Color(0xFFE3E7EF),
            surface = Color(0xFF0F141D),
            onSurface = Color(0xFFE3E7EF),
            surfaceVariant = Color(0xFF202733),
            onSurfaceVariant = Color(0xFFC3C9D3),
            surfaceContainer = Color(0xFF151B25),
            surfaceContainerHigh = Color(0xFF1B222D),
            outline = Color(0xFF8B929D),
            outlineVariant = Color(0xFF3B424C),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF275DB8),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD9E5FF),
            onPrimaryContainer = Color(0xFF001A41),
            secondary = Color(0xFF006A60),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFF9FF2E4),
            onSecondaryContainer = Color(0xFF00201C),
            background = Color(0xFFF7F9FC),
            onBackground = Color(0xFF181C22),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF181C22),
            surfaceVariant = Color(0xFFE1E7F0),
            onSurfaceVariant = Color(0xFF424750),
            surfaceContainer = Color(0xFFF0F3F8),
            surfaceContainerHigh = Color(0xFFE9EDF3),
            outline = Color(0xFF737984),
            outlineVariant = Color(0xFFC3C8D0),
            error = Color(0xFFBA1A1A),
            onError = Color.White
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = TrivoxTypography,
        shapes = TrivoxShapes,
        content = content
    )
}

fun ComposeView.showLegacyMirror(activity: Activity, legacy: ViewGroup, fallbackTitle: String) {
    setContent {
        TrivoxTheme(activity) {
            LegacyMirrorScreen(activity, legacy, fallbackTitle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyMirrorScreen(activity: Activity, legacy: ViewGroup, fallbackTitle: String) {
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(legacy) {
        while (true) {
            delay(1_250)
            revision++
        }
    }

    val toolbar = findToolbar(legacy)
    val title = toolbar?.title?.toString()?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val footerSave = (0 until legacy.childCount)
        .map(legacy::getChildAt)
        .filterIsInstance<Button>()
        .firstOrNull { it.id == R.id.saveButton && it.visibility == View.VISIBLE }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = { activity.finish() }) { Text("‹") }
                }
            )
        },
        bottomBar = {
            if (footerSave != null) {
                Surface(tonalElevation = 2.dp) {
                    M3Button(
                        onClick = { if (footerSave.isEnabled) footerSave.performClick() },
                        enabled = footerSave.isEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(footerSave.text?.toString().orEmpty().ifBlank { activity.getString(R.string.save) })
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RenderChildren(
                legacy,
                revision,
                topLevel = true,
                excludedIds = footerSave?.let { setOf(it.id) }.orEmpty()
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

private fun findToolbar(root: ViewGroup): Toolbar? {
    for (index in 0 until root.childCount) {
        val child = root.getChildAt(index)
        if (child is Toolbar) return child
        if (child is ViewGroup) findToolbar(child)?.let { return it }
    }
    return null
}

@Composable
private fun RenderChildren(
    group: ViewGroup,
    revision: Int,
    topLevel: Boolean = false,
    excludedIds: Set<Int> = emptySet()
) {
    val visible = (0 until group.childCount)
        .map(group::getChildAt)
        .filter { it.visibility == View.VISIBLE && it !is Toolbar && it.id !in excludedIds }

    visible.forEachIndexed { index, child ->
        when (child) {
            is ScrollView -> RenderChildren(child, revision, excludedIds = excludedIds)
            is LinearLayout -> LegacyLinearGroup(child, revision, topLevel)
            is FrameLayout -> RenderChildren(child, revision, excludedIds = excludedIds)
            is ViewGroup -> RenderChildren(child, revision, excludedIds = excludedIds)
            else -> LegacyControl(child, revision, Modifier.fillMaxWidth())
        }
        if (topLevel && index != visible.lastIndex) {
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun LegacyLinearGroup(group: LinearLayout, revision: Int, parentTopLevel: Boolean) {
    val children = (0 until group.childCount)
        .map(group::getChildAt)
        .filter { it.visibility == View.VISIBLE && it !is Toolbar }
    if (children.isEmpty()) return

    if (group.orientation == LinearLayout.HORIZONTAL && children.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            children.forEach { child ->
                val childModifier = if (child is TextView && child !is EditText && child !is Button) {
                    Modifier
                } else {
                    Modifier.weight(1f)
                }
                if (child is ViewGroup && child !is Spinner) {
                    Box(childModifier) { RenderChildren(child, revision) }
                } else {
                    LegacyControl(child, revision, childModifier)
                }
            }
        }
    } else {
        val decorate = children.size in 2..30 &&
            children.any { it is EditText || it is Spinner || it is CheckBox || it is SwitchCompat || it is Button }
        if (decorate) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    children.forEach { child ->
                        if (child is ViewGroup && child !is Spinner) {
                            when (child) {
                                is LinearLayout -> LegacyLinearGroup(child, revision, false)
                                else -> RenderChildren(child, revision)
                            }
                        } else {
                            LegacyControl(child, revision, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                children.forEach { child ->
                    if (child is ViewGroup && child !is Spinner) {
                        when (child) {
                            is LinearLayout -> LegacyLinearGroup(child, revision, false)
                            else -> RenderChildren(child, revision)
                        }
                    } else {
                        LegacyControl(child, revision, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun LegacyControl(view: View, revision: Int, modifier: Modifier = Modifier) {
    if (view.visibility != View.VISIBLE) return
    when (view) {
        is SwitchCompat -> LegacySwitch(view, revision, modifier)
        is CheckBox -> LegacyCheck(view, revision, modifier)
        is EditText -> LegacyEdit(view, revision, modifier)
        is Spinner -> LegacySpinner(view, revision, modifier)
        is Button -> LegacyButton(view, revision, modifier)
        is TextView -> LegacyText(view, revision, modifier)
        is ViewGroup -> RenderChildren(view, revision)
        else -> Unit
    }
}

@Composable
private fun LegacyText(view: TextView, revision: Int, modifier: Modifier) {
    @Suppress("UNUSED_EXPRESSION")
    revision
    val value = view.text?.toString().orEmpty()
    if (value.isBlank()) return

    val prominent = view.id == R.id.stateText || view.id == R.id.protocolValue
    val likelyHeading = !view.isClickable && value.length <= 44 && (
        value.endsWith(":") ||
            value in legacySectionTitles(view)
        )

    Text(
        text = value,
        style = when {
            prominent -> MaterialTheme.typography.titleMedium
            likelyHeading -> MaterialTheme.typography.titleSmall
            else -> MaterialTheme.typography.bodyMedium
        },
        fontWeight = if (likelyHeading || prominent) FontWeight.SemiBold else FontWeight.Normal,
        color = if (likelyHeading) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (likelyHeading) 2 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = if (likelyHeading) 5.dp else 0.dp)
    )
    if (likelyHeading) HorizontalDivider(Modifier.padding(top = 3.dp))
}

private fun legacySectionTitles(view: TextView): Set<String> {
    val c = view.context
    return listOfNotNull(
        safeString(c, R.string.interface_section),
        safeString(c, R.string.profile_sorting_section),
        safeString(c, R.string.ping_section),
        safeString(c, R.string.local_proxy_section),
        safeString(c, R.string.vpn_section),
        safeString(c, R.string.dns_section),
        safeString(c, R.string.reconnect_section),
        safeString(c, R.string.network_tuning_section),
        safeString(c, R.string.wireguard_section_v9),
        safeString(c, R.string.update_section),
        safeString(c, R.string.about_section),
        safeString(c, R.string.basic_settings),
        safeString(c, R.string.transport_settings),
        safeString(c, R.string.security_settings)
    ).toSet()
}

private fun safeString(context: android.content.Context, id: Int): String? =
    runCatching { context.getString(id) }.getOrNull()

@Composable
private fun LegacyButton(view: Button, revision: Int, modifier: Modifier) {
    @Suppress("UNUSED_EXPRESSION")
    revision
    val label = view.text?.toString().orEmpty().ifBlank { "Action" }
    M3Button(
        onClick = { if (view.isEnabled) view.performClick() },
        enabled = view.isEnabled,
        modifier = modifier
    ) {
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LegacyEdit(view: EditText, revision: Int, modifier: Modifier) {
    var value by remember(view) { mutableStateOf(view.text?.toString().orEmpty()) }
    LaunchedEffect(revision) {
        val now = view.text?.toString().orEmpty()
        if (now != value && !view.hasFocus()) value = now
    }
    val label = view.hint?.toString().orEmpty()
    val multiline = label.contains("DNS", true) || label.contains("JSON", true) || view.minLines > 1

    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            if (view.text?.toString() != it) {
                view.setText(it)
                view.setSelection(view.text?.length ?: 0)
            }
        },
        label = if (label.isNotBlank()) { { Text(label) } } else null,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        enabled = view.isEnabled,
        modifier = modifier
    )
}

@Composable
private fun LegacySwitch(view: SwitchCompat, revision: Int, modifier: Modifier) {
    var checked by remember(view) { mutableStateOf(view.isChecked) }
    LaunchedEffect(revision) {
        if (checked != view.isChecked) checked = view.isChecked
    }
    val label = view.text?.toString().orEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(15.dp),
        modifier = modifier.clickable(enabled = view.isEnabled) {
            checked = !checked
            view.isChecked = checked
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 2)
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    view.isChecked = it
                },
                enabled = view.isEnabled
            )
        }
    }
}

@Composable
private fun LegacyCheck(view: CompoundButton, revision: Int, modifier: Modifier) {
    var checked by remember(view) { mutableStateOf(view.isChecked) }
    LaunchedEffect(revision) {
        if (checked != view.isChecked) checked = view.isChecked
    }
    val label = (view as? TextView)?.text?.toString().orEmpty()

    Row(
        modifier = modifier
            .clickable(enabled = view.isEnabled) {
                checked = !checked
                view.isChecked = checked
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = {
                checked = it
                view.isChecked = it
            },
            enabled = view.isEnabled
        )
        Text(label, modifier = Modifier.weight(1f), maxLines = 3)
    }
}

@Composable
private fun LegacySpinner(view: Spinner, revision: Int, modifier: Modifier) {
    var expanded by remember(view) { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION")
    revision
    val adapter = view.adapter
    val count = adapter?.count ?: 0
    val selectedIndex = view.selectedItemPosition.coerceIn(0, (count - 1).coerceAtLeast(0))
    val selected = view.selectedItem?.toString().orEmpty().ifBlank { "Select" }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.clickable(enabled = view.isEnabled && count > 0) { expanded = true }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(selected) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    repeat(count) { index ->
                        val label = adapter?.getItem(index)?.toString().orEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = false
                                    view.setSelection(index)
                                }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = {
                                    expanded = false
                                    view.setSelection(index)
                                }
                            )
                            Text(label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) {
                    Text(view.context.getString(R.string.cancel))
                }
            }
        )
    }
}
