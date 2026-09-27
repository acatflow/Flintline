package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

// 原样搬自 上游项目——nativeFetchAndValid() 下载+校验订阅配置时的进度/完成回调。
@Keep
interface FetchCallback {
    fun report(statusJson: String)
    fun complete(error: String?)
}
