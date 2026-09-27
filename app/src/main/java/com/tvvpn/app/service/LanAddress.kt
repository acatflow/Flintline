package com.tvvpn.app.service

import java.net.Inet4Address
import java.net.NetworkInterface

/** 取本机局域网 IPv4 地址（供远程桌面拼访问地址用），拿不到返回 null。 */
object LanAddress {
    fun ipv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { null }
}
