package com.example.myvpn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── الألوان (نفس روح التصميم المطلوب) ─────────────────────────
private val Teal = Color(0xFF00897B)
private val Mint = Color(0xFF1DE9B6)
private val Ink = Color(0xFF0F2B30)
private val PageBg = Color(0xFFF2F6F7)
private val FieldBg = Color(0xFFE4ECEC)
private val TextMain = Color(0xFF1F2933)
private val TextSub = Color(0xFF5F6B73)
private val Purple = Color(0xFF5B16D0)
private val Red = Color(0xFFD32F2F)
private val Green = Color(0xFF2E9E5B)
private val Amber = Color(0xFFF59E0B)

private val AppColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Teal,
    background = PageBg,
    onBackground = TextMain,
    surface = Color.White,
    onSurface = TextMain,
    surfaceVariant = FieldBg,
    onSurfaceVariant = TextSub,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE9EEEE),
    outline = Color(0xFF9AA7AD),
    error = Red
)

class MainActivity : ComponentActivity() {

    private val vm: VpnViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) vm.connect() else vm.onPermissionDenied()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdsManager.init(this)
        AdsManager.onTimeExpired = { vm.disconnect() } // انتهى الوقت: قطع الـ VPN
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        window.statusBarColor = Mint.toArgb()
        window.navigationBarColor = Mint.toArgb()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = AppColors) {
                    Surface(Modifier.fillMaxSize(), color = PageBg) {
                        OnTunnelApp(vm, onToggle = ::toggle)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AdsManager.refresh()
    }

    private fun toggle() {
        if (vm.status == VpnStatus.CONNECTED) {
            vm.disconnect()
            AdsManager.onUserDisconnected(this)
        } else {
            if (!AdsManager.canConnect(this)) return // انتهى الوقت
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) permissionLauncher.launch(intent) else vm.connect()
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

// ───────────────────────── الهيكل العام ─────────────────────────
@Composable
fun OnTunnelApp(vm: VpnViewModel, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf("") }
    var dnsTick by remember { mutableStateOf(0) }
    var showUpdated by remember { mutableStateOf(false) }

    // لافتة Update Success: 1.2 ثانية فقط
    LaunchedEffect(vm.updateSuccessTick) {
        if (vm.updateSuccessTick > 0) {
            showUpdated = true
            delay(1200)
            showUpdated = false
        }
    }
    // دعوة تلغرام (مرة كل 24 ساعة حتى يوافق)
    LaunchedEffect(Unit) {
        if (shouldAskTelegram(ctx)) {
            delay(700)
            dialog = "telegram"
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(onItem = { id ->
                scope.launch { drawerState.close() }
                when (id) {
                    "update" -> vm.updateSubscription()
                    "battery" -> try {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "Cannot open battery settings", Toast.LENGTH_SHORT).show()
                    }
                    "tg" -> openUrl(ctx, Links.TELEGRAM)
                    "yt" -> openUrl(ctx, Links.YOUTUBE)
                    else -> dialog = id
                }
            })
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(PageBg)) {
                TopBar(
                    subtitle = remember { operatorText(ctx) },
                    busy = vm.subBusy,
                    onMenu = { scope.launch { drawerState.open() } },
                    onSync = { vm.updateSubscription() }
                )
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.White,
                    contentColor = Teal
                ) {
                    Tab(
                        selected = tab == 0, onClick = { tab = 0 },
                        selectedContentColor = Teal, unselectedContentColor = TextSub,
                        text = { Text("HOME", fontSize = 20.sp, letterSpacing = 3.sp) }
                    )
                    Tab(
                        selected = tab == 1, onClick = { tab = 1 },
                        selectedContentColor = Teal, unselectedContentColor = TextSub,
                        text = { Text("LOGS", fontSize = 20.sp, letterSpacing = 3.sp) }
                    )
                }
                if (tab == 0) {
                    HomeTab(
                        vm = vm, dnsTick = dnsTick, onToggle = onToggle,
                        onServers = { dialog = "servers" },
                        onDns = { dialog = "dns" }
                    )
                } else {
                    LogScreen()
                }
            }

            AnimatedVisibility(
                visible = showUpdated,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF16A34A), shadowElevation = 8.dp) {
                    Text(
                        "Update Success",
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                        color = Color.White, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    when (dialog) {
        "servers" -> ServersDialog(vm, onDismiss = { dialog = "" }, onAdd = { dialog = "add" })
        "add" -> AddServerDialog(onDismiss = { dialog = "servers" }, onSave = { vm.addServer(it); dialog = "servers" })
        "dns" -> AlertDialog(
            onDismissRequest = { dialog = ""; dnsTick++ },
            title = { Text("Custom DNS") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { DnsSettingsSection() } },
            confirmButton = { TextButton(onClick = { dialog = ""; dnsTick++ }) { Text("Close") } }
        )
        "share" -> ShareDialog(vm, onDismiss = { dialog = "" })
        "source" -> SourceDialog(vm, onDismiss = { dialog = "" })
        "restore" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Restore Default") },
            text = { Text("Reset DNS, QUIC blocking and Wifi Tethering to their defaults? Your servers are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    DnsConfig.reset(ctx)
                    vm.saveShare(false, 8080)
                    dnsTick++
                    dialog = ""
                    Toast.makeText(ctx, "Settings restored", Toast.LENGTH_SHORT).show()
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
        )
        "telegram" -> TelegramDialog(
            onLater = { dialog = "" },
            onJoin = { markTelegramJoined(ctx); dialog = ""; openUrl(ctx, Links.TELEGRAM) }
        )
    }
}

@Composable
private fun TopBar(subtitle: String, busy: Boolean, onMenu: () -> Unit, onSync: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "sync")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle"
    )
    Row(
        Modifier.fillMaxWidth().background(Teal).statusBarsPadding().padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Default.Menu, "Menu", tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text("ON Tunnel", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFFD7F5F0), fontSize = 16.sp)
        }
        IconButton(onClick = onSync, enabled = !busy) {
            Icon(
                Icons.Default.Sync, "Update servers", tint = Color.White,
                modifier = Modifier.size(32.dp).rotate(if (busy) angle else 0f)
            )
        }
    }
}

