package com.tvvpn.app.ui.nodes

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvvpn.app.service.VpnClient
import com.tvvpn.app.ui.theme.FlintAmber
import com.tvvpn.app.ui.theme.FlintFocusSurface
import com.tvvpn.app.ui.theme.tvFocusHighlight
import com.tvvpn.app.ui.theme.FlintSurfaceRaised
import com.tvvpn.app.ui.theme.FlintTextMuted
import com.tvvpn.app.ui.theme.FlintTextSecondary

// 选节点页——三个核心动作之一。节点列表来自 :vpn 进程里 mihomo 核心已加载配置的
// 第一个 select 类型分组（FlintVpnService.selectorGroupName()）。**已知限制**：
// 只有连接过一次之后才能看到真实列表（配置目前只在 connectVpn() 时才会真正
// Clash.load()，见 FlintVpnService.replyNodes() 注释）；没连接过 / 没有可选节点组
// 时显示空态文案，不是 bug。用户在「設定」填入订阅并连接成功之前，看到的
// 都是 fallback_direct.yaml 里的占位节点，选谁都不影响实际流量（mode: direct）。
@Composable
fun NodeSelectScreen() {
    val context = LocalContext.current
    val vpnClient = remember { VpnClient(context) }
    val nodeList by vpnClient.nodes.collectAsState()

    // 节点列表在 VpnClient 里 bind 成功的那一刻（onServiceConnected）就自动查询
    // 一次了，这里不需要再单独 LaunchedEffect 查一次——之前踩过坑：跟 bind()
    // 同一帧调用 queryNodes() 会在 serviceMessenger 还是 null 的时候把请求静默
    // 丢掉，见 VpnClient.onServiceConnected 注释。
    DisposableEffect(Unit) {
        vpnClient.bind()
        onDispose { vpnClient.unbind() }
    }

    // TV 焦点：列表一有数据就把焦点放到"当前选中"的那一行（没有选中就第一行），
    // 只做一次——之后用户用方向键移动，切换节点导致列表刷新时不再抢焦点。
    val initialFocusRequester = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    val initialFocusIndex = nodeList.selectedIndex.coerceAtLeast(0)
    val inputModeManager = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(nodeList.names.isNotEmpty(), windowFocused) {
        if (nodeList.names.isNotEmpty() && windowFocused && !initialFocusDone) {
            // 先退出触摸模式，原因见 RequestInitialTvFocus 的注释
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (runCatching { initialFocusRequester.requestFocus() }.isSuccess) initialFocusDone = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 24.dp),
    ) {
        Text("選線路", fontSize = 18.sp, color = FlintTextSecondary)
        Spacer(Modifier.height(24.dp))

        if (nodeList.names.isEmpty()) {
            Text(
                "暫無可用線路（先連接一次，或稍候線路資料同步完成）",
                fontSize = 14.sp,
                color = FlintTextMuted,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(nodeList.names.size) { index ->
                    val name = nodeList.names[index]
                    val selected = index == nodeList.selectedIndex
                    val rowInteraction = remember { MutableInteractionSource() }
                    val rowFocused by rowInteraction.collectIsFocusedAsState()
                    val rowShape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (index == initialFocusIndex) Modifier.focusRequester(initialFocusRequester)
                                else Modifier
                            )
                            // 列表行是全宽的，放大幅度小一点（1.02），不然左右会被 LazyColumn 裁掉
                            .tvFocusHighlight(rowInteraction, rowShape, focusedScale = 1.02f)
                            .background(
                                color = when {
                                    rowFocused -> FlintFocusSurface
                                    selected -> FlintAmber.copy(alpha = 0.18f)
                                    else -> FlintSurfaceRaised
                                },
                                shape = rowShape,
                            )
                            .clickable(
                                interactionSource = rowInteraction,
                                indication = null,
                            ) {
                                vpnClient.selectNode(name)
                                vpnClient.queryNodes()
                                // 切换是 mihomo 核心的热切换（patchSelector 立刻生效，不需要
                                // 断开重连），但光靠列表里那一项变色未必够明显——这个 App 的
                                // 目标用户是独居老人，电视上离远了看容易怀疑"刚才是不是点到
                                // 了"，加一个短暂的 Toast 提示更清楚。
                                Toast.makeText(context, "已切換到 $name", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = name,
                            fontSize = 16.sp,
                            color = if (selected) FlintAmber else Color.White,
                        )
                        if (selected) {
                            Spacer(Modifier.weight(1f))
                            Text("✓ 使用中", fontSize = 14.sp, color = FlintAmber)
                        }
                    }
                }
            }
        }
    }
}
