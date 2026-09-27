package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

// 原样搬自 上游项目——必须存在的原因见 LogcatInterface.kt 顶部注释
// （libbridge.so 的 JNI_OnLoad 会一次性 FindClass 这几个类）。
@Keep
class ClashException(msg: String) : IllegalArgumentException(msg)
