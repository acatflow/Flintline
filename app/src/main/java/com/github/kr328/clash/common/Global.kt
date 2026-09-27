package com.github.kr328.clash.common

import android.app.Application

// 从 上游项目 的 :common 模块原样搬过来的最小子集——Bridge.kt 的 JNI init 需要
// 一个全局 Application 引用（拿 filesDir/packageCodePath/packageManager），
// 没有必要为这一个字段整体引入 上游项目 的 :common 模块（那边还有一堆
// FlintLine 用不到的工具类）。FlintLineApp.onCreate() 里调用 Global.init(this)。
object Global {
    val application: Application
        get() = application_

    private lateinit var application_: Application

    fun init(application: Application) {
        this.application_ = application
    }
}
