package com.tvvpn.app.service

import android.content.Context
import kotlin.random.Random

/**
 * 远程桌面的本地配置（开源版，纯本地、无后端）：
 *  - PIN：首次访问随机生成一次并持久化，作为 HTTP 访问令牌（?token=<PIN>）。
 *  - 端口：MJPEG/HTTP 服务监听端口，默认 8964 之外取一个不常冲突的值。
 *
 * 只落 SharedPreferences，不上报任何地方。PIN 是「可信局域网内」的最低门槛，
 * 不是强加密方案——README 已明确「仅限可信局域网，风险自负」。
 */
object RemotePrefs {
    private const val PREFS = "flint_remote_desktop"
    private const val KEY_PIN = "pin"
    const val PORT = 8623

    /** 取 PIN；不存在则生成一个 6 位数字并持久化。 */
    fun getOrCreatePin(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(KEY_PIN, null)?.let { return it }
        val pin = (Random.nextInt(0, 1_000_000)).toString().padStart(6, '0')
        sp.edit().putString(KEY_PIN, pin).apply()
        return pin
    }

    /** 重新生成 PIN（用户想换令牌时）。 */
    fun regeneratePin(context: Context): String {
        val pin = (Random.nextInt(0, 1_000_000)).toString().padStart(6, '0')
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PIN, pin).apply()
        return pin
    }
}
