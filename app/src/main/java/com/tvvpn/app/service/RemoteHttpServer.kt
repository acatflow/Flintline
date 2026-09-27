package com.tvvpn.app.service

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * 极简裸 Socket HTTP 服务（**不引第三方库，教学清晰**）。只服务三条路由，全部**强制 token=PIN**：
 *  - `GET /?token=<PIN>`        → 内嵌观看/操控页（HTML）
 *  - `GET /stream?token=<PIN>`  → multipart/x-mixed-replace 的 MJPEG 屏幕流
 *  - `POST /input?token=<PIN>`  → 表单体 type=tap&x=..&y=.. 等，转交无障碍注入
 *
 * token 不符一律 403。坐标用 **归一化 (0..1)** 传输，与显示尺寸无关，服务端乘以真实屏幕像素。
 * 仅监听局域网、无 TLS——README 已声明「仅限可信局域网，风险自负」。
 *
 * [host] 提供最新帧与真实屏幕尺寸；输入注入直接调 [RemoteInputAccessibilityService]。
 */
class RemoteHttpServer(
    private val port: Int,
    private val pin: String,
    private val host: Host,
) {
    interface Host {
        fun latestFrame(): ByteArray?
        /** 真实屏幕像素尺寸 (宽,高)，用于把归一化坐标还原成注入坐标。 */
        fun screenSize(): Pair<Int, Int>
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "remote-http-accept") {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                Log.i(TAG, "远程桌面 HTTP 监听 :$port")
                while (running) {
                    val socket = try { ss.accept() } catch (e: Exception) { if (running) Log.w(TAG, "accept 失败: ${e.message}"); break }
                    thread(name = "remote-http-conn") { handle(socket) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "服务启动失败: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                val params = parseQuery(query)

                // 读 headers（拿 Content-Length，供 POST 读体）
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                        contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                    }
                }

                // —— 强制鉴权：token 必须等于 PIN ——
                if (params["token"] != pin) {
                    writeText(s.getOutputStream(), "403 Forbidden", "invalid token", 403)
                    return
                }

                when {
                    method == "GET" && path == "/" -> writeText(s.getOutputStream(), "200 OK", viewerHtml(pin), 200, "text/html; charset=utf-8")
                    method == "GET" && path == "/stream" -> streamMjpeg(s.getOutputStream())
                    method == "POST" && path == "/input" -> {
                        val body = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val r = reader.read(body, read, contentLength - read); if (r < 0) break; read += r
                        }
                        handleInput(parseQuery(String(body, 0, read)))
                        writeText(s.getOutputStream(), "200 OK", "ok", 200)
                    }
                    else -> writeText(s.getOutputStream(), "404 Not Found", "not found", 404)
                }
            } catch (e: Exception) {
                Log.w(TAG, "连接处理异常: ${e.message}")
            }
        }
    }

    private fun handleInput(p: Map<String, String>) {
        val a11y = RemoteInputAccessibilityService.instance
        if (a11y == null) { Log.w(TAG, "收到输入但无障碍未开启，忽略"); return }
        val (sw, sh) = host.screenSize()
        fun px(v: String?, span: Int) = ((v?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f) * span)
        when (p["type"]) {
            "tap" -> a11y.tap(px(p["x"], sw), px(p["y"], sh))
            "swipe" -> a11y.swipe(px(p["x"], sw), px(p["y"], sh), px(p["x2"], sw), px(p["y2"], sh), p["dur"]?.toLongOrNull() ?: 300)
            "key" -> a11y.globalAction(p["name"] ?: "")
            "text" -> a11y.inputText(p["value"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "")
            else -> Log.w(TAG, "未知输入类型: ${p["type"]}")
        }
    }

    /** MJPEG：持续把最新帧作为 multipart part 推出去，约 10fps。 */
    private fun streamMjpeg(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Pragma: no-cache\r\n" +
            "Connection: close\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
        out.write(header.toByteArray()); out.flush()
        try {
            while (running) {
                val frame = host.latestFrame()
                if (frame != null) {
                    val part = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    out.write(part.toByteArray()); out.write(frame); out.write("\r\n".toByteArray()); out.flush()
                }
                Thread.sleep(100)
            }
        } catch (_: Exception) { /* 客户端断开，正常结束 */ }
    }

    private fun writeText(out: OutputStream, status: String, body: String, code: Int, contentType: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray()
        val resp = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(resp.toByteArray()); out.write(bytes); out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('='); if (i < 0) return@mapNotNull null
            it.substring(0, i) to it.substring(i + 1)
        }.toMap()
    }

    private fun viewerHtml(pin: String): String = """
<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TV VPN 远程桌面</title>
<style>
 body{margin:0;background:#0b0a09;color:#f5f1ea;font-family:system-ui,sans-serif;text-align:center}
 #screen{max-width:100%;max-height:78vh;touch-action:none;cursor:crosshair;border:1px solid #26221c}
 .bar{padding:8px;display:flex;gap:8px;justify-content:center;flex-wrap:wrap}
 button,input{font-size:15px;padding:8px 12px;border-radius:6px;border:1px solid #f59e0b;background:#17140f;color:#fbbf24}
 .hint{color:#7a756c;font-size:12px;padding:4px}
</style></head><body>
<div class="bar">
 <button onclick="key('back')">返回</button>
 <button onclick="key('home')">主页</button>
 <button onclick="key('recents')">最近</button>
 <input id="txt" placeholder="输入文本后点发送">
 <button onclick="sendText()">发送文本</button>
</div>
<img id="screen" src="/stream?token=$pin" alt="screen">
<div class="hint">点击=点按，拖动=滑动。坐标按图片归一化。方向键(D-pad)在 rootless 无障碍下无法注入。</div>
<script>
 const PIN="$pin", img=document.getElementById('screen');
 function post(data){ data.token=PIN; fetch('/input?token='+PIN,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)}); }
 function norm(e){ const r=img.getBoundingClientRect(); const cx=(e.touches?e.touches[0].clientX:e.clientX); const cy=(e.touches?e.touches[0].clientY:e.clientY); return {x:((cx-r.left)/r.width).toFixed(4), y:((cy-r.top)/r.height).toFixed(4)}; }
 let down=null;
 img.addEventListener('mousedown',e=>{down=norm(e);});
 img.addEventListener('mouseup',e=>{ if(!down)return; const up=norm(e); const dx=Math.abs(up.x-down.x),dy=Math.abs(up.y-down.y);
   if(dx<0.02&&dy<0.02){ post({type:'tap',x:down.x,y:down.y}); } else { post({type:'swipe',x:down.x,y:down.y,x2:up.x,y2:up.y,dur:300}); } down=null; });
 function key(n){ post({type:'key',name:n}); }
 function sendText(){ const v=document.getElementById('txt').value; post({type:'text',value:encodeURIComponent(v)}); }
</script>
</body></html>
""".trimIndent()

    companion object { private const val TAG = "RemoteHttpServer" }
}
