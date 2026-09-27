package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

// 裁剪自 milkdns 原版，去掉 Parcelable——理由见 TunnelState.kt 顶部注释。
@Serializable
data class Proxy(
    val name: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val delay: Int,
    var isGroup: Boolean,
)
