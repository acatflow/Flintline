package com.tvvpn.app.net

import android.content.Context
import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 老安卓（<7.1.1 / 部分 API23~24 机型）系统 CA 库沒有 Let's Encrypt 的 ISRG Root X1，
 * 早年靠 DST Root X3 交叉签名兼容，但该交叉签名 2024 已过期 → 这些盒子验证 oldtools 的
 * TLS 证书失败（CertPathValidatorException: Trust anchor not found），註冊/拉配置/OTA 全断。
 *
 * 修复：把全量 CA（assets/ca-bundle.pem，certifi 121 根，含 ISRG X1/X2）内置进 App，装一个
 * **合成信任链**——「系统锚点 ∪ 内置锚点」。纯增量：先按系统校验，失败再按内置校验，**两者都不过
 * 就抛异常拒连**（不做 trust-all、不碰 hostname 校验，fail-closed）。全 API 生效（含 API23，
 * 不依赖只有 API24+ 才认的 network_security_config）。install() 全程 try/catch，任何异常都退回
 * 系统默认 = 与不装此修复完全一致，绝不更差，绝不因证书逻辑让开机崩溃。
 *
 * 覆盖面：install() 里 setDefaultSSLSocketFactory 覆盖所有 HttpsURLConnection。若日后引入
 * OkHttp，它不读该默认，需各 builder 显式注入 TlsCompat.socketFactory + TlsCompat.trustManager。
 * install() 在 FlintLineApp.onCreate 最早调用。
 */
object TlsCompat {
    private const val TAG = "TlsCompat"

    @Volatile private var installed = false

    // 默认 = 纯系统信任（与今天行为一致）。install 成功后替换为合成信任链。
    // 任一 builder 在 install 之前读取也拿到有效对象，绝不为 null。
    @Volatile private var tm: X509TrustManager = systemTrustManager()
    @Volatile private var sf: SSLSocketFactory = factoryFor(tm)

    val trustManager: X509TrustManager get() = tm
    val socketFactory: SSLSocketFactory get() = sf

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        try {
            val merged = CompositeX509TrustManager(
                primary = systemTrustManager(),
                secondary = bundledCaTrustManager(context.applicationContext),
            )
            val factory = factoryFor(merged)
            tm = merged
            sf = factory
            // 覆盖全进程所有 HttpsURLConnection（未自设 factory 的都用它）。
            HttpsURLConnection.setDefaultSSLSocketFactory(factory)
            installed = true
            Log.i(TAG, "installed composite trust (system ∪ ISRG X1/X2)")
        } catch (e: Throwable) {
            // 保持系统默认。宁可老盒子继续报证书错，也绝不让证书逻辑崩掉 onCreate。
            Log.w(TAG, "install failed, keep system default: ${e.message}")
        }
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun bundledCaTrustManager(context: Context): X509TrustManager {
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        // 用 assets/ca-bundle.pem（certifi 全量 121 根，覆盖所有主流 CA，防将来域名/CDN 换非 LE 证书）。
        // 逐块解析：Android Conscrypt 对"拼接多证书 PEM"的 generateCertificates()(复数)会静默返空，
        // 必须按 BEGIN/END 切单块、每块 generateCertificate()(单数)。
        val pem = context.assets.open("ca-bundle.pem").use { it.readBytes() }.toString(Charsets.US_ASCII)
        var count = 0
        Regex("-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)
            .findAll(pem).forEach { m ->
                runCatching {
                    val cert = java.io.ByteArrayInputStream(m.value.toByteArray(Charsets.US_ASCII))
                        .use { cf.generateCertificate(it) as X509Certificate }
                    ks.setCertificateEntry("ca_$count", cert)
                    count++
                }
            }
        Log.i(TAG, "bundled CA loaded: $count certs")
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun factoryFor(trust: X509TrustManager): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trust), null)
        return ctx.socketFactory
    }

    /** 系统锚点 ∪ 内置 ISRG 锚点。fail-closed：两者都不信才抛。 */
    private class CompositeX509TrustManager(
        private val primary: X509TrustManager,
        private val secondary: X509TrustManager,
    ) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                primary.checkServerTrusted(chain as Array<X509Certificate>, authType)
            } catch (e: CertificateException) {
                // 系统不认（老盒子缺 ISRG 根）→ 用内置 ISRG 再验；仍不过则抛出、拒连。
                secondary.checkServerTrusted(chain, authType)
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            primary.checkClientTrusted(chain as Array<X509Certificate>, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            primary.acceptedIssuers + secondary.acceptedIssuers
    }
}
