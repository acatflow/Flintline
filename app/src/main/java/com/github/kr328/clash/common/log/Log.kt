package com.github.kr328.clash.common.log

// 从 milkdns 的 :common 模块原样搬过来，只是为了让 Bridge.kt 能不改一个字符地复用。
object Log {
    private const val TAG = "ClashCoreForFlintLine"

    fun i(message: String, throwable: Throwable? = null) =
        android.util.Log.i(TAG, message, throwable)

    fun w(message: String, throwable: Throwable? = null) =
        android.util.Log.w(TAG, message, throwable)

    fun e(message: String, throwable: Throwable? = null) =
        android.util.Log.e(TAG, message, throwable)

    fun d(message: String, throwable: Throwable? = null) =
        android.util.Log.d(TAG, message, throwable)
}
