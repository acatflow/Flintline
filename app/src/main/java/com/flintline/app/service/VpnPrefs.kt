package com.flintline.app.service

import android.content.Context

/**
 * "VPN 该不该开"的持久化开关——这是开机自启的地基:
 * always-on VPN 在 launcher 之前 startService 起 FlintVpnService,那时没有 GUI、
 * 没有 replyTo,onStartCommand 只能靠这个落盘的意图判断"用户上次是开着的吗",
 * 是→自己拉起连接(见 FlintVpnService.onStartCommand);否→按兵不动。
 *
 * 写入方是 GUI(VpnClient.connectVpn/disconnectVpn):用户开一次就记 true、关一次记 false。
 * 老人只要开过一次,之后每次开机都会自动连,GUI 只剩一个开关——正是需求要的。
 */
object VpnPrefs {
    private const val PREFS_NAME = "flint_vpn"
    private const val KEY_SHOULD_BE_ON = "should_be_on"

    fun shouldBeOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOULD_BE_ON, false)

    fun setShouldBeOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOULD_BE_ON, on).apply()
    }
}
