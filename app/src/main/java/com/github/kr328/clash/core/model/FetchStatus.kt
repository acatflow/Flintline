package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

// 裁剪自 上游项目 原版，去掉 Parcelable——理由见 TunnelState.kt 顶部注释。
// FlintLine 第一期不在 UI 上展示下载进度，只关心 fetchAndValid 最终成功/失败，
// 但 FetchCallback.report() 的 JSON 载荷需要能正常反序列化，字段原样保留。
@Serializable
data class FetchStatus(
    val action: Action,
    val args: List<String>,
    val progress: Int,
    val max: Int,
    val subUpload: Long? = null,
    val subDownload: Long? = null,
    val subTotal: Long? = null,
    val subExpire: Long? = null,
    val subUpdateInterval: Long? = null,
) {
    enum class Action {
        FetchConfiguration,
        FetchProviders,
        SubscriptionInfo,
        Verifying,
    }
}
