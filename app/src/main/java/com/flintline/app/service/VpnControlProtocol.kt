package com.flintline.app.service

/**
 * MainActivity（主进程）跟 FlintVpnService（`:vpn` 独立进程）之间的 IPC 协议——
 * 两者是不同进程，UI 不能直接调 Clash.* 方法（native 核心的状态只在 `:vpn`
 * 进程里），必须靠 Binder 消息传递。用最轻量的 Messenger（Android 内置，不需要
 * 写 AIDL），够用——FlintLine 只有三个动作，不需要 AIDL 那一套接口版本管理。
 */
object VpnControlProtocol {
    /** UI -> Service：连接。Message.data（Bundle）里可选带 KEY_SUBSCRIPTION_URL =
     * 用户在设定里配置的订阅链接（SubscriptionProvider 持久化的那个）；Service 会先用
     * mihomo 核心的 fetchAndValid 把订阅下载成真实配置再起 TUN。没带 / 下载失败时
     * 退回上次下载成功的配置，再不行才用 assets 里的直连兜底配置（那份配置不代理
     * 任何流量，M6 盒子真机上"开了 VPN 但网络没变化"就是这个原因）。 */
    const val MSG_CONNECT = 1
    const val KEY_SUBSCRIPTION_URL = "subscriptionUrl"

    /** Service -> UI 的 CONNECT / QUERY_STATE 回复里，Message.arg2 = 当前加载的配置来源
     * （PROFILE_*），UI 用来区分"真的在走代理"和"只是直连兜底"。 */
    const val KEY_PROFILE_SOURCE = "profileSource"
    const val PROFILE_NONE = 0
    const val PROFILE_SUBSCRIPTION = 1
    const val PROFILE_FALLBACK = 2

    /** UI -> Service：断开。 */
    const val MSG_DISCONNECT = 2

    /** UI -> Service：查询当前状态，必须带 replyTo。 */
    const val MSG_QUERY_STATE = 3

    /** UI -> Service：查询节点列表，必须带 replyTo。回复用
     * Message.data（Bundle）里 KEY_NODE_NAMES = ArrayList<String>（节点名列表）
     * + Message.arg1 = 当前选中节点在列表里的下标（-1 表示没有选中/没有节点组）。
     * 空列表表示当前配置里没有可选节点组（比如还没接上真实订阅、走的是
     * fallback_direct.yaml）。
     *
     * **真机踩过的坑，别再用 Message.obj 传 List**：cross-process 的 Messenger
     * 传输里，Message.obj 只有在它本身实现 Parcelable 时才会被保留——
     * `ArrayList<String>` 不实现 Parcelable（跟"是不是能塞进 Bundle"是两回事），
     * 结果就是同进程内看着数据是对的（直接引用），一旦跨进程真通过 Binder 传输，
     * obj 会被静默丢成 null，UI 端永远收不到，且没有任何异常或错误日志——排查
     * 起来最费时间的那种坑。正确做法是用 Message.setData(Bundle)，Bundle 的
     * putStringArrayList/getStringArrayList 是专门为跨进程设计的。 */
    const val MSG_QUERY_NODES = 4
    const val KEY_NODE_NAMES = "nodeNames"

    /** UI -> Service：切换节点，Message.data（Bundle）里 KEY_SELECT_NODE_NAME =
     * 目标节点名。不强制要求 replyTo——切换结果通过调用方随后自己发一次
     * MSG_QUERY_NODES 或 MSG_QUERY_STATE 来确认，保持协议简单。**同样不能用
     * Message.obj = String**——String 也不是 Parcelable，跨进程一样会被静默
     * 丢成 null，见 MSG_QUERY_NODES 那条注释，这是真机上连续踩到的同一类坑
     * 第二次，第一次就该想到这里也有同样的问题，没想到，记录下来。 */
    const val MSG_SELECT_NODE = 5
    const val KEY_SELECT_NODE_NAME = "selectNodeName"

    /** UI -> Service：预热。Message.data 里 KEY_SUBSCRIPTION_URL = 订阅链接。Service 会
     * 提前初始化 mihomo 核心（首次 loadLibrary + nativeInit 在 armv7 盒子上要好几秒）
     * 并把订阅下载好，之后 MSG_CONNECT 只剩 Clash.load + establish，按下去一两秒就连上。
     * 幂等，随便发；没有 replyTo。 */
    const val MSG_PREPARE = 6

    /** Service -> UI：状态回复，Message.arg1 = STATE_*，
     * Message.what 复用发起请求时的 what（QUERY_STATE / CONNECT / DISCONNECT 都会回这个）。
     *
     * MSG_CONNECT 会回**两次**：收到请求立刻回 STATE_CONNECTING（UI 马上进"連接中"，
     * 期间再按 OK 一律忽略——M6 真机反馈：按了没反应就会一直按），完成后再回
     * CONNECTED（成功）或 DISCONNECTED（失败，UI 提示重试）。连接进行中收到的
     * CONNECT/DISCONNECT 都只回 STATE_CONNECTING，不重复起连接、也不中途拆。 */
    const val STATE_CONNECTED = 1
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 2
}
