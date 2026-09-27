package com.flintline.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 主进程这边用的小封装——把"跟 :vpn 进程里的 FlintVpnService 用 Messenger 通讯"这坨
 * 样板代码收在一起，Compose UI 只需要 `state: StateFlow<State>`（未连接/連接中/已连接）+
 * `connectVpn()`/`disconnectVpn()`/`prepare()`，不用直接摸 Messenger/ServiceConnection。
 */
class VpnClient(context: Context) {
    private val appContext = context.applicationContext

    private var serviceMessenger: Messenger? = null

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val stateFlow = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = stateFlow

    /** 一次连接尝试以失败告终（CONNECT 请求在"連接中"之后回了 DISCONNECTED），
     * UI 用来弹"連接失敗"提示。用 SharedFlow 而不是 State：它是事件不是状态。 */
    private val connectFailedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connectFailed: SharedFlow<Unit> = connectFailedFlow

    data class NodeList(val names: List<String>, val selectedIndex: Int)

    private val nodesFlow = MutableStateFlow(NodeList(emptyList(), -1))
    val nodes: StateFlow<NodeList> = nodesFlow

    // 自动重连:核心首次 Clash.load 在部分 32 位老盒子上必崩(:vpn 进程 SIGABRT),但
    // 重启后"第二次"就正常。这里把用户手动的"关崩溃框+再点一次"自动化——连接过程中
    // :vpn 崩(onServiceDisconnected 且仍处 CONNECTING)→ 系统重启 :vpn(onServiceConnected)
    // → 自动重发连接。限次数,避免真·连不上时无限循环。
    @Volatile private var autoReconnectUrl: String? = null
    @Volatile private var needReconnect = false
    @Volatile private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            VpnControlProtocol.MSG_QUERY_NODES -> {
                // 用 Message.data（Bundle）不用 Message.obj——原因见
                // VpnControlProtocol.MSG_QUERY_NODES 的注释，obj 跨进程传输
                // 会把非 Parcelable 的 ArrayList<String> 静默丢成 null。
                val names = msg.data.getStringArrayList(VpnControlProtocol.KEY_NODE_NAMES) ?: arrayListOf()
                nodesFlow.value = NodeList(names, msg.arg1)
            }
            else -> {
                val newState = when (msg.arg1) {
                    VpnControlProtocol.STATE_CONNECTED -> State.CONNECTED
                    VpnControlProtocol.STATE_CONNECTING -> State.CONNECTING
                    else -> State.DISCONNECTED
                }
                if (newState == State.CONNECTED) {
                    // 连上了：清掉自动重连意图,不再重连
                    autoReconnectUrl = null; needReconnect = false; reconnectAttempts = 0
                }
                if (msg.what == VpnControlProtocol.MSG_CONNECT &&
                    stateFlow.value == State.CONNECTING && newState == State.DISCONNECTED
                ) {
                    connectFailedFlow.tryEmit(Unit)
                }
                stateFlow.value = newState
            }
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = binder?.let { Messenger(it) }
            // 真机踩过的坑：queryNodes() 如果在 bind() 发起后立刻调用（比如
            // Compose LaunchedEffect(Unit) 跟 DisposableEffect(Unit) 同一帧触发），
            // 这时候 bindService() 还没异步回调完成，serviceMessenger 还是 null，
            // send() 直接静默丢弃、永远等不到回复——界面卡在"暫無可用節點"，
            // 不报错也不重试。真正安全的时机就是这里：onServiceConnected 回调
            // 触发的这一刻，serviceMessenger 刚刚赋值完成，此时查询保证能发出去。
            send(VpnControlProtocol.MSG_QUERY_STATE)
            send(VpnControlProtocol.MSG_QUERY_NODES)
            // paypay 注册可能比 bind 更早完成，那时 prepare() 发不出去——补发
            pendingPrepareUrl?.let { prepare(it) }
            // :vpn 崩溃重启后回到这里 → 自动重发连接(相当于用户手动的"第二次")
            if (needReconnect) {
                needReconnect = false
                reconnectAttempts++
                val url = autoReconnectUrl
                mainHandler.postDelayed({
                    if (autoReconnectUrl != null) {
                        android.util.Log.i("VpnClient", "崩溃后自动重连(第 $reconnectAttempts 次)")
                        connectVpn(url)
                    }
                }, 800)  // 给 :vpn 重启后核心初始化一点时间
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            // 连接过程中 :vpn 崩了 → 标记等重启后自动重连(限 2 次)
            if (stateFlow.value == State.CONNECTING && autoReconnectUrl != null && reconnectAttempts < 2) {
                needReconnect = true
            }
        }
    }

    fun bind() {
        appContext.bindService(
            Intent(appContext, FlintVpnService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        runCatching { appContext.unbindService(connection) }
        serviceMessenger = null
    }

    /** 调用前必须已经过 VpnService.prepare() 拿到系统授权——这个类不管权限申请，
     * 权限流程是 Activity 的事（需要 startActivityForResult）。 */
    fun connectVpn(subscriptionUrl: String?) {
        if (stateFlow.value == State.CONNECTING) return
        // 记录本次连接意图,供 :vpn 崩溃后自动重连使用(见 onServiceDisconnected/Connected)
        autoReconnectUrl = subscriptionUrl
        // 落盘"用户要开着"——开机自启的地基:下次开机 always-on 在 launcher 前
        // 起服务时,onStartCommand 靠这个决定自动连(见 FlintVpnService.maybeAutoConnect)。
        VpnPrefs.setShouldBeOn(appContext, true)
        // 带 ACTION_GUI_ATTACH:告诉 onStartCommand"这次是 GUI 在驱动,别自己拉起",
        // 连接由紧接着的 MSG_CONNECT 发起(拿得到最终态回 GUI)。
        appContext.startService(
            Intent(appContext, FlintVpnService::class.java).setAction(FlintVpnService.ACTION_GUI_ATTACH),
        )
        // 本地先置成 CONNECTING：按下的瞬间界面就变，不等 Service 回包（Binder 往返
        // 加上 :vpn 进程可能还没起来，会有几百毫秒空档，用户这时候再按一下就重复了）。
        stateFlow.value = State.CONNECTING
        val msg = Message.obtain(null, VpnControlProtocol.MSG_CONNECT)
        msg.replyTo = replyMessenger
        msg.data = Bundle().apply { putString(VpnControlProtocol.KEY_SUBSCRIPTION_URL, subscriptionUrl) }
        val target = serviceMessenger
        if (target == null || runCatching { target.send(msg) }.isFailure) {
            // 还没 bind 上 / 发送失败：退回未连接，让用户再按一次
            stateFlow.value = State.DISCONNECTED
        }
    }

    /** 预热：提前初始化核心 + 下载订阅（见 VpnControlProtocol.MSG_PREPARE）。 */
    @Volatile private var pendingPrepareUrl: String? = null

    fun prepare(subscriptionUrl: String?) {
        subscriptionUrl ?: return
        val target = serviceMessenger
        if (target == null) {
            pendingPrepareUrl = subscriptionUrl
            return
        }
        val msg = Message.obtain(null, VpnControlProtocol.MSG_PREPARE)
        msg.data = Bundle().apply { putString(VpnControlProtocol.KEY_SUBSCRIPTION_URL, subscriptionUrl) }
        if (runCatching { target.send(msg) }.isSuccess) pendingPrepareUrl = null
    }

    fun queryState() {
        send(VpnControlProtocol.MSG_QUERY_STATE)
    }

    fun disconnectVpn() {
        // 用户主动断开 → 取消一切自动重连意图
        autoReconnectUrl = null; needReconnect = false; reconnectAttempts = 0
        // 落盘"用户要关"——下次开机 onStartCommand 就不会自动拉起(见 maybeAutoConnect)
        VpnPrefs.setShouldBeOn(appContext, false)
        send(VpnControlProtocol.MSG_DISCONNECT)
    }

    fun queryNodes() {
        send(VpnControlProtocol.MSG_QUERY_NODES)
    }

    /** 切换节点后没有单独的确认回复（保持协议简单，见 VpnControlProtocol 注释），
     * 调用方自己紧接着再 queryNodes() 一次来拿到切换后的最新状态。 */
    fun selectNode(name: String) {
        val msg = Message.obtain(null, VpnControlProtocol.MSG_SELECT_NODE)
        msg.data = Bundle().apply { putString(VpnControlProtocol.KEY_SELECT_NODE_NAME, name) }
        runCatching { serviceMessenger?.send(msg) }
    }

    private fun send(what: Int) {
        val msg = Message.obtain(null, what)
        msg.replyTo = replyMessenger
        runCatching { serviceMessenger?.send(msg) }
    }
}
