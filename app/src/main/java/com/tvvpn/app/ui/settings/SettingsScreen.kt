package com.tvvpn.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvvpn.app.service.SubscriptionProvider
import com.tvvpn.app.ui.theme.FlintTextMuted
import com.tvvpn.app.ui.theme.FlintTextSecondary

/**
 * 設定：填写 Clash/mihomo 订阅链接。开源版没有任何自动注册——用户把自己的订阅地址
 * （指向一份 clash 配置 yaml 的 http(s) 链接）填在这里，保存后回首页即可连接。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var url by remember {
        mutableStateOf(SubscriptionProvider.getSubscriptionUrl(context) ?: "")
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Text("設定", fontSize = 28.sp, color = FlintTextSecondary)
        Spacer(Modifier.height(8.dp))
        Text(
            "填入你的訂閱網址（Clash / mihomo 訂閱連結，指向一份設定 yaml）。",
            fontSize = 14.sp, color = FlintTextMuted,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            label = { Text("訂閱網址") },
            placeholder = { Text("https://example.com/your-subscription.yaml") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = {
                SubscriptionProvider.setSubscriptionUrl(context, url)
                Toast.makeText(context, "已儲存訂閱網址", Toast.LENGTH_SHORT).show()
                onBack()
            }) { Text("儲存") }
            Button(onClick = onBack) { Text("返回") }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "提示：本 App 不連任何私有後端、不做設備註冊或遙測；訂閱內容由 mihomo 核心直接下載校驗。",
            fontSize = 12.sp, color = FlintTextMuted,
        )
    }
}
