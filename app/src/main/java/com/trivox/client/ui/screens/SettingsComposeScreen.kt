package com.trivox.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trivox.client.data.*

data class AboutInfo(
    val appVersion: String,
    val buildNumber: Int,
    val gitSha: String,
    val xrayVersion: String,
    val singBoxVersion: String,
    val mihomoVersion: String,
    val packageName: String,
    val signingSha: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsComposeScreen(
    initialSettings: AppSettings,
    aboutInfo: AboutInfo,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onManageSubscriptions: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    var settings by remember { mutableStateOf(initialSettings.copy()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("عمومی", "هسته و پینگ", "شبکه و تونل", "درباره سازندگان")
    val tabIcons = listOf(
        Icons.Default.Tune,
        Icons.Default.Speed,
        Icons.Default.Router,
        Icons.Default.Info
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات تریواکس", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(settings) }) {
                        Icon(Icons.Default.Check, contentDescription = "ذخیره", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("انصراف")
                    }
                    Button(
                        onClick = { onSave(settings) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ذخیره تنظیمات")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                title,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = {
                            Icon(tabIcons[index], contentDescription = title, modifier = Modifier.size(20.dp))
                        }
                    )
                }
            }

            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedTab) {
                    0 -> GeneralSettingsTab(settings = settings, onUpdateSettings = { settings = it }, onManageSubscriptions = onManageSubscriptions)
                    1 -> CoreAndPingTab(settings = settings, onUpdateSettings = { settings = it })
                    2 -> NetworkAndTunnelTab(settings = settings, onUpdateSettings = { settings = it })
                    3 -> AboutTab(aboutInfo = aboutInfo, onCheckUpdate = onCheckUpdate)
                }
            }
        }
    }
}

