package com.tvvpn.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.system.Os
import android.util.Log
import com.tvvpn.app.net.TlsCompat
import com.github.kr328.clash.common.Global
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class FlintLineApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private companion object { const val RESTART_DEBOUNCE_MS = 30_000L }

    /**
     * 全局未捕获异常兜底 + 自愈：一个后台线程的未捕获异常本会弹「已停止运作」并杀死主进程。
     * 这里替换默认 handler：记日志 → 主进程静默排 500ms 自愈重启 → 杀进程。**防抖**：30s 内
     * 已自动重启过则不再重启（避免崩→重启→再崩的循环）。native 崩溃（SIGABRT，如 :vpn 的
     * libclash）由系统直接杀进程、JVM handler 拦不住,那类靠 service 自动重启,不在此范围。
     */
    private fun installCrashGuard() {
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                Log.e("FlintLineApp", "未捕获异常 @${thread.name}", ex)
                if (isMainProcess()) {
                    val prefs = getSharedPreferences("flint_crashguard", MODE_PRIVATE)
                    val now = System.currentTimeMillis()
                    if (now - prefs.getLong("last_restart", 0L) > RESTART_DEBOUNCE_MS) {
                        prefs.edit().putLong("last_restart", now).apply()
                        scheduleSelfRestart()
                    } else {
                        Log.w("FlintLineApp", "30s 内已重启过,不再自愈,放任退出(防崩溃循环)")
                    }
                }
            } catch (_: Throwable) {
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun scheduleSelfRestart() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val flags = PendingIntent.FLAG_ONE_SHOT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(this, 0, intent, flags)
            (getSystemService(ALARM_SERVICE) as AlarmManager)
                .set(AlarmManager.RTC, System.currentTimeMillis() + 500, pi)
            Log.i("FlintLineApp", "已排 500ms 后自愈重启")
        } catch (t: Throwable) {
            Log.w("FlintLineApp", "排自愈重启失败: ${t.message}")
        }
    }

    private fun isMainProcess(): Boolean = try {
        File("/proc/self/cmdline").readText().substringBefore(' ').trim() == packageName
    } catch (_: Throwable) { true }

    /**
     * 让 mihomo 预编译 Go 内核(libbridge.so/libclash.so)在老安卓上也能验证 Let's Encrypt：
     * 安卓7 系统 CA 库缺 ISRG 根 → Go crypto/x509 走系统池报 unknown authority。通过
     * SSL_CERT_FILE 环境变量指向 App 内置的 CA bundle 修复。
     */
    private fun setupGoCaBundle() {
        try {
            val caFile = File(filesDir, "ca-bundle.pem")
            assets.open("ca-bundle.pem").use { input ->
                caFile.outputStream().use { output -> input.copyTo(output) }
            }
            Os.setenv("SSL_CERT_FILE", caFile.absolutePath, true)
            Log.i("FlintLineApp", "Go CA bundle 就位")
        } catch (e: Throwable) {
            Log.w("FlintLineApp", "CA bundle 设置失败(不阻塞启动): ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashGuard()   // 最先装,后续任何初始化崩了也能兜住
        setupGoCaBundle()
        Global.init(this)
        TlsCompat.install(this)
    }
}