// ───────────────────────── القائمة الجانبية ─────────────────────────
@Composable
private fun DrawerContent(onItem: (String) -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "v. ${info.versionName} (${versionCodeOf(info)})"
        } catch (_: Exception) { "" }
    }
    ModalDrawerSheet(drawerContainerColor = Color.White, modifier = Modifier.width(310.dp)) {
        Column(
            Modifier.fillMaxWidth().background(Teal).statusBarsPadding().padding(vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(112.dp).background(Color(0xFFEDEDED), CircleShape).padding(6.dp).background(Ink, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.VpnLock, null, tint = Mint, modifier = Modifier.size(58.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("ON Tunnel", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("App Version : $version", color = Color.White, fontSize = 13.sp)
        }
        DrawerRow(Icons.Default.Sync, "Update servers") { onItem("update") }
        DrawerRow(Icons.Default.CloudSync, "Servers source (GitHub)") { onItem("source") }
        DrawerRow(Icons.Default.Dns, "Custom DNS") { onItem("dns") }
        DrawerRow(Icons.Default.WifiTethering, "Wifi Tethering") { onItem("share") }
        DrawerRow(Icons.Default.BatteryStd, "Battery Optimization") { onItem("battery") }
        DrawerRow(Icons.Default.SettingsBackupRestore, "Restore Default") { onItem("restore") }
        if (Links.isSet(Links.TELEGRAM)) DrawerRow(Icons.AutoMirrored.Filled.Send, "Telegram") { onItem("tg") }
        if (Links.isSet(Links.YOUTUBE)) DrawerRow(Icons.Default.PlayCircle, "YouTube") { onItem("yt") }
    }
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Color(0xFF14B8A6), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(30.dp))
            Text(label, fontSize = 19.sp, color = TextMain)
        }
        HorizontalDivider(color = Color(0xFFE3E7E9))
    }
}

