package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

// 原样搬自 上游项目——native 侧通过这个回调请求给 socket 打 VPN protect() 标记
// （避免 VPN 自己的代理连接被再次路由进 TUN 造成死循环）和查询连接归属 uid。
@Keep
interface TunInterface {
    fun markSocket(fd: Int)
    fun querySocketUid(protocol: Int, source: String, target: String): Int
}
