package com.flintline.app.config

import android.util.Log
import java.io.IOException
import java.net.URI

private fun failureThreshold(): Int = AppConfig.failureThreshold

// 从 milkdns 原样搬过来（com.github.kr328.clash.config.EndpointManager），只改了包名，
// failover 逻辑一字未动——这段是纯业务无关的域名切换策略，跟 Nelo/FlintLine 是哪个品牌
// 无关，milkdns 那边验证过的行为（网络级失败才切换、业务错误不切换、同一 session 内
// 切换后不自动切回）原样复用。
interface LastSuccessfulEndpointStore {
    fun get(): String?
    fun set(url: String)
}

/**
 * 进程内内存实现，不做任何跨进程/跨 APP 重启的持久化——原因见 milkdns 里同一个类的
 * 注释：避免一次 failover 切到 backup 后，以后每次冷启动都"黏"在 backup 上。
 */
class InMemoryEndpointStore : LastSuccessfulEndpointStore {
    @Volatile
    private var value: String? = null
    override fun get(): String? = value
    override fun set(url: String) { value = url }
}

/** 反代自己报告的网关级故障（502/503/504）——语义是"这个 endpoint 不可用"，计入网络级失败。 */
class GatewayUnavailableException(val code: Int) : IOException("upstream unavailable: HTTP $code")

internal fun orderCandidatesWithLastSuccessfulFirst(ordered: List<String>, lastGood: String?): List<String> {
    return if (lastGood != null && ordered.contains(lastGood) && ordered.firstOrNull() != lastGood) {
        listOf(lastGood) + ordered.filter { it != lastGood }
    } else {
        ordered
    }
}

class EndpointManager internal constructor(
    rawCandidates: List<String>,
    private val store: LastSuccessfulEndpointStore,
) {
    private val candidates: List<String> =
        orderCandidatesWithLastSuccessfulFirst(rawCandidates, store.get())

    val allCandidates: List<String>
        get() = candidates

    @Volatile
    private var index = 0

    @Volatile
    private var consecutiveFailuresAtCurrentIndex = 0

    val currentBaseUrl: String?
        get() = candidates.getOrNull(index)

    val currentHost: String
        get() = candidates.getOrNull(index)
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?: "unknown"

    fun hasNext(): Boolean = index < candidates.size - 1

    @Synchronized
    fun advanceToNext(): Boolean {
        if (!hasNext()) return false
        index += 1
        consecutiveFailuresAtCurrentIndex = 0
        Log.i(TAG, "switching endpoint index=$index host=$currentHost")
        return true
    }

    fun markSuccess() {
        consecutiveFailuresAtCurrentIndex = 0
        currentBaseUrl?.let { store.set(it) }
    }

    fun <T> withFailover(onSwitching: (() -> Unit)? = null, call: (baseUrl: String) -> T): T? {
        val threshold = failureThreshold()
        var totalAttemptsLeft = candidates.size * threshold
        while (totalAttemptsLeft > 0) {
            totalAttemptsLeft -= 1
            val baseUrl = currentBaseUrl ?: return null
            try {
                val result = call(baseUrl)
                markSuccess()
                return result
            } catch (e: IOException) {
                if (!isNetworkFailure(e)) throw e
                consecutiveFailuresAtCurrentIndex += 1
                Log.w(
                    TAG,
                    "network failure host=$currentHost attempt=$consecutiveFailuresAtCurrentIndex/$threshold: ${e.javaClass.simpleName}"
                )
                if (consecutiveFailuresAtCurrentIndex < threshold) continue
                if (!advanceToNext()) return null
                onSwitching?.invoke()
            }
        }
        return null
    }

    companion object {
        private const val TAG = "EndpointManager"

        fun isNetworkFailure(e: IOException): Boolean = when (e) {
            is java.net.UnknownHostException -> true
            is java.net.ConnectException -> true
            is java.net.NoRouteToHostException -> true
            is java.net.SocketTimeoutException -> true
            is javax.net.ssl.SSLException -> true
            is GatewayUnavailableException -> true
            else -> false
        }
    }
}