// ───────────────────────── الشاشة الرئيسية ─────────────────────────
@Composable
fun HomeTab(
    vm: VpnViewModel, dnsTick: Int, onToggle: () -> Unit,
    onServers: () -> Unit, onDns: () -> Unit
) {
    val ctx = LocalContext.current
    val server = vm.servers.getOrNull(vm.selected)
    val net = remember(vm.status) { networkInfo(ctx) }
    val traffic = rememberTraffic(vm.status == VpnStatus.CONNECTED)
    var quic by remember(dnsTick) { mutableStateOf(DnsConfig.blockQuic(ctx)) }
    val dnsText = remember(dnsTick) { "DNS: " + DnsConfig.summary(ctx) }

    val statusText = when (vm.status) {
        VpnStatus.CONNECTED -> "CONNECTED"
        VpnStatus.CONNECTING -> "CONNECTING..."
        VpnStatus.DISCONNECTED -> "DISCONNECTED"
    }
    val statusColor = when (vm.status) {
        VpnStatus.CONNECTED -> Green
        VpnStatus.CONNECTING -> Amber
        VpnStatus.DISCONNECTED -> Red
    }
    val mode = "TCP|V2RAY"

    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Card(
            Modifier.fillMaxWidth().padding(14.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("${net.first}: ${net.second}", fontSize = 20.sp, color = TextMain)
                        Row {
                            Text("STATUS : ", fontSize = 17.sp, color = TextMain)
                            Text(statusText, fontSize = 17.sp, color = statusColor)
                        }
                    }
                    Text(mode, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Purple)
                }
                vm.error?.let {
                    Text(it, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Servers", fontSize = 24.sp, color = TextMain, modifier = Modifier.weight(1f))
                    TrafficChip(up = true, text = formatBytes(traffic.first))
                    Spacer(Modifier.width(12.dp))
                    TrafficChip(up = false, text = formatBytes(traffic.second))
                }
                Spacer(Modifier.height(10.dp))

                val flag = server?.name?.let { flagOf(it) }
                ServerField(
                    leading = { if (flag != null) FlagBox(flag) else GlobeBox() },
                    title = server?.let { cleanName(it.name, flag) } ?: "No servers — tap the sync icon",
                    subtitle = server?.let { protocolText(it) } ?: "GitHub list is empty",
                    onClick = onServers
                )

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tweaks", fontSize = 24.sp, color = TextMain, modifier = Modifier.weight(1f))
                    Text("Block QUIC", fontSize = 18.sp, color = TextMain)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = quic, onCheckedChange = { quic = it; DnsConfig.setBlockQuic(ctx, it) })
                }
                Spacer(Modifier.height(10.dp))
                ServerField(
                    leading = { GlobeBox() },
                    title = dnsText,
                    subtitle = "TAP TO CHANGE",
                    onClick = onDns
                )

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onToggle,
                    enabled = vm.status != VpnStatus.CONNECTING,
                    modifier = Modifier.fillMaxWidth().height(68.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink, contentColor = Color.White,
                        disabledContainerColor = Ink, disabledContentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Text(
                        when (vm.status) {
                            VpnStatus.CONNECTED -> "STOP"
                            VpnStatus.CONNECTING -> "CONNECTING..."
                            VpnStatus.DISCONNECTED -> "START"
                        },
                        fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp
                    )
                }

                Spacer(Modifier.height(26.dp))
                TimeCreditRow()
            }
        }

        // البانر بعيد عن الأزرار لتفادي النقر الخاطئ
        AdBanner(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

        Spacer(Modifier.height(24.dp))
        val footer = "${vm.servers.size} servers" +
            if (vm.lastUpdated > 0) " • updated " +
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(vm.lastUpdated)) else ""
        Text(
            footer,
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 16.sp, color = TextMain
        )
    }
}

@Composable
private fun TrafficChip(up: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(Teal, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (up) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null,
                tint = Color.White, modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 18.sp, color = TextMain)
    }
}

@Composable
private fun ServerField(leading: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = FieldBg,
        border = BorderStroke(1.5.dp, Ink)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain, maxLines = 2)
                Text(subtitle, fontSize = 13.sp, color = TextSub)
            }
        }
    }
}

@Composable
private fun FlagBox(flag: String) {
    Box(Modifier.width(66.dp).height(46.dp), contentAlignment = Alignment.Center) {
        Text(flag, fontSize = 34.sp)
    }
}

