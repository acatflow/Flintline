package com.github.kr328.clash.core.bridge

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * mihomo 核心（上游项目 编译产物 libclash.so + 配套 JNI 桥接层 libbridge.so）的 JNI
 * 声明层——**这个文件的包名 `com.github.kr328.clash.core.bridge` 绝对不能改**，
 * `nm -D libbridge.so` 实测过，JNI 导出符号是
 * `Java_com_github_kr328_clash_core_bridge_Bridge_nativeXxx` 这样硬编码在预编译
 * `.so` 里的，改了包名/类名/方法名就是运行时 UnsatisfiedLinkError（编译期不报错）。
 * FlintLine 决定"vendor 预编译 .so，不重建整套 Go+CMake 工具链"（见 DEVLOG 开头那条
 * 记录），代价就是这一层的包名被这几个 .so 焊死，只能原样照抄，不能套进
 * com.tvvpn.app 命名空间——这不影响 App 本身的 applicationId/namespace，
 * 只是这几个源文件的 package 声明。
 *
 * 只保留 FlintLine 用得到的原生方法（初始化/加载配置/起停 TUN/查节点组/切节点/
 * 查隧道状态/reset）——上游项目 原版 Bridge.kt 还有 override 编辑、age 加密密钥、
 * rule-provider 更新、日志订阅等接口，FlintLine 需求里没有对应功能（选节点/连接/
 * 断开三个动作而已），不搬来增加没用的 keep 面。真要用到时回 上游项目 源码补。
 */
@Keep
object Bridge {
    external fun nativeReset()
    external fun nativeQueryTunnelState(): String
    external fun nativeStartTun(fd: Int, stack: String, gateway: String, portal: String, dns: String, cb: TunInterface)
    external fun nativeStopTun()
    external fun nativeQueryGroupNames(excludeNotSelectable: Boolean): String
    external fun nativeQueryGroup(name: String, sort: String): String?
    external fun nativePatchSelector(selector: String, name: String): Boolean
    external fun nativeFetchAndValid(
        completable: FetchCallback,
        path: String,
        url: String,
        force: Boolean
    )

    external fun nativeLoad(completable: CompletableDeferred<Unit>, path: String)

    private external fun nativeInit(home: String, versionName: String, sdkVersion: Int)

    init {
        System.loadLibrary("bridge")

        val ctx = Global.application

        // 上游项目 原版注释：疑似 mihomo 原生侧需要提前拿到 APK 自身的一个只读 fd
        // （可能跟内嵌资源 mmap 有关）才能正常工作——不完全理解原理，原样保留，
        // 不敢删。
        ParcelFileDescriptor.open(File(ctx.packageCodePath), ParcelFileDescriptor.MODE_READ_ONLY)
            .detachFd()

        val home = ctx.filesDir.resolve("clash").apply { mkdirs() }.absolutePath
        val versionName = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
        val sdkVersion = Build.VERSION.SDK_INT

        Log.d("Home = $home")

        nativeInit(home, versionName, sdkVersion)
    }
}
