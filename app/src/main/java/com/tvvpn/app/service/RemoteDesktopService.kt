package com.tvvpn.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.tvvpn.app.R
import java.io.ByteArrayOutputStream

/**
 * 远程桌面前台服务（**rootless、需用户显式授权投屏**）。
 *
 * 流程:Activity 用 MediaProjectionManager 申请投屏 → 把 resultCode+data 通过 startForegroundService
 * 传进来 → 本服务先 startForeground（API29+ 标注 mediaProjection 类型），再 getMediaProjection →
 * VirtualDisplay + ImageReader 抓屏 → 每帧转 JPEG 存 [latestJpeg] → [RemoteHttpServer] 以 MJPEG 推给浏览器。
 *
 * 抓屏分辨率按最长边 [MAX_DIM] 缩放（省带宽/CPU）；输入坐标由客户端归一化传回、乘以**真实**屏幕像素，
 * 与抓屏缩放无关。停止时释放 projection/display/reader 并撤前台通知。
 */
class RemoteDesktopService : Service(), RemoteHttpServer.Host {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var httpServer: RemoteHttpServer? = null

    @Volatile private var latestJpeg: ByteArray? = null
    private var realW = 0
    private var realH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)

        if (resultCode == 0 || data == null) { Log.w(TAG, "缺投屏授权，停止"); stopSelf(); return START_NOT_STICKY }

        startAsForeground()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        if (proj == null) { Log.w(TAG, "getMediaProjection 返回 null"); stopSelf(); return START_NOT_STICKY }
        projection = proj
        // API34 要求注册 Callback，否则抛异常
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "投屏被系统/用户停止"); stopEverything() }
        }, null)

        startCapture()

        val pin = RemotePrefs.getOrCreatePin(this)
        httpServer = RemoteHttpServer(RemotePrefs.PORT, pin, this).also { it.start() }
        isRunning = true
        Log.i(TAG, "远程桌面已启动，端口 ${RemotePrefs.PORT}")
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = realMetrics()
        realW = metrics.widthPixels
        realH = metrics.heightPixels
        val dpi = metrics.densityDpi.coerceAtLeast(160)

        // 按最长边缩放，保持比例
        val scale = (MAX_DIM.toFloat() / maxOf(realW, realH)).coerceAtMost(1f)
        val capW = (realW * scale).toInt().coerceAtLeast(1)
        val capH = (realH * scale).toInt().coerceAtLeast(1)

        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * capW
                val bmpW = capW + (if (pixelStride > 0) rowPadding / pixelStride else 0)
                val bitmap = Bitmap.createBitmap(bmpW.coerceAtLeast(capW), capH, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (bmpW > capW) Bitmap.createBitmap(bitmap, 0, 0, capW, capH) else bitmap
                val bos = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                latestJpeg = bos.toByteArray()
                if (cropped !== bitmap) bitmap.recycle()
                cropped.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "帧编码失败: ${e.message}")
            } finally {
                image.close()
            }
        }, null)
        imageReader = reader

        virtualDisplay = projection?.createVirtualDisplay(
            "tvvpn-remote",
            capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null,
        )
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "远程桌面", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("远程桌面运行中")
            .setContentText("正在通过局域网共享屏幕，需 PIN 才能访问")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopEverything() {
        try { httpServer?.stop() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        httpServer = null; virtualDisplay = null; imageReader = null; projection = null
        latestJpeg = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopEverything(); super.onDestroy() }

    // —— RemoteHttpServer.Host ——
    override fun latestFrame(): ByteArray? = latestJpeg
    override fun screenSize(): Pair<Int, Int> = realW to realH

    companion object {
        /** UI 用来反映远程桌面是否在跑。 */
        @Volatile var isRunning: Boolean = false
            private set
        private const val TAG = "RemoteDesktopSvc"
        private const val CHANNEL_ID = "flint_remote_desktop"
        private const val NOTIF_ID = 42
        private const val MAX_DIM = 1280
        private const val JPEG_QUALITY = 55

        const val ACTION_STOP = "com.tvvpn.app.action.REMOTE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, RemoteDesktopService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, RemoteDesktopService::class.java).setAction(ACTION_STOP)
    }
}