@Composable
fun GeneralSettingsTab(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    onManageSubscriptions: () -> Unit
) {
    CardContainer(title = "تنظیمات عمومی و ظاهر") {
        SettingSwitchItem(
            title = "حالت تاریک (Dark Mode)",
            subtitle = "تغییر تم برنامه به حالت تاریک با کنتراست بالا",
            icon = Icons.Default.DarkMode,
            checked = settings.darkMode,
            onCheckedChange = { onUpdateSettings(settings.copy(darkMode = it, themeMode = if (it) ThemeMode.DARK else ThemeMode.LIGHT)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        SettingSwitchItem(
            title = "پنهان کردن IP در صفحه اصلی",
            subtitle = "عدم نمایش IP خروجی برای حفظ حریم خصوصی",
            icon = Icons.Default.VisibilityOff,
            checked = settings.hideIpOnMain,
            onCheckedChange = { onUpdateSettings(settings.copy(hideIpOnMain = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        SettingSwitchItem(
            title = "اتصال مجدد با تغییر شبکه",
            subtitle = "تلاش مجدد برای برقراری اتصال با تغییر شبکه اینترنت",
            icon = Icons.Default.NetworkCheck,
            checked = settings.reconnectOnNetworkChange,
            onCheckedChange = { onUpdateSettings(settings.copy(reconnectOnNetworkChange = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        SettingSwitchItem(
            title = "اتصال خودکار پس از روشن شدن دستگاه",
            subtitle = "شروع سرویس هنگام بوت شدن سیستم‌عامل",
            icon = Icons.Default.PowerSettingsNew,
            checked = settings.reconnectOnBoot,
            onCheckedChange = { onUpdateSettings(settings.copy(reconnectOnBoot = it)) }
        )
    }

    CardContainer(title = "مدیریت اشتراک‌ها") {
        Button(
            onClick = onManageSubscriptions,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Subscriptions, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("مدیریت لینک‌های اشتراک (Subscription List)")
        }
    }
}

@Composable
fun CoreAndPingTab(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit
) {
    CardContainer(title = "انتخابگر هوشمند هسته (Smart Core Selector)") {
        SettingSwitchItem(
            title = "فعال‌سازی انتخاب هوشمند هسته",
            subtitle = "ارزیابی همزمان هسته‌ها بر اساس پروتکل، سرعت استارت و پینگ واقعی HTTPS",
            icon = Icons.Default.Psychology,
            checked = settings.smartCoreSelection,
            onCheckedChange = { onUpdateSettings(settings.copy(smartCoreSelection = it)) }
        )

        AnimatedVisibility(visible = !settings.smartCoreSelection) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text("هسته ترجیحی دستی (Manual Core):", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoreId.entries.forEach { core ->
                        FilterChip(
                            selected = settings.coreId == core,
                            onClick = { onUpdateSettings(settings.copy(coreId = core)) },
                            label = { Text(core.label) }
                        )
                    }
                }
            }
        }
    }

    CardContainer(title = "هسته ترجیحی برای تست تاخیر (Preferred Test Core)") {
        Text("در صورت غیرفعال بودن انتخابگر هوشمند، این هسته برای تست‌های TCP و تاخیر واقعی استفاده می‌شود:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoreId.entries.forEach { core ->
                FilterChip(
                    selected = settings.preferredTestCore == core,
                    onClick = { onUpdateSettings(settings.copy(preferredTestCore = core)) },
                    label = { Text("تست با ${core.label}") }
                )
            }
        }
    }

    CardContainer(title = "تنظیمات تست تاخیر و پینگ زنده") {
        SettingSwitchItem(
            title = "پینگ زنده خودکار",
            subtitle = "بررسی مرتب تاخیر در حین اتصال",
            icon = Icons.Default.Timeline,
            checked = settings.livePingEnabled,
            onCheckedChange = { onUpdateSettings(settings.copy(livePingEnabled = it)) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = settings.testUrl,
            onValueChange = { onUpdateSettings(settings.copy(testUrl = it)) },
            label = { Text("آدرس URL تست تاخیر واقعی") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
    }
}

@Composable
fun NetworkAndTunnelTab(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit
) {
    CardContainer(title = "تنظیمات پورت و پراکسی محلی") {
        OutlinedTextField(
            value = settings.socksPort.toString(),
            onValueChange = { input -> input.toIntOrNull()?.let { onUpdateSettings(settings.copy(socksPort = it, httpPort = it)) } },
            label = { Text("پورت ترکیبی (SOCKS / HTTP Port)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSwitchItem(
            title = "پراکسی محلی درون VPN",
            subtitle = "اجازه دسترسی سایر برنامه‌ها به پورت پراکسی محلی",
            icon = Icons.Default.Lan,
            checked = settings.localProxyInVpn,
            onCheckedChange = { onUpdateSettings(settings.copy(localProxyInVpn = it)) }
        )
    }

    CardContainer(title = "بهینه‌سازی شبکه و TCP Fast Open") {
        SettingSwitchItem(
            title = "بهینه‌سازی پارامترهای شبکه (Network Tuning)",
            subtitle = "افزایش سرعت هندشیک و بافر اتصالات SOCKS و TUN",
            icon = Icons.Default.FlashOn,
            checked = settings.networkTuningEnabled,
            onCheckedChange = { onUpdateSettings(settings.copy(networkTuningEnabled = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        SettingSwitchItem(
            title = "TCP Fast Open",
            subtitle = "ارسال دیتای اولیه در حین هندشیک TCP جهت کاهش تاخیر اولیه",
            icon = Icons.Default.Speed,
            checked = settings.tcpFastOpen,
            onCheckedChange = { onUpdateSettings(settings.copy(tcpFastOpen = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        SettingSwitchItem(
            title = "پشتیبانی از IPv6",
            subtitle = "هدایت ترافیک IPv6 در صورت پشتیبانی سرور",
            icon = Icons.Default.AltRoute,
            checked = settings.ipv6,
            onCheckedChange = { onUpdateSettings(settings.copy(ipv6 = it)) }
        )
    }
}

@Composable
fun AboutTab(
    aboutInfo: AboutInfo,
    onCheckUpdate: () -> Unit
) {
    CardContainer(title = "توسعه‌دهندگان و سازندگان") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Icon(
                Icons.Default.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("تیم توسعه تریواکس (Trivox Team)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("توسعه‌یافته بر پایه آخرین تکنولوژی‌های کلاینت شبکه و چند هسته‌ای", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    CardContainer(title = "اطلاعات نسخه برنامه و هسته‌ها") {
        AboutInfoRow("نسخه برنامه:", "${aboutInfo.appVersion} (${aboutInfo.buildNumber})")
        AboutInfoRow("شناسه ساخت (Git SHA):", aboutInfo.gitSha)
        AboutInfoRow("بسته نرم‌افزاری (Package):", aboutInfo.packageName)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        AboutInfoRow("هسته Xray Core:", aboutInfo.xrayVersion)
        AboutInfoRow("هسته Sing-box Core:", aboutInfo.singBoxVersion)
        AboutInfoRow("هسته Mihomo (Clash Meta):", aboutInfo.mihomoVersion)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        AboutInfoRow("امضای دیجیتال (SHA-256):", aboutInfo.signingSha, isSmall = true)

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCheckUpdate,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("بررسی بروزرسانی نسخه جدید")
        }
    }
}

@Composable
fun CardContainer(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SettingSwitchItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun AboutInfoRow(label: String, value: String, isSmall: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (isSmall) 10.sp else 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
