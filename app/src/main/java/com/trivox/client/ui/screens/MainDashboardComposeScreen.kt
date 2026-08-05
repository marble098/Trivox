package com.trivox.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.CoreId
import com.trivox.client.data.TestStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardComposeScreen(
    connectionState: ConnectionState,
    activeCore: CoreId,
    smartCoreEnabled: Boolean,
    activeProfile: ConfigProfile?,
    profiles: List<ConfigProfile>,
    connectedTimer: String,
    onToggleConnection: () -> Unit,
    onSelectProfile: (ConfigProfile) -> Unit,
    onTcpPingCore: (CoreId) -> Unit,
    onRealDelayCore: (CoreId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> Color(0xFF00E676)
            ConnectionState.CONNECTING, ConnectionState.PREPARING -> Color(0xFFFFB300)
            ConnectionState.ERROR -> Color(0xFFFF5252)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "statusColor"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("تریواکس", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                if (smartCoreEnabled) "هوشمند (${activeCore.label})" else activeCore.label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSubscriptions) {
                        Icon(Icons.Default.Subscriptions, contentDescription = "اشتراک‌ها")
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Default.Build, contentDescription = "عیب‌یابی")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "تنظیمات")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Connection Status Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.15f))
                            .clickable { onToggleConnection() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (connectionState == ConnectionState.CONNECTED) Icons.Default.PowerSettingsNew else Icons.Default.PowerOff,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = when (connectionState) {
                            ConnectionState.CONNECTED -> "متصل شد"
                            ConnectionState.CONNECTING -> "در حال اتصال..."
                            ConnectionState.PREPARING -> "آماده‌سازی هسته..."
                            ConnectionState.ERROR -> "خطا در اتصال"
                            else -> "قطع شده"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = statusColor
                    )

                    if (connectionState == ConnectionState.CONNECTED) {
                        Text(
                            text = connectedTimer,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    activeProfile?.let { profile ->
                        Text(
                            text = profile.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // Core-Specific Delay Action Buttons connected to Smart Selector or Preferred Core
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onTcpPingCore(activeCore) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تست TCP (${activeCore.label})", fontSize = 12.sp)
                }

                Button(
                    onClick = { onRealDelayCore(activeCore) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تاخیر واقعی (${activeCore.label})", fontSize = 12.sp)
                }
            }

            Text("کانفیگ‌های فعال (${profiles.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCardItem(
                        profile = profile,
                        isSelected = activeProfile?.id == profile.id,
                        onClick = { onSelectProfile(profile) }
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileCardItem(
    profile: ConfigProfile,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${profile.protocol.uppercase()} • ${profile.server}:${profile.port}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            profile.realLatencyMs?.let { ms ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (ms < 300) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFFFFB300).copy(alpha = 0.2f)
                ) {
                    Text(
                        "${ms}ms",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (ms < 300) Color(0xFF00E676) else Color(0xFFFFB300)
                    )
                }
            }
        }
    }
}
