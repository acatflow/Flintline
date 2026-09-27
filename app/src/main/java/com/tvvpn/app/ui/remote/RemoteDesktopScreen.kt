package com.tvvpn.app.ui.remote

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tvvpn.app.service.LanAddress
import com.tvvpn.app.service.RemoteDesktopService
import com.tvvpn.app.service.RemoteInputAccessibilityService
import com.tvvpn.app.service.RemotePrefs
import com.tvvpn.app.ui.theme.FlintTextMuted
import com.tvvpn.app.ui.theme.FlintTextPrimary
import com.tvvpn.app.ui.theme.FlintTextSecondary

/**
 * 远程桌面（**rootless / 需显式授权 / 局域网 / 默认关**）。
 *
 * 两项授权:①投屏(MediaProjection，Activity 弹窗) ②无障碍(去系统设置手动开，用于注入点按)。
 * 开启后展示 `http://<局域网IP>:<端口>/?token=<PIN>`——在**可信局域网**里用浏览器打开即可看屏/操控。
 * API<24 只能看屏,无法注入手势(dispatchGesture 限制),UI 会提示。
 */
@Composable
fun RemoteDesktopScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val pin = remember { RemotePrefs.getOrCreatePin(context) }
    var running by remember { mutableStateOf(RemoteDesktopService.isRunning) }
    var pinShown by remember { mutableStateOf(pin) }
    val a11yOn = RemoteInputAccessibilityService.instance != null
    val canInject = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    val ip = remember { LanAddress.ipv4() }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                context, RemoteDesktopService.startIntent(context, result.resultCode, result.data!!)
            )
            running = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Text("遠程桌面", fontSize = 28.sp, color = FlintTextSecondary)
        Spacer(Modifier.height(8.dp))
        Text(
            "在可信局域網內，用瀏覽器遠程看屏/操控本機。全程 rootless、需你顯式授權、預設關閉。",
            fontSize = 14.sp, color = FlintTextMuted,
        )
        Spacer(Modifier.height(20.dp))

        // 状态
        Text("狀態：${if (running) "運行中" else "已停止"}", fontSize = 16.sp, color = FlintTextPrimary)
        Text("無障礙(遠程操控)：${if (a11yOn) "已開啟" else "未開啟"}", fontSize = 14.sp,
            color = if (a11yOn) FlintTextPrimary else FlintTextMuted)
        if (!canInject) {
            Text("注意：本機 Android 版本低於 7.0，只能看屏、無法注入點按/滑動。",
                fontSize = 13.sp, color = FlintTextMuted)
        }
        Spacer(Modifier.height(16.dp))

        // 访问地址
        if (running) {
            val addr = if (ip != null) "http://$ip:${RemotePrefs.PORT}/?token=$pinShown"
            else "拿不到局域網 IP，請確認已連 Wi-Fi/有線網"
            Text("訪問地址（瀏覽器打開）：", fontSize = 14.sp, color = FlintTextSecondary)
            Text(addr, fontSize = 16.sp, color = FlintTextPrimary)
            Text("PIN：$pinShown", fontSize = 15.sp, color = FlintTextPrimary)
            Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!running) {
                Button(onClick = {
                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                }) { Text("開啟(申請投屏)") }
            } else {
                Button(onClick = {
                    context.startService(RemoteDesktopService.stopIntent(context))
                    running = false
                }) { Text("停止") }
            }
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("開啟無障礙(遠程操控)") }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { pinShown = RemotePrefs.regeneratePin(context) }) { Text("重置 PIN") }
            Button(onClick = onBack) { Text("返回") }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "安全提示：僅限可信局域網、風險自負。任何知道地址與 PIN 的人都能看屏並操控本機；" +
                "不用時請「停止」。本功能不連任何後端、不會開機自啟。",
            fontSize = 12.sp, color = FlintTextMuted,
        )
    }
}
