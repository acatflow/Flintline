package com.flintline.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.flintline.app.R
import com.flintline.app.config.AppConfig
import com.flintline.app.ui.MainActivity
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxySort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * VPN 引擎——独立进程（`:vpn`，见 manifest），VPN 内核崩溃不拖累主进程和 Flint
 * 心跳，这是"专家审核三合一风险"那轮讨论定下的硬性要求，不是可选项。
 *
 * 订阅来源（开源版）：由用户在「設定」里填写的订阅链接（SubscriptionProvider），
 * 交给 mihomo 核心 Clash.fetchAndValid 下载+校验成真实节点配置再起 TUN。下载失败/未配置时
 * 退回上次成功的配置，再不行用 assets/fallback_direct.yaml 直连兜底（不代理任何流量，
 * 只保证 TUN 能起来、设备始终能上网、绝不因订阅问题而断网）。
 *
 * UI（主进程）跟这个 Service 之间靠 Messenger 通信（VpnControlProtocol.kt）——
 * 不能直接调 Clash.* 方法，native 核心状态只存在于 `:vpn` 进程里。
 */
class FlintVpnService : VpnService() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    @Volatile
    private var running = false

    private var tunFd: Int? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VpnControlProtocol.MSG_CONNECT -> {
                    val replyTo = msg.replyTo
                    val subUrl = msg.data?.getString(VpnControlProtocol.KEY_SUBSCRIPTION_URL)
                    when {
                        running -> replyState(replyTo, VpnControlProtocol.MSG_CONNECT, VpnControlProtocol.STATE_CONNECTED)
                        connecting -> {
                            // 连接进行中又按了一次：只回"連接中"，不重复起连接
                            replyState(replyTo, VpnControlProtocol.MSG_CONNECT, VpnControlProtocol.STATE_CONNECTING)
                        }
                        else -> {
                            connecting = true
                            replyState(replyTo, VpnControlProtocol.MSG_CONNECT, VpnControlProtocol.STATE_CONNECTING)
                            scope.launch {
                                val ok = try { startVpn(subUrl) } finally { connecting = false }
                                replyState(
                                    replyTo, VpnControlProtocol.MSG_CONNECT,
                                    if (ok) VpnControlProtocol.STATE_CONNECTED else VpnControlProtocol.STATE_DISCONNECTED,
                                )
                            }
                        }
                    }
                }
                VpnControlProtocol.MSG_DISCONNECT -> {
                    val replyTo = msg.replyTo
                    if (connecting) {
                        // 连接一半不拆：等它自己成功/失败，UI 期间忽略按键
                        replyState(replyTo, VpnControlProtocol.MSG_DISCONNECT, VpnControlProtocol.STATE_CONNECTING)
                    } else {
                        stopVpn()
                        replyState(replyTo, VpnControlProtocol.MSG_DISCONNECT, VpnControlProtocol.STATE_DISCONNECTED)
                    }
                }
                VpnControlProtocol.MSG_QUERY_STATE -> {
                    replyState(msg.replyTo, VpnControlProtocol.MSG_QUERY_STATE, currentState())
                }
                VpnControlProtocol.MSG_PREPARE -> {
                    val subUrl = msg.data?.getString(VpnControlProtocol.KEY_SUBSCRIPTION_URL)
                    scope.launch { warmUp(subUrl) }
                }
                VpnControlProtocol.MSG_QUERY_NODES -> {
                    replyNodes(msg.replyTo)
                }
                VpnControlProtocol.MSG_SELECT_NODE -> {
                    msg.data.getString(VpnControlProtocol.KEY_SELECT_NODE_NAME)?.let { selectNode(it) }
                }
            }
        }
    }

    /** 节点组名字不是写死的——不同来源的订阅配置分组名不一样，取
     * queryGroupNames(excludeNotSelectable=true) 的第一个当"选节点"用的那一组，
     * 这是 Clash 配置的常见约定（一个顶层 select 类型的组代表"选哪个节点"）。
     * fallback_direct.yaml 里就是这么定义的（"節點選擇"）,真实订阅接入后
     * （第 6 步范围之外）如果分组结构不一样,这里可能需要调整,先用这个通用假设。
     *
     * **真机踩过的坑**：mode: direct 时这里永远返回空——不是 API 用错了,是
     * mihomo 核心在 direct 模式下压根不会把 proxy-groups 接进任何流量路径,
     * queryGroupNames/queryGroup 无论 excludeNotSelectable 传 true 还是 false
     * 都查不到。fallback_direct.yaml 改成了 mode: rule + 一条只匹配假域名、
     * 永远不会命中的规则指向这个组（其余流量走 MATCH,DIRECT，真实设备流量
     * 不受影响，也验证过 ping 不丢包），这样组才会被 mihomo 真正初始化、
     * 才能查到。真实订阅接入后 rules 会是订阅自己定义的，届时这个组自然会
     * 被真实规则引用，不需要这种"假域名占位"手法。 */
    private fun selectorGroupName(): String? =
        runCatching { Clash.queryGroupNames(true) }
            .onFailure { Log.e(TAG, "queryGroupNames 失败", it) }
            .getOrNull()?.firstOrNull()

    private fun replyNodes(replyTo: Messenger?) {
        replyTo ?: return
        val names = ArrayList<String>()
        var selectedIndex = -1
        // 只有 running（配置已经真正 Clash.load() 过）才查——没连接过的话根本
        // 没有配置加载进 native 核心，这时候调 queryGroupNames/queryGroup 是未定义
        // 行为，没有把握过，不冒险调用，直接回空列表。这是第 6 步范围内的一个已知
        // 简化：真实产品应该是"App 一启动就加载配置，不必等用户点连接"（milkdns 的
        // ConfigurationModule 就是这么做的），FlintLine 目前还没做这一层，节点列表
        // 只有连接之后才能看——留给后面需要时再补。
        if (running) {
            selectorGroupName()?.let { group ->
                runCatching { Clash.queryGroup(group, ProxySort.Default) }
                    .onFailure { Log.e(TAG, "queryGroup($group) 失败", it) }
                    .getOrNull()?.let {
                        names.addAll(it.proxies.map { p -> p.name })
                        selectedIndex = names.indexOf(it.now)
                    }
            }
        }
        val reply = Message.obtain(null, VpnControlProtocol.MSG_QUERY_NODES)
        reply.data = Bundle().apply { putStringArrayList(VpnControlProtocol.KEY_NODE_NAMES, names) }
        reply.arg1 = selectedIndex
        runCatching { replyTo.send(reply) }
    }

    private fun selectNode(name: String) {
        if (!running) return
        val group = selectorGroupName() ?: return
        runCatching { Clash.patchSelector(group, name) }
            .onFailure { Log.e(TAG, "切换节点失败: $name", it) }
    }

    @Volatile
    private var connecting = false

    private fun currentState(): Int = when {
        running -> VpnControlProtocol.STATE_CONNECTED
        connecting -> VpnControlProtocol.STATE_CONNECTING
        else -> VpnControlProtocol.STATE_DISCONNECTED
    }

    private fun replyState(replyTo: Messenger?, what: Int, state: Int) {
        replyTo ?: return
        val reply = Message.obtain(null, what)
        reply.arg1 = state
        reply.arg2 = if (state == VpnControlProtocol.STATE_CONNECTED) profileSource else VpnControlProtocol.PROFILE_NONE
        runCatching { replyTo.send(reply) }
    }

    // ---- 预热（MSG_PREPARE）：把"按下连接后要等很久"的两大头提前做掉 ----
    // M6（armv7）真机测过：从按下到"已連接"最长 12 秒，大头是 libclash.so 首次加载 +
    // nativeInit（几秒）和订阅下载（1~2 秒，国内网络波动时更久）。App 一启动、paypay
    // 注册拿到订阅链接后就预热，真正按连接时只剩 Clash.load + establish + startTun。
    private val prepareMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var coreWarmed = false
    @Volatile private var preparedUrl: String? = null
    @Volatile private var preparedAt = 0L
    @Volatile private var preparedSource = VpnControlProtocol.PROFILE_NONE

    private fun profileDir(): File = File(filesDir, "flint_profile").apply { mkdirs() }

    private suspend fun warmUp(subscriptionUrl: String?) {
        prepareMutex.withLock {
            warmCore()
            if (!subscriptionUrl.isNullOrBlank() && !isPreparedFresh(subscriptionUrl)) {
                preparedSource = prepareProfile(profileDir(), subscriptionUrl)
                preparedUrl = subscriptionUrl
                preparedAt = System.currentTimeMillis()
            }
        }
    }

    /** 触发 Bridge 的 System.loadLibrary + nativeInit（首次访问 Clash 对象时发生）。 */
    private fun warmCore() {
        if (coreWarmed) return
        val t = System.currentTimeMillis()
        runCatching { Clash.queryTunnelState() }
            .onFailure { Log.w(TAG, "warmCore 失败（不影响后续连接）", it) }
        coreWarmed = true
        Log.i(TAG, "核心预热完成，耗时 ${System.currentTimeMillis() - t}ms")
    }

    /** 同一条订阅链接、10 分钟内下载过、文件还在 → 连接时不再重新下载。 */
    private fun isPreparedFresh(url: String): Boolean =
        preparedUrl == url &&
            preparedSource == VpnControlProtocol.PROFILE_SUBSCRIPTION &&
            System.currentTimeMillis() - preparedAt < PREPARED_TTL_MS &&
            File(profileDir(), "config.yaml").exists()

    /** 当前 Clash.load() 进去的配置来源（PROFILE_*），随状态回复带给 UI。 */
    @Volatile
    private var profileSource = VpnControlProtocol.PROFILE_NONE

    /** 预热阶段是否已把配置 Clash.load() 进核心。true 时点连接跳过 load，避开首次 load 崩。 */
    @Volatile
    private var profileLoaded = false

    /**
     * 把要交给 Clash.load() 的配置目录准备好，返回配置来源。优先级：
     *   1. 用 mihomo 核心自带的 fetchAndValid 把 [subscriptionUrl] 下载到
     *      configDir/config.yaml 并校验（下载 + 解析 + 校验都在 native 侧，跟
     *      milkdns 的 ProfileProcessor 走的同一个入口）——成功就是真实节点配置。
     *   2. 下载失败（断网 / paypay 挂了）但目录里还有上次下载成功的 config.yaml
     *      → 沿用它，标记来源仍然是 SUBSCRIPTION（是真节点，只是可能旧）。
     *   3. 什么都没有 → 从 assets 复制直连兜底配置，来源 FALLBACK——这份配置不代理
     *      任何流量，只保证 TUN 能起来、App 不崩。
     *
     * 订阅下载走的是 :vpn 进程自己的网络（此时 TUN 还没建立，走普通 WiFi），
     * 必须在 establish() 之前完成——这是 startVpn() 顺序约束的一部分。
     */
    private suspend fun prepareProfile(configDir: File, subscriptionUrl: String?): Int {
        val configFile = File(configDir, "config.yaml")
        val downloadedMarker = File(configDir, ".from_subscription")

        if (!subscriptionUrl.isNullOrBlank()) {
            // 关键:下载到【独立临时目录】,校验通过且确认有真实节点后,才原子替换正式 config.yaml。
            // 绝不让一次坏/空下载(如后端账号无节点时返回 proxies:[])覆盖污染上次的好缓存——
            // 那会让 tier-2"沿用缓存"加载到空配置、核心加载失败、lockdown 下盒子彻底离线
            // (2026-09-23 GTBOX 真机踩到:后端返回 `proxies: []` → 盒子断网,见 TEST 文档)。
            val dlDir = File(filesDir, "flint_profile_dl").apply { mkdirs() }
            val dlConfig = File(dlDir, "config.yaml")
            try {
                dlConfig.delete()
                val deferred = Clash.fetchAndValid(dlDir, subscriptionUrl, true) { status ->
                    Log.d(TAG, "fetch subscription: ${status.action} ${status.args}")
                }
                // 下载卡住不能让用户在"連接中"干等：超时就退回上次的订阅/兜底。
                // 下载进的是临时目录,超时/失败都不会碰正式 config.yaml。
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { deferred.await() }
                    ?: throw IllegalStateException("订阅下载超时 ${FETCH_TIMEOUT_MS}ms")
                // fetchAndValid 通过 ≠ 一定有节点:后端可能返回合法 YAML 但 proxies 为空
                // (mihomo 之后 load 会炸"does not contain proxies")。这里显式挡住空配置。
                if (!hasRealProxies(dlConfig)) {
                    throw IllegalStateException("订阅无可用节点(proxies 为空),不覆盖好缓存")
                }
                // 有真实节点 → 原子替换正式配置(先写临时同目录再 rename,避免半写)。
                val staged = File(configDir, "config.yaml.staged")
                dlConfig.copyTo(staged, overwrite = true)
                if (!staged.renameTo(configFile)) {
                    staged.copyTo(configFile, overwrite = true); staged.delete()
                }
                downloadedMarker.writeText(subscriptionUrl)
                Log.i(TAG, "订阅下载并校验成功(有真实节点)-> ${configFile.absolutePath}")
                return VpnControlProtocol.PROFILE_SUBSCRIPTION
            } catch (e: Exception) {
                Log.w(TAG, "订阅下载/校验失败(不动好缓存): ${e.message}")
            } finally {
                dlDir.deleteRecursively()
            }
        } else {
            Log.w(TAG, "没有订阅链接（paypay 注册还没成功？），无法下载真实配置")
        }

        // tier-2:上次下载成功的好缓存。必须【重新校验有真实节点】——历史上可能被旧版本
        // 的坏下载污染过(空 proxies),不能盲目沿用,否则又是加载失败 + 断网。
        if (configFile.exists() && downloadedMarker.exists() && hasRealProxies(configFile)) {
            Log.i(TAG, "沿用上次下载成功的订阅配置")
            return VpnControlProtocol.PROFILE_SUBSCRIPTION
        }

        // tier-3:assets 直连兜底。占位 proxies 满足 mihomo 校验、实则全 MATCH,DIRECT——
        // 保证 TUN 一定能起来、盒子上网正常,即使 lockdown 开着也【绝不断网】。
        assets.open("fallback_direct.yaml").use { input ->
            configFile.outputStream().use { output -> input.copyTo(output) }
        }
        downloadedMarker.delete()
        Log.w(TAG, "订阅无节点/不可用 → 使用 assets 直连兜底(不代理,但保证不断网)")
        return VpnControlProtocol.PROFILE_FALLBACK
    }

    /**
     * 配置里是否有【真实可用节点】。mihomo 硬性要求 proxies 非空,否则 Clash.load 直接失败。
     * 后端在账号无节点时会返回合法 YAML 但 `proxies: []`——必须在加载前挡掉,回落直连兜底。
     * 判据:存在至少一个内联节点(有 `server:` 行)。assets 兜底的占位节点也含 server: → 可加载。
     */
    private fun hasRealProxies(config: File): Boolean = try {
        config.exists() && config.readText().lineSequence().any {
            it.trimStart().startsWith("server:")
        }
    } catch (_: Exception) {
        false
    }

    private val messenger = Messenger(handler)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action flags=$flags startId=$startId running=$running connecting=$connecting")
        // GUI 发起的 startService 带 ACTION_GUI_ATTACH:GUI 随后会自己经 Messenger 发
        // MSG_CONNECT(且带 replyTo,拿得到最终态),这里不重复拉起,免得双连接、
        // 也免得最终 CONNECTED 回不到 GUI。
        if (action == ACTION_GUI_ATTACH) return START_STICKY
        // 其余来源 = always-on/开机:系统在 launcher 之前 startService 起本服务,
        // 此刻没有 GUI,onStartCommand 依据落盘的"上次是否开着"自己决定要不要连。
        //
        maybeAutoConnect()
        return START_STICKY
    }

    /**
     * 开机自启的自动连接:仅当用户上次是"开"着的(VpnPrefs.shouldBeOn)才连。
     * 用缓存的订阅 URL(可能为 null → startVpn 内部走"缓存 config.yaml / 兜底直连"
     * 三级回退,保证 TUN 总能起来,老人盒子绝不因此永久断网)。
     */
    private fun maybeAutoConnect() {
        if (running || connecting) {
            Log.i(TAG, "autostart: 已在连/已连,跳过")
            return
        }
        if (!VpnPrefs.shouldBeOn(this)) {
            Log.i(TAG, "autostart: 开关为关,不自动连")
            return
        }
        connecting = true
        val url = SubscriptionProvider.getSubscriptionUrl(this)
        Log.i(TAG, "autostart: 开关为开,自动拉起连接 hasCachedUrl=${url != null}")
        scope.launch {
            val ok = try { startVpn(url) } finally { connecting = false }
            Log.i(TAG, "autostart: 自动连接结果 ok=$ok")
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onRevoke() {
        // 用户在系统设置里（不是 App 内）把 VPN 权限收回时系统直接调这个，
        // 不会经过 MSG_DISCONNECT——必须在这里也做一次清理，否则 TUN fd 泄漏。
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * @return 是否成功建立起 TUN + 加载核心配置。
     *
     * 执行顺序是第 5 步真机事故（见 DEVLOG）之后特意调整过的，硬性要求：
     * 所有"可能失败、且不需要 TUN fd"的步骤（加载/校验配置，会触发 Bridge 的
     * native 库首次初始化）必须排在 `establish()` **之前**——`establish()`
     * 一旦成功，Android 立刻在系统层面把流量路由进这个 fd，这一步和
     * "native 核心真正开始读这个 fd"（`Clash.startTun()`）之间必须不插入任何
     * 其它可能失败的操作，中间窗口期越长，"TUN 建立了但没人读、流量进黑洞"
     * 的风险越大。`startForeground()`（只是发通知，几乎不会失败但也挪到最后
     * 保险)同理挪到 `startTun()` 成功之后。
     */
    private suspend fun startVpn(subscriptionUrl: String?): Boolean {
        if (running) return true

        // 1) 先做完全不需要 TUN fd 的部分：把兜底配置文件准备好、交给 native
        //    核心加载校验。这一步会触发 Bridge 的 System.loadLibrary + JNI
        //    init（第一次访问 Clash/Bridge 对象),是整条链路里失败面最大的一步
        //    ——必须在 establish() 之前做完,不能让"TUN 已建立但核心没起来"
        //    这种状态出现。
        try {
            // 真机实测踩出来的坑：Clash.load(path) 的 path 不是"配置文件本身"，
            // native 侧把它当"配置目录"，会去这个目录下找一个固定文件名
            // config.yaml（真机报错 "open .../flint_fallback.yaml/config.yaml:
            // not a directory" 证实了这一点——milkdns 那边同理，传的也是
            // ctx.filesDir.resolve("profiles/xxx") 这种目录,不是 .yaml 文件路径,
            // 之前想当然照着"文件路径"理解了,是错的）。
            val configDir = profileDir()
            // 先把真实订阅下载成 config.yaml（失败才退回兜底），再交给核心加载。
            // 预热已经下载过且还新鲜的话直接用，省掉一次下载；Mutex 保证不跟正在
            // 进行的预热并发写同一个 config.yaml。
            prepareMutex.withLock {
                warmCore()
                if (profileLoaded) {
                    // 预热阶段已经把配置 load 进核心 → 直接复用，不再 load，绕开首次 load 崩点。
                    Log.i(TAG, "核心已在预热阶段加载好配置，跳过 load")
                    profileSource = preparedSource
                } else {
                    // 兜底：预热还没加载好（用户抢在预热完成前点了连接）——这里加载。
                    profileSource = if (!subscriptionUrl.isNullOrBlank() && isPreparedFresh(subscriptionUrl)) {
                        Log.i(TAG, "使用预热阶段下载好的订阅配置")
                        preparedSource
                    } else {
                        prepareProfile(configDir, subscriptionUrl).also {
                            preparedSource = it
                            preparedUrl = subscriptionUrl
                            preparedAt = System.currentTimeMillis()
                        }
                    }
                    Clash.load(configDir).await()
                    profileLoaded = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载核心配置失败，不建立 TUN", e)
            return false
        }

        // 2) 配置已经加载好，核心已经初始化好了，这时候才建立 TUN——
        //    bypass_private_route 排除私网段，不吞设备自己的局域网/调试连接
        //    （事故原因见类注释）。
        val pfd = try {
            with(Builder()) {
                addAddress(tunGateway(), tunPrefix())
                resources.getStringArray(R.array.bypass_private_route).forEach { cidr ->
                    val (ip, prefix) = cidr.split("/", limit = 2)
                    addRoute(ip, prefix.toInt())
                }
                addDnsServer(tunDns())
                addRoute(tunDns(), 32)
                setBlocking(false)
                setMtu(tunMtu())
                setSession(getString(R.string.app_name))
                setConfigureIntent(
                    PendingIntent.getActivity(
                        this@FlintVpnService,
                        0,
                        Intent(this@FlintVpnService, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                establish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }

        if (pfd == null) {
            Log.e(TAG, "VPN 系统拒绝了 establish()（权限没给 / 被另一个 VPN 抢占）")
            return false
        }

        // 3) 立刻把 fd 交给 native 核心——这中间不再插入任何其它操作。失败就
        //    显式关掉这个 ParcelFileDescriptor（还没 detachFd() 之前，Java 侧
        //    仍然持有所有权，close() 就能让系统清掉这段 VPN 路由），不能指望
        //    stopVpn() 里的 Clash.stopTun() 兜底——那个函数在 native tun 从
        //    没启动过的情况下什么也不会做，这正是事故当时的漏洞。
        var fd: Int? = null
        return try {
            fd = pfd.detachFd()
            tunFd = fd
            Clash.startTun(
                fd = fd,
                stack = tunStack(),
                gateway = "${tunGateway()}/${tunPrefix()}",
                portal = tunPortal(),
                dns = tunDns(),
                markSocket = ::protect,
                querySocketUid = { _, _, _ -> -1 },
            )
            running = true
            startForeground(NOTIF_ID, buildNotification())
            Log.i(TAG, "VPN 已连接，配置来源=" + when (profileSource) {
                VpnControlProtocol.PROFILE_SUBSCRIPTION -> "真实订阅"
                VpnControlProtocol.PROFILE_FALLBACK -> "直连兜底（不代理任何流量）"
                else -> "未知"
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动 mihomo 核心失败", e)
            // detachFd() 之后 pfd 自身已经失效，close() 拿不回 fd 的所有权了——
            // 必须用 adoptFd() 把裸 fd 重新包回 ParcelFileDescriptor 才能真正关掉，
            // 不然就是当时事故那种"fd 已经交出去但没人关"的泄漏。fd 还是 null
            // 说明连 detachFd() 都没跑到，是 pfd 自己没关过，也要关。
            runCatching {
                if (fd != null) ParcelFileDescriptor.adoptFd(fd).close() else pfd.close()
            }
            tunFd = null
            false
        }
    }

    private fun stopVpn() {
        if (running) {
            runCatching { Clash.stopTun() }
        }
        running = false
        profileSource = VpnControlProtocol.PROFILE_NONE
        profileLoaded = false   // 断开后核心配置状态清掉，下次连接前重新走预热加载
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FlintVpnService"
        /** GUI 发起 startService 时带的 action,用来和 always-on/开机的系统启动区分开。 */
        const val ACTION_GUI_ATTACH = "com.flintline.app.action.GUI_ATTACH"
        private const val CHANNEL_ID = "flint_vpn"
        private const val NOTIF_ID = 1
        private const val FETCH_TIMEOUT_MS = 20_000L
        private const val PREPARED_TTL_MS = 10 * 60_000L

        private fun tunMtu() = AppConfig.tunMtu
        private fun tunPrefix() = AppConfig.tunPrefix
        private fun tunGateway() = AppConfig.tunGateway
        private fun tunPortal() = AppConfig.tunPortal
        private fun tunDns() = tunPortal()
        private fun tunStack() = AppConfig.tunStack
    }
}