@Composable
private fun GlobeBox() {
    Box(
        Modifier.width(66.dp).height(46.dp).background(Color(0xFF14B8A6), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Public, null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

// ───────────────────────── السجل ─────────────────────────
@Composable
fun LogScreen() {
    val clipboard = LocalClipboardManager.current
    val state = rememberLazyListState()
    LaunchedEffect(AppLog.lines.size) {
        if (AppLog.lines.isNotEmpty()) state.scrollToItem(AppLog.lines.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(AppLog.lines.joinToString("\n")))
            }) { Text("Copy") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))
        if (AppLog.lines.isEmpty()) Text("No logs yet", fontSize = 13.sp)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(AppLog.lines) {
                Text(
                    it, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

// ───────────────────────── النوافذ ─────────────────────────
@Composable
private fun ServersDialog(vm: VpnViewModel, onDismiss: () -> Unit, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Servers", modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.pingAll() }, enabled = !vm.pinging && vm.servers.isNotEmpty()) {
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (vm.pinging) "Testing…" else "Ping")
                }
            }
        },
        text = {
            if (vm.servers.isEmpty()) {
                Text("No servers yet. Tap the sync icon to update from GitHub, or add one manually.")
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(vm.servers) { i, s ->
                        val flag = flagOf(s.name)
                        val ping = vm.pings[s.config]
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (vm.status == VpnStatus.DISCONNECTED) {
                                    vm.select(i)
                                    onDismiss()
                                } else {
                                    Toast.makeText(ctx, "Disconnect first to change server", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = vm.selected == i, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            if (flag != null) Text(flag, fontSize = 24.sp) else Icon(Icons.Default.Public, null, tint = Teal)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cleanName(s.name, flag), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(protocolText(s), fontSize = 12.sp, color = TextSub)
                            }
                            if (ping != null) {
                                Text(
                                    if (ping < 0) "timeout" else "$ping ms",
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        ping < 0 -> Red
                                        ping < 300 -> Green
                                        ping < 800 -> Amber
                                        else -> Red
                                    }
                                )
                            }
                            IconButton(onClick = { vm.removeServer(i) }) {
                                Icon(Icons.Default.Delete, "Delete", tint = TextSub)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun ShareDialog(vm: VpnViewModel, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(vm.shareEnabled) }
    var port by remember { mutableStateOf(vm.sharePort.toString()) }
    val ips = remember { ShareProxy.localIps() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wifi Tethering") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(on, { on = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Share when connected", fontSize = 15.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) },
                    label = { Text("Port (1024-65535)") }, singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                if (ShareProxy.active) {
                    Text(
                        "Active: ${ips.firstOrNull() ?: "phone IP"}:${ShareProxy.port}",
                        fontSize = 14.sp, color = Teal, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "Other devices on your Wi-Fi or hotspot can use this phone's VPN. " +
                        "On the other device set an HTTP proxy to this phone's IP and the port above. " +
                        "Local network devices only.",
                    fontSize = 12.sp, color = TextSub
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveShare(on, port.toIntOrNull() ?: 8080); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SourceDialog(vm: VpnViewModel, onDismiss: () -> Unit) {
    var subUrl by remember { mutableStateOf(vm.subUrl) }
    var subAuto by remember { mutableStateOf(vm.subAuto) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servers source (GitHub)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (AppConfig.LOCK_SUB && AppConfig.SUB_URL.isNotBlank()) {
                    Text("The GitHub link is built into the app.", fontSize = 14.sp)
                } else {
                    OutlinedTextField(
                        subUrl, { subUrl = it.trim() },
                        label = { Text("Raw file link (https://…)") }, singleLine = true
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(subAuto, { subAuto = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Auto update when the app opens", fontSize = 14.sp)
                }
                TextButton(
                    onClick = { vm.saveSubscription(subUrl, subAuto); vm.updateSubscription() },
                    enabled = !vm.subBusy
                ) { Text("Save & update now") }
                vm.subMessage?.let { Text(it, fontSize = 12.sp, color = TextSub) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveSubscription(subUrl, subAuto); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun TelegramDialog(onLater: () -> Unit, onJoin: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Join us on Telegram?", fontSize = 26.sp, letterSpacing = 1.sp) },
        text = {
            Text(
                "We have a telegram support group where we post and discuss about settings, " +
                    "new features and also assist our users.\n\nWould you like to join us there?",
                fontSize = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onJoin,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.White)
            ) { Text("Yes Please.", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("Later", fontSize = 20.sp, letterSpacing = 1.sp) } }
    )
}

@Composable
fun AddServerDialog(onDismiss: () -> Unit, onSave: (Server) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var name by remember { mutableStateOf("") }
    var cfg by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add V2Ray server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    cfg, { cfg = it.trim() },
                    label = { Text("VLESS / VMess / Trojan link") },
                    placeholder = { Text("vless://… or vmess://… or trojan://…") },
                    modifier = Modifier.height(150.dp)
                )
                TextButton(onClick = { clipboard.getText()?.text?.let { cfg = it.trim() } }) { Text("Paste from clipboard") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Server(name = name, config = cfg)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ───────────────────────── أدوات مساعدة ─────────────────────────
@Suppress("DEPRECATION")
private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

private fun protocolText(s: Server): String = "V2Ray • ${s.config.substringBefore("://").uppercase()}"

/** أول علم (رمزان إقليميان) داخل الاسم، أو null. */
private fun flagOf(name: String): String? {
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        val n = Character.charCount(cp)
        if (cp in 0x1F1E6..0x1F1FF && i + n < name.length) {
            val cp2 = name.codePointAt(i + n)
            if (cp2 in 0x1F1E6..0x1F1FF) return String(Character.toChars(cp)) + String(Character.toChars(cp2))
        }
        i += n
    }
    return null
}

private fun cleanName(name: String, flag: String?): String {
    val n = if (flag != null) name.replace(flag, "") else name
    return n.replace("[]", "").trim().ifBlank { name }
}

private fun operatorText(ctx: Context): String = try {
    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val op = tm?.networkOperatorName.orEmpty()
    val cc = tm?.networkCountryIso.orEmpty().uppercase()
    if (op.isBlank()) "Operator: —" else "Operator: $op" + if (cc.isNotBlank()) "($cc)" else ""
} catch (_: Exception) { "Operator: —" }

/** (نوع الشبكة، IP) للشبكة الأصلية (بدون نفق VPN). */
@Suppress("DEPRECATION")
private fun networkInfo(ctx: Context): Pair<String, String> {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val ip = lp.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: continue
            val label = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                else -> "NET"
            }
            return label to ip
        }
        "NET" to "—"
    } catch (_: Exception) { "NET" to "—" }
}

/** حركة التطبيق (رفع، تنزيل) منذ الاتصال — تقريب لحركة الـ VPN لأن المحرك داخل التطبيق. */
@Composable
private fun rememberTraffic(active: Boolean): Pair<Long, Long> {
    var up by remember { mutableStateOf(0L) }
    var down by remember { mutableStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) {
            up = 0L; down = 0L
            return@LaunchedEffect
        }
        val uid = android.os.Process.myUid()
        val tx0 = TrafficStats.getUidTxBytes(uid)
        val rx0 = TrafficStats.getUidRxBytes(uid)
        while (true) {
            up = (TrafficStats.getUidTxBytes(uid) - tx0).coerceAtLeast(0L)
            down = (TrafficStats.getUidRxBytes(uid) - rx0).coerceAtLeast(0L)
            delay(1000)
        }
    }
    return up to down
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
}

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(ctx, "Cannot open link", Toast.LENGTH_SHORT).show()
    }
}

private fun shouldAskTelegram(ctx: Context): Boolean {
    if (!Links.isSet(Links.TELEGRAM)) return false
    val p = ctx.getSharedPreferences("ui", 0)
    if (p.getBoolean("tgJoined", false)) return false
    val now = System.currentTimeMillis()
    if (now - p.getLong("tgLast", 0L) < 24L * 3600 * 1000) return false
    p.edit().putLong("tgLast", now).apply()
    return true
}

private fun markTelegramJoined(ctx: Context) {
    ctx.getSharedPreferences("ui", 0).edit().putBoolean("tgJoined", true).apply()
}
