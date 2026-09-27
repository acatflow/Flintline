package com.tvvpn.app.service

import android.content.Context

/**
 * 订阅来源（开源版）。
 *
 * 本项目不做任何自动注册/遥测——**订阅地址由用户在「設定」里自己填**（Clash/mihomo 订阅
 * 的 http(s) 链接，指向一份 clash 配置 yaml）。本类只负责把这个地址持久化、读回。
 *
 * 真正的下载与校验由 mihomo 核心完成（见 FlintVpnService.prepareProfile → Clash.fetchAndValid），
 * 它原生支持 clash 订阅格式、会校验节点，无需在这里重复用 OkHttp 拉取。
 */
object SubscriptionProvider {
    private const val PREFS = "flint_subscription"
    private const val KEY_URL = "subscription_url"

    /** 用户配置的订阅链接；未配置时返回 null（UI 据此提示去「設定」填写）。 */
    fun getSubscriptionUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, null)
            ?.trim()
            ?.ifBlank { null }

    fun setSubscriptionUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim()).apply()
    }
}
