import com.bumptech.glide.Glide
package com.example.randompopper

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.VideoView
import androidx.core.app.NotificationCompat
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

    private val loop = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try { spawnOne() } catch (e: Exception) { Log.e("Popper", "spawn", e) }
            val gap = Random.nextLong(300L, 5000L)
            handler.postDelayed(this, gap)
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
        handler.removeCallbacks(loop)
        removeAllViews()
        super.onDestroy()
    }

    private fun startPopping() {
        if (isRunning) return
        isRunning = true
        startForegroundCompat()
        handler.post(loop)
    }

    private fun stopPopping() {
        isRunning = false
        handler.removeCallbacks(loop)
        removeAllViews()
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "弹图器",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
        }
    }

    private fun removeAllViews() {
        val copy = ArrayList(activeViews)
        activeViews.clear()
        copy.forEach {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
    }

    private fun spawnOne() {
        val assets = AssetStore.load(this)
        if (assets.isEmpty()) return
        if (activeViews.size >= 12) return

        val a = assets.random()

        val dm = resources.displayMetrics
        val vw = dm.widthPixels
        val vh = dm.heightPixels

        val areaRatio  = Random.nextDouble(1.0 / 8.0, 1.0 / 4.0)
        val targetArea = areaRatio * vw * vh
        val aspect = if (a.width > 0 && a.height > 0)
            a.width.toDouble() / a.height else 16.0 / 9.0

        var h = sqrt(targetArea / aspect)
        var w = h * aspect
        w = min(w, vw * 0.92)
        h = min(h, vh * 0.92)

        val view: View
        var videoView: VideoView? = null

        if (a.isVideo) {
            val vv = VideoView(this)
            videoView = vv
            vv.setVideoURI(Uri.parse(a.uri))
            vv.setOnPreparedListener { mp ->
                mp.isLooping = false
                mp.setVolume(0f, 0f)
                vv.start()
            }
            vv.setOnErrorListener { _, _, _ -> true }
            view = vv
            } else {
    val iv = ImageView(this)
    iv.scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(Uri.parse(a.uri))
        .into(iv)
    view = iv
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            w.toInt(), h.toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (Random.nextDouble() * (vw - w).coerceAtLeast(1.0)).toInt()
            y = (Random.nextDouble() * (vh - h).coerceAtLeast(1.0)).toInt()
        }

        view.alpha  = 0f
        view.scaleX = 0.4f
        view.scaleY = 0.4f

        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            Log.e("Popper", "addView failed", e)
            return
        }
        activeViews.add(view)

        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(350)
            .start()

        val life = if (a.isVideo) {
            val d = if (a.durationMs > 0) a.durationMs else 5000L
            d.coerceIn(1200L, 12000L)
        } else {
            Random.nextLong(1500L, 3500L)
        }

        handler.postDelayed({
            view.animate()
                .alpha(0f).scaleX(0.7f).scaleY(0.7f)
                .setDuration(500)
                .withEndAction {
                    activeViews.remove(view)
                    try {
                        videoView?.stopPlayback()
                        wm.removeView(view)
                    } catch (_: Exception) {}
                }
                .start()
        }, life)
    }
}
