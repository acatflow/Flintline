package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

// 裁剪自 milkdns 原版：去掉 Parcelable + SliceProxyList 分片传输（那是给 Binder
// IPC 单次 1MB 限制绕过用的，FlintLine 的进程间通讯不直接传这个对象，见
// TunnelState.kt 顶部注释），直接用普通 List<Proxy>。
@Serializable
data class ProxyGroup(
    val type: String,
    val proxies: List<Proxy>,
    val now: String,
)
