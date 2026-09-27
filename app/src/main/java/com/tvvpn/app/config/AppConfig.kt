package com.tvvpn.app.config

/**
 * 全局配置中心（开源精简版）。
 *
 * 本开源项目不连任何私有后端、不做远程配置拉取与遥测。这里只保留与 VPN 引擎直接相关、
 * 纯本地、无隐私、无网络的默认值。需要用户自配的东西（订阅地址）见 [com.tvvpn.app.service.SubscriptionProvider]。
 */
object AppConfig {

    /** 顶栏展示名，纯 UI。可自行改成你的项目名。 */
    const val brandDisplay: String = "TV VPN"

    // ── TUN 参数（交给 Android VpnService.Builder + mihomo 核心）──
    // 这些是本地虚拟网卡的地址/MTU/协议栈，与任何后端无关，改动需懂 mihomo tun 语义。
    const val tunMtu: Int = 9000
    const val tunPrefix: Int = 30
    const val tunGateway: String = "172.19.0.1"
    const val tunPortal: String = "172.19.0.2"
    const val tunStack: String = "system"

    /** EndpointManager 的网络级失败切换阈值（本项目通常只有一个订阅源，保留以兼容其逻辑）。 */
    const val failureThreshold: Int = 2
}
