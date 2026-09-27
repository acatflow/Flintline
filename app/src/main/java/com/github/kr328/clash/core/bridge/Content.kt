package com.github.kr328.clash.core.bridge

import android.net.Uri
import androidx.annotation.Keep
import com.github.kr328.clash.common.Global
import java.io.FileNotFoundException

// 原样搬自 milkdns——必须存在的原因见 LogcatInterface.kt 顶部注释
// （libbridge.so 的 JNI_OnLoad 会一次性 FindClass 这几个类）。native 侧在加载
// content:// URI 形式的配置路径时会回调这个类的 open()，FlintLine 目前配置文件
// 都是本地 File 路径，不会触发，但类本身必须在。
@Keep
object Content {
    @JvmStatic
    fun open(url: String): Int {
        val uri = Uri.parse(url)

        if (uri.scheme != "content") {
            throw UnsupportedOperationException("Unsupported scheme ${uri.scheme}")
        }

        return Global.application.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
            ?: throw FileNotFoundException("$uri not found")
    }
}
