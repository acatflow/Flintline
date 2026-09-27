package com.tvvpn.app.util

import android.content.Context
import android.os.Build

// 运行时读【已安装包】的版本(权威)。不要用 BuildConfig.VERSION_NAME:它是编译期 const,
// Kotlin 会内联到使用处,Gradle 增量编译没重编该类时内联的是旧值 → 界面显示旧版本,
// 但 APK 清单/dumpsys 是新版本(此前曾踩过此坑)。PackageManager 读的是清单真值。

fun installedVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?: com.tvvpn.app.BuildConfig.VERSION_NAME
} catch (_: Exception) {
    com.tvvpn.app.BuildConfig.VERSION_NAME
}

fun installedVersionLabel(context: Context): String = try {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    "版本 ${pi.versionName} ($code)"
} catch (_: Exception) {
    "版本 ${com.tvvpn.app.BuildConfig.VERSION_NAME} (${com.tvvpn.app.BuildConfig.VERSION_CODE})"
}
