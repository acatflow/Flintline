package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

// 原样搬自 milkdns。FlintLine 目前没有调用 Bridge.nativeSubscribeLogcat()（没有
// 应用内日志查看器 UI），但这个类**必须存在**——libbridge.so 的 JNI_OnLoad 在库
// 加载时就会一次性 FindClass 缓存这几个回调接口的 jclass（不是等到真正调用对应
// native 方法才去找），真机踩过坑：缺了这个类会导致 JNI_OnLoad 里
// ClassNotFoundException，JNI DETECTED ERROR，直接 SIGABRT 崩溃（:vpn 进程），
// 跟有没有用到 subscribeLogcat() 无关。ClashException/Content/FetchCallback/
// TunInterface 同理，一个都不能少（用 `strings libbridge.so | grep
// com/github/kr328/clash` 能看到 native 侧到底认识哪几个类）。
@Keep
interface LogcatInterface {
    fun received(jsonPayload: String)
}
