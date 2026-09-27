package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 从 milkdns 原版裁剪：只保留 JSON 反序列化需要的字段，去掉了 Parcelable 实现——
// milkdns 那边需要 Parcelable 是因为要把这个对象跨进程传给 UI（Binder IPC）；
// FlintLine 的 IPC 走的是自己在 FlintVpnService 里定义的 Messenger 协议（见该文件
// 注释），不直接传这个对象本身，所以不需要 Parcelable。
@Serializable
data class TunnelState(
    val mode: Mode,
) {
    @Serializable
    enum class Mode {
        @SerialName("direct")
        Direct,

        @SerialName("global")
        Global,

        @SerialName("rule")
        Rule,

        @SerialName("script")
        Script,
    }
}
