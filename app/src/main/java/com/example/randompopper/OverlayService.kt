package com.example.randompopper

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.VideoView
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.randompopper.START"
        const val ACTION_STOP  = "com.example.randompopper.STOP"
        private const val CHANNEL_ID = "random_popper_channel"
        private const val NOTIF_ID   = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val activeViews = mutableListOf<View>()
    private val backgroundViews = mutableListOf<View>()
    private val prefs by lazy { getSharedPreferences("popper_settings", Context.MODE_PRIVATE) }

    // 当前爆发状态
    private var isBursting = false
    private var burstEndTime = 0L
    private var currentMediaPlayer: MediaPlayer? = null

    private val MAX_VIEWS = 20 // 屏幕最多20张照片

    // ✅ 常规随机弹出循环（每 0.3 ~ 5 秒弹出一张，爆发时暂停）
    private val normalLoop = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val gap = Random.nextLong(300L, 5000L)
            handler.postDelayed({
                if (isRunning && !isBursting) { // ✅ 改动1：爆发时跳过常规弹出
                    try { spawnOne() } catch (e: Exception) { Log.e("Popper", "normal spawn", e) }
                }
                handler.postDelayed(this, gap)
            }, gap)
        }
    }

    // 爆发模式循环（每 30 秒触发一次爆发）
    private val burstLoop = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val gap = prefs.getInt("burst_interval", 30).toLong() * 1000L
            handler.postDelayed({
                if (isRunning && !isBursting) {
                    startBurst()
                }
                handler.postDelayed(this, gap)
            }, gap)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startPopping()
            ACTION_STOP  -> stopPopping()
            else -> {
                if (isRunning) startForegroundCompat() else stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(normalLoop)
        handler.removeCallbacks(burstLoop)
        stopAudio()
        removeAllViews()
        super.onDestroy()
    }

    private fun startPopping() {
        if (isRunning) return
        isRunning = true
        startForegroundCompat()
        
        // 启动常规随机弹出
        handler.post(normalLoop)
        
        // 启动爆发循环，并立即触发一次爆发
        handler.postDelayed(burstLoop, prefs.getInt("burst_interval", 30).toLong() * 1000L)
        startBurst()
    }

    private fun stopPopping() {
        isRunning = false
        handler.removeCallbacks(normalLoop)
        handler.removeCallbacks(burstLoop)
        stopAudio()
        removeAllViews()
        stopForegroundCompat()
        stopSelf()
    }

    // ================== 爆发逻辑 ==================
    private fun startBurst() {
        if (!isRunning || isBursting) return
        isBursting = true
        val durationSec = prefs.getInt("burst_duration", 10)
        burstEndTime = System.currentTimeMillis() + durationSec * 1000L

        Log.d("Popper", "开始爆发弹窗！")
        val count = prefs.getInt("burst_count", 15)

        // 1. 添加右上角强制结束按钮
        addForceStopView()

        // 2. 添加背景图
        val allAssets = AssetStore.load(this)
        val mediaAssets = allAssets.filter { !it.isAudio }
        if (mediaAssets.isNotEmpty()) {
            val bgAsset = mediaAssets.random()
            addBackgroundView(bgAsset)
        }

        // 3. 播放音频
        val audioAssets = allAssets.filter { it.isAudio }
        if (audioAssets.isNotEmpty()) {
            playRandomAudio(audioAssets)
        }

        // 4. 弹出照片（爆发核心）
        for (i in 0 until count) {
            handler.postDelayed({
                if (isRunning && isBursting) {
                    try { spawnOne() } catch (e: Exception) { Log.e("Popper", "burst spawn", e) }
                }
            }, i * 30L) // 每张间隔 30 毫秒
        }

        // 5. 检查爆发是否结束
        handler.postDelayed({
            if (System.currentTimeMillis() >= burstEndTime) {
                endBurst()
            }
        }, durationSec * 1000L + 1000L)
    }

    private fun endBurst() {
        isBursting = false
        stopAudio()
        removeBackgroundView()
        removeForceStopView()
        Log.d("Popper", "爆发结束")
    }

    // 添加背景
    private fun addBackgroundView(a: PopItem) {
        val dm = resources.displayMetrics
        val vw = dm.widthPixels
        val vh = dm.heightPixels

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.alpha = 1f // ✅ 改动2：背景完全不透明 (100%)
        Glide.with(this).load(Uri.parse(a.uri)).into(iv)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            vw, vh, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        try { 
            wm.addView(iv, lp)
            backgroundViews.add(iv) 
        } catch (e: Exception) { 
            Log.e("Popper", "addBackground failed", e) 
        }
    }

    private fun removeBackgroundView() {
        val copy = ArrayList(backgroundViews)
        backgroundViews.clear()
        copy.forEach {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
    }

    // 右上角强制结束按钮
    private var forceStopView: View? = null

    private fun addForceStopView() {
        if (forceStopView != null) return
        val size = (resources.displayMetrics.density * 60).toInt()
        val iv = ImageView(this).apply {
            setBackgroundColor(0x33FF0000) // 半透明红色
            setOnLongClickListener {
                if (isBursting) {
                    endBurst()
                    removeAllViews() 
                }
                true
            }
        }
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0; y = 0
        }

        try { 
            wm.addView(iv, lp)
            forceStopView = iv
        } catch (_: Exception) {}
    }

    private fun removeForceStopView() {
        forceStopView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        forceStopView = null
    }

    // 播放音频
    private fun playRandomAudio(audioAssets: List<PopItem>) {
        if (audioAssets.isEmpty()) return
        try {
            val a = audioAssets.random()
            val mp = MediaPlayer()
            mp.setDataSource(this, Uri.parse(a.uri))
            mp.isLooping = false
            mp.setOnCompletionListener {
                if (isBursting) {
                    playRandomAudio(audioAssets)
                }
            }
            mp.prepare()
            mp.start()
            currentMediaPlayer = mp
        } catch (e: Exception) {
            Log.e("Popper", "playAudio failed", e)
        }
    }

    private fun stopAudio() {
        try {
            currentMediaPlayer?.stop()
            currentMediaPlayer?.release()
        } catch (_: Exception) {}
        currentMediaPlayer = null
    }

    // ================== 视图管理 ==================
    private fun removeAllViews() {
        val copy = ArrayList(activeViews)
        activeViews.clear()
        copy.forEach {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        removeBackgroundView()
        removeForceStopView()
    }

    private fun spawnOne() {
        if (!isRunning) return
        
        val allAssets = AssetStore.load(this)
        val mediaAssets = allAssets.filter { !it.isAudio }
        if (mediaAssets.isEmpty()) return

        if (activeViews.size >= MAX_VIEWS) {
            val oldest = activeViews.removeAt(0)
            try { wm.removeView(oldest) } catch (_: Exception) {}
        }

        val a = mediaAssets.random()
        val dm = resources.displayMetrics
        val vw = dm.widthPixels
        val vh = dm.heightPixels

        val areaRatio  = Random.nextDouble(1.0 / 8.0, 1.0 / 4.0)
        val targetArea = areaRatio * vw * vh
        val aspect = if (a.width > 0 && a.height > 0) a.width.toDouble() / a.height else 16.0 / 9.0

        var h = sqrt(targetArea / aspect)
        var w = h * aspect
        w = min(w, vw * 0.92); h = min(h, vh * 0.92)

        val view: View
        var videoView: VideoView? = null

        if (a.isVideo) {
            val vv = VideoView(this)
            videoView = vv
            vv.setVideoURI(Uri.parse(a.uri))
            vv.setOnPreparedListener { mp -> mp.isLooping = false; mp.setVolume(0f, 0f); vv.start() }
            vv.setOnErrorListener { _, _, _ -> true }
            view = vv
        } else {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(this).load(Uri.parse(a.uri)).into(iv)
            view = iv
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            w.toInt(), h.toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (Random.nextDouble() * (vw - w).coerceAtLeast(1.0)).toInt()
            y = (Random.nextDouble() * (vh - h).coerceAtLeast(1.0)).toInt()
        }

        view.alpha = 1f
        view.scaleX = 0.4f; view.scaleY = 0.4f

        try { wm.addView(view, lp) } catch (e: Exception) { Log.e("Popper", "addView failed", e); return }
        activeViews.add(view)

        view.animate().scaleX(1f).scaleY(1f).setDuration(350).start()

        // 点击事件
        view.setOnClickListener {
            activeViews.remove(view)
            try { videoView?.stopPlayback(); wm.removeView(view) } catch (_: Exception) {}
            if (Random.nextBoolean()) {
                repeat(3) { handler.postDelayed({ if (isRunning) spawnOne() }, 50L * it) }
            }
        }

        // 生命周期：至少 8 秒
        val life = if (a.isVideo) {
            val d = if (a.durationMs > 0) a.durationMs else 8000L
            d.coerceIn(8000L, 12000L)
        } else {
            Random.nextLong(8000L, 12000L)
        }

        handler.postDelayed({
            activeViews.remove(view)
            view.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(500).withEndAction {
                try { videoView?.stopPlayback(); wm.removeView(view) } catch (_: Exception) {}
            }.start()
        }, life)
    }

    // ================== 前台服务与通知 ==================
    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("随机弹图器运行中")
            .setContentText("照片/视频正在随机弹出")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "弹图器", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            }
        }
    }
}
