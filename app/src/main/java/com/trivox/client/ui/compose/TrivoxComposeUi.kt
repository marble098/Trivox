package com.trivox.client.ui.compose

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun TrivoxTheme(activity: Activity, content: @Composable () -> Unit) {
    val dark = remember(activity) {
        SettingsRepository(activity).load().themeMode == ThemeMode.DARK
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF7AA2FF),
            secondary = androidx.compose.ui.graphics.Color(0xFF72D6C9),
            tertiary = androidx.compose.ui.graphics.Color(0xFFB9A0FF),
            background = androidx.compose.ui.graphics.Color(0xFF0D1117),
            surface = androidx.compose.ui.graphics.Color(0xFF121821),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1B2430)
        )
    } else {
        lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF2859C5),
            secondary = androidx.compose.ui.graphics.Color(0xFF006B61),
            tertiary = androidx.compose.ui.graphics.Color(0xFF65558F),
            background = androidx.compose.ui.graphics.Color(0xFFF7F9FC),
            surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEAF0F8)
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = androidx.compose.material3.Typography(
            bodyLarge = TextStyle(fontFamily = TrivoxFont, fontSize = 14.sp),
            bodyMedium = TextStyle(fontFamily = TrivoxFont, fontSize = 12.sp),
            bodySmall = TextStyle(fontFamily = TrivoxFont, fontSize = 11.sp),
            titleLarge = TextStyle(fontFamily = TrivoxFont, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            titleMedium = TextStyle(fontFamily = TrivoxFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
            labelLarge = TextStyle(fontFamily = TrivoxFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        ),
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
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(legacy) {
        while (true) {
            delay(750)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick
    val children = remember(tick) { flattenControls(legacy) }
    val toolbar = children.filterIsInstance<Toolbar>().firstOrNull()
    val title = toolbar?.title?.toString()?.takeIf { it.isNotBlank() } ?: fallbackTitle
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = { activity.finish() }) { Text("‹") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            children.forEach { view ->
                if (view !is Toolbar && view.visibility == View.VISIBLE) {
                    LegacyControl(view)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun flattenControls(root: ViewGroup): List<View> {
    val out = ArrayList<View>()
    for (i in 0 until root.childCount) {
        val child = root.getChildAt(i)
        out += child
        if (child is ViewGroup && child !is Spinner && child.visibility == View.VISIBLE) {
            out += flattenControls(child)
        }
    }
    return out
}

@Composable
private fun LegacyControl(view: View) {
    when (view) {
        is SwitchCompat -> LegacySwitch(view)
        is CheckBox -> LegacyCheck(view)
        is EditText -> LegacyEdit(view)
        is Spinner -> LegacySpinner(view)
        is Button -> LegacyButton(view)
        is TextView -> LegacyText(view)
        else -> Unit
    }
}

@Composable
private fun LegacyText(view: TextView) {
    val value = view.text?.toString().orEmpty()
    if (value.isBlank()) return
    val prominent = view.id == R.id.stateText || view.id == R.id.protocolValue
    Text(
        text = value,
        style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = if (prominent) 6.dp else 1.dp)
    )
}

@Composable
private fun LegacyButton(view: Button) {
    val label = view.text?.toString().orEmpty().ifBlank { "Action" }
    M3Button(
        onClick = { view.performClick() },
        enabled = view.isEnabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text(label) }
}

@Composable
private fun LegacyEdit(view: EditText) {
    var value by remember(view) { mutableStateOf(view.text?.toString().orEmpty()) }
    LaunchedEffect(view.text?.toString()) {
        val now = view.text?.toString().orEmpty()
        if (now != value) value = now
    }
    val label = view.hint?.toString().orEmpty()
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
        singleLine = !label.contains("DNS", true) && !label.contains("JSON", true),
        enabled = view.isEnabled,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LegacySwitch(view: SwitchCompat) {
    var checked by remember(view) { mutableStateOf(view.isChecked) }
    LaunchedEffect(view.isChecked) {
        if (checked != view.isChecked) checked = view.isChecked
    }
    val label = view.text?.toString().orEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = view.isEnabled) {
            checked = !checked
            view.isChecked = checked
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = {
                checked = it
                view.isChecked = it
            }, enabled = view.isEnabled)
        }
    }
}

@Composable
private fun LegacyCheck(view: CompoundButton) {
    var checked by remember(view) { mutableStateOf(view.isChecked) }
    LaunchedEffect(view.isChecked) {
        if (checked != view.isChecked) checked = view.isChecked
    }
    val label = (view as? TextView)?.text?.toString().orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = view.isEnabled) {
            checked = !checked
            view.isChecked = checked
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = {
            checked = it
            view.isChecked = it
        }, enabled = view.isEnabled)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LegacySpinner(view: Spinner) {
    var expanded by remember { mutableStateOf(false) }
    val adapter = view.adapter
    val count = adapter?.count ?: 0
    val selected = view.selectedItem?.toString().orEmpty().ifBlank { "Select" }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().clickable(enabled = view.isEnabled && count > 0) { expanded = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selected, modifier = Modifier.weight(1f))
                Text("⌄")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            repeat(count) { index ->
                DropdownMenuItem(
                    text = { Text(adapter?.getItem(index)?.toString().orEmpty()) },
                    onClick = {
                        expanded = false
                        view.setSelection(index)
                    }
                )
            }
        }
    }
}
