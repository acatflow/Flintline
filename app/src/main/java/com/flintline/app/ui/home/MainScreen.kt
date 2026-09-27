package com.flintline.app.ui.home

import android.net.VpnService
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flintline.app.config.AppConfig
import com.flintline.app.service.SubscriptionProvider
import com.flintline.app.service.VpnClient
import kotlinx.coroutines.launch
import com.flintline.app.ui.theme.FlintAmber
import com.flintline.app.ui.theme.FlintAmberBright
import com.flintline.app.ui.theme.FlintTextMuted
import com.flintline.app.ui.theme.FlintTextSecondary
import com.flintline.app.ui.theme.RequestInitialTvFocus
import com.flintline.app.ui.theme.tvFocusHighlight

private const val CONNECT_WATCHDOG_MS = 45_000L
private const val TOGGLE_DEBOUNCE_MS = 2_000L

private val TileBg = Color(0xFF221E18)
private val TileBorder = Color(0xFF352D22)
private val TrackOn = Color(0xFF0D9A6B)
private val TrackOff = Color(0xFF33302A)
private val GreenBright = Color(0xFF22D99A)

/**
 * 首页（开源版）：一个大「上網」开关 + 一张「設定」卡（填订阅链接）+ 选线路入口。
 * 商业版的「叫家人幫忙」远程协助、流量配额守护、always-on/lockdown 强制,均已在抽取时移除。
 */
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit = {},
    onOpenNodeSelect: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vpnClient = remember { VpnClient(context) }
    val vpnState by vpnClient.state.collectAsState()
    val connected = vpnState == VpnClient.State.CONNECTED
    val connecting = vpnState == VpnClient.State.CONNECTING
    val nodeList by vpnClient.nodes.collectAsState()
    val currentNodeLabel = nodeList.names.getOrNull(nodeList.selectedIndex) ?: "自動"

    // 订阅是否已配置（用户在「設定」填过）。未配置时提示去设置,不报错、不转圈。
    var subUrl by remember { mutableStateOf(SubscriptionProvider.getSubscriptionUrl(context)) }
    val lineReady = subUrl != null

    // TV 焦点：进首页焦点落在「上網」卡上。
    val settingsInteraction = remember { MutableInteractionSource() }
    val onlineInteraction = remember { MutableInteractionSource() }
    val settingsCardInteraction = remember { MutableInteractionSource() }
    val nodeInteraction = remember { MutableInteractionSource() }
    val settingsFocused by settingsInteraction.collectIsFocusedAsState()
    val onlineFocusRequester = remember { FocusRequester() }
    RequestInitialTvFocus(onlineFocusRequester)

    DisposableEffect(Unit) {
        vpnClient.bind()
        onDispose { vpnClient.unbind() }
    }

    // 回到首页时刷新订阅配置（可能刚在「設定」里改过）。
    LaunchedEffect(Unit) { subUrl = SubscriptionProvider.getSubscriptionUrl(context) }
    // 有订阅就预热核心 + 提前下载配置,按下连接更快。
    LaunchedEffect(subUrl) { subUrl?.let { vpnClient.prepare(it) } }

    LaunchedEffect(connected) { if (connected) vpnClient.queryNodes() }

    LaunchedEffect(Unit) {
        vpnClient.connectFailed.collect {
            Toast.makeText(context, "連接失敗，請檢查訂閱網址與網路後再試一次", Toast.LENGTH_LONG).show()
        }
    }

    // 看门狗:卡在「連接中」超过 45s 主动问一次真实状态纠正。
    LaunchedEffect(vpnState) {
        if (vpnState == VpnClient.State.CONNECTING) {
            kotlinx.coroutines.delay(CONNECT_WATCHDOG_MS)
            vpnClient.queryState()
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vpnClient.connectVpn(subUrl)
        } else {
            Log.w("MainScreen", "用户拒绝了 VPN 权限")
        }
    }

    var lastToggleAt by remember { mutableStateOf(0L) }

    fun onToggleConnection() {
        if (connecting) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToggleAt < TOGGLE_DEBOUNCE_MS) return
        lastToggleAt = now
        if (connected) {
            vpnClient.disconnectVpn()
        } else {
            val sub = subUrl
            if (sub == null) {
                Toast.makeText(context, "請先在「設定」填入訂閱網址", Toast.LENGTH_SHORT).show()
                onOpenSettings()
                return
            }
            // 标准 Android VpnService 授权流程（无 root）：系统弹窗授权一次后即可连接。
            scope.launch {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    vpnClient.connectVpn(sub)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(AppConfig.brandDisplay, fontSize = 18.sp, color = FlintTextSecondary)
                Spacer(Modifier.width(8.dp))
                Text("v${com.flintline.app.util.installedVersionName(context)}", fontSize = 12.sp, color = FlintTextMuted)
            }
            Text(
                "設定",
                fontSize = 16.sp,
                color = if (settingsFocused) FlintAmberBright else FlintTextMuted,
                modifier = Modifier
                    .tvFocusHighlight(settingsInteraction, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = settingsInteraction, indication = null) { onOpenSettings() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 上網:大开关
            Tile(
                interaction = onlineInteraction,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(onlineFocusRequester),
                onClick = ::onToggleConnection,
            ) {
                Text("上網", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
                Spacer(Modifier.height(18.dp))
                BigToggle(on = connected, connecting = connecting)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when {
                        connected -> "已連接"
                        connecting -> "連接中，請稍候…"
                        !lineReady -> "尚未設定訂閱網址"
                        else -> "已關閉"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        connected -> GreenBright
                        connecting || !lineReady -> FlintAmberBright
                        else -> FlintTextSecondary
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (lineReady) "按 OK 開 / 關" else "請先到「設定」填入訂閱網址",
                    fontSize = 14.sp, color = FlintTextMuted,
                )
            }

            // 設定:填订阅链接
            Tile(
                interaction = settingsCardInteraction,
                modifier = Modifier.weight(1f),
                onClick = onOpenSettings,
            ) {
                Text("設定", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
                Spacer(Modifier.height(18.dp))
                Text("🔗", fontSize = 56.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (lineReady) "訂閱網址已設定" else "填入你的訂閱網址",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = if (lineReady) GreenBright else FlintTextSecondary,
                )
                Spacer(Modifier.weight(1f))
                Text("按 OK 編輯訂閱網址", fontSize = 14.sp, color = FlintTextMuted)
            }
        }

        Spacer(Modifier.height(22.dp))

        // 選線路
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .tvFocusHighlight(
                    nodeInteraction,
                    RoundedCornerShape(20.dp),
                    idleBorder = BorderStroke(1.dp, FlintTextMuted),
                )
                .clickable(interactionSource = nodeInteraction, indication = null) { onOpenNodeSelect() }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text("線路：$currentNodeLabel", fontSize = 15.sp, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 16.sp, color = FlintTextMuted)
        }
    }
}

/** 一张大卡:焦点高亮 + 可点。 */
@Composable
private fun Tile(
    interaction: MutableInteractionSource,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .height(240.dp)
            .tvFocusHighlight(interaction, RoundedCornerShape(24.dp))
            .background(TileBg, RoundedCornerShape(24.dp))
            .border(1.dp, TileBorder, RoundedCornerShape(24.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(28.dp),
        content = content,
    )
}

/** 大开关:绿=開、灰=關,连接中转圈。 */
@Composable
private fun BigToggle(on: Boolean, connecting: Boolean) {
    val track = when {
        connecting -> FlintAmber.copy(alpha = 0.4f)
        on -> TrackOn
        else -> TrackOff
    }
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(50.dp)
            .background(track, CircleShape),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (connecting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
            }
        } else {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(38.dp)
                    .background(Color(0xFFF6F2EA), CircleShape),
            )
        }
    }
}
