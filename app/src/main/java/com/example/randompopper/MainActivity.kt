package com.example.randompopper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AssetAdapter
    private lateinit var countView: TextView
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var toggleBtn: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("popper_settings", Context.MODE_PRIVATE) }

    // 选择媒体（图片/视频）
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val newItems = ArrayList<PopItem>()
        for (uri in uris) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            AssetStore.buildItem(this, uri)?.let { newItems.add(it) }
        }
        AssetStore.addAll(this, newItems)
        refresh()
        toast("已添加 ${newItems.size} 个素材")
    }

    // 选择音频
    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val newItems = ArrayList<PopItem>()
        for (uri in uris) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            AssetStore.buildItem(this, uri)?.let { newItems.add(it) }
        }
        AssetStore.addAll(this, newItems)
        refresh()
        toast("已添加 ${newItems.size} 个音频")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
        refresh()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUI(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1117"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "随机弹图器"
            setTextColor(Color.parseColor("#8FB0FF"))
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "添加照片 / GIF / 视频 / 音频，设置爆发参数"
            setTextColor(Color.parseColor("#8B95A9"))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })

        // 按钮行：添加媒体、添加音频、设置
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) }

        val addMediaBtn = Button(this).apply {
            text = "＋ 照片/视频"
            setTextColor(Color.WHITE)
            textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A68E0"))
        }
        addMediaBtn.setOnClickListener {
            pickMedia.launch(arrayOf("image/*", "video/*"))
        }
        btnRow.addView(addMediaBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val addAudioBtn = Button(this).apply {
            text = "＋ 音频"
            setTextColor(Color.WHITE)
            textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
        }
        addAudioBtn.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }
        btnRow.addView(addAudioBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })

        val settingBtn = Button(this).apply {
            text = "⚙ 设置"
            setTextColor(Color.WHITE)
            textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#374151"))
        }
        settingBtn.setOnClickListener { showSettingsDialog() }
        btnRow.addView(settingBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })

        root.addView(btnRow)

        // 状态文字
        statusView = TextView(this).apply {
            text = "等待添加素材…"
            setTextColor(Color.parseColor("#8B95A9"))
            textSize = 13f
        }
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        // 开始/停止按钮
        toggleBtn = Button(this).apply {
            text = "▶ 开始弹出"
            setTextColor(Color.WHITE)
            textSize = 15f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        }
        toggleBtn.setOnClickListener {
            if (OverlayService.isRunning) stopPopping() else startPopping()
        }
        root.addView(toggleBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        // 素材库标题
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) }

        titleRow.addView(TextView(this).apply {
            text = "素材库"
            setTextColor(Color.parseColor("#CFD7E6"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        countView = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#5B7CFA"))
                cornerRadius = dp(20).toFloat()
            }
        }
        titleRow.addView(countView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        root.addView(titleRow)

        // 素材网格
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            isNestedScrollingEnabled = false
        }
        adapter = AssetAdapter { item ->
            AssetStore.remove(this, item.uri)
            refresh()
        }
        grid.adapter = adapter
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        emptyView = TextView(this).apply {
            text = "还没有素材，先添加一些吧～"
            setTextColor(Color.parseColor("#8B95A9"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(emptyView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        return scroll
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showSettingsDialog() {
        val interval = prefs.getInt("burst_interval", 30)
        val count = prefs.getInt("burst_count", 15)
        val duration = prefs.getInt("burst_duration", 10)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        layout.addView(TextView(this).apply { text = "爆发间隔（秒）："; textSize = 14f; setTextColor(Color.BLACK) })
        val etInterval = EditText(this).apply { setText(interval.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(etInterval)

        layout.addView(TextView(this).apply { text = "爆发数量（张）："; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, dp(10), 0, 0) })
        val etCount = EditText(this).apply { setText(count.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(etCount)

        layout.addView(TextView(this).apply { text = "爆发维持时间（秒）："; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, dp(10), 0, 0) })
        val etDuration = EditText(this).apply { setText(duration.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(etDuration)

        AlertDialog.Builder(this)
            .setTitle("爆发参数设置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putInt("burst_interval", etInterval.text.toString().toIntOrNull() ?: 30)
                    .putInt("burst_count", etCount.text.toString().toIntOrNull() ?: 15)
                    .putInt("burst_duration", etDuration.text.toString().toIntOrNull() ?: 10)
                    .apply()
                toast("设置已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startPopping() {
        if (AssetStore.load(this).isEmpty()) {
            toast("请先添加至少一个素材")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请授予「显示在其他应用上层」权限")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val i = Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_START }
        ContextCompat.startForegroundService(this, i)
        mainHandler.postDelayed({ refresh() }, 200)
    }

    private fun stopPopping() {
        val i = Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP }
        startService(i)
        mainHandler.postDelayed({ refresh() }, 200)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), 1001)
            }
        }
    }

    private fun refresh() {
        val list = AssetStore.load(this)
        adapter.submit(list)
        countView.text = list.size.toString()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        if (OverlayService.isRunning) {
            toggleBtn.text = "■ 停止弹出"
            statusView.text = "🔥 正在爆发中…（长按屏幕右上角可强制结束爆发）"
        } else {
            toggleBtn.text = "▶ 开始弹出"
            statusView.text = if (list.isEmpty()) "等待添加素材…" else "共 ${list.size} 个素材"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    inner class AssetAdapter(private val onDelete: (PopItem) -> Unit) : RecyclerView.Adapter<AssetAdapter.VH>() {
        private val items = mutableListOf<PopItem>()

        fun submit(list: List<PopItem>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val frame = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0B0E14")); setStroke(dp(1), Color.parseColor("#28313F")); cornerRadius = dp(12).toFloat()
                }
            }
            val thumb = ImageView(parent.context).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(thumb)
            val badge = TextView(parent.context).apply {
                setTextColor(Color.parseColor("#DBE3F5")); textSize = 10f; setBackgroundColor(Color.parseColor("#99000000")); setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.START or Gravity.TOP; setMargins(dp(6), dp(6), 0, 0) }
            }
            frame.addView(badge)
            val del = TextView(parent.context).apply {
                text = "×"; setTextColor(Color.parseColor("#FF9AA5")); textSize = 16f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(Color.parseColor("#99000000")); shape = GradientDrawable.OVAL }
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.END or Gravity.TOP; setMargins(0, dp(6), dp(6), 0) }
                isClickable = true
            }
            frame.addView(del)
            val name = TextView(parent.context).apply {
                setTextColor(Color.parseColor("#CDD6E8")); textSize = 10f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setBackgroundColor(Color.parseColor("#AA000000")); setPadding(dp(6), dp(3), dp(6), dp(3))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM }
            }
            frame.addView(name)
            return VH(frame, thumb, badge, name, del)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(v: View, private val thumb: ImageView, private val badge: TextView, private val name: TextView, private val del: View) : RecyclerView.ViewHolder(v) {
            fun bind(item: PopItem) {
                if (item.isAudio) {
                    thumb.setImageResource(android.R.drawable.ic_media_play)
                    thumb.scaleType = ImageView.ScaleType.CENTER
                } else if (item.isVideo) {
                    thumb.setImageResource(android.R.drawable.ic_media_play)
                    thumb.scaleType = ImageView.ScaleType.CENTER
                } else {
                    thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    try { thumb.setImageURI(Uri.parse(item.uri)) } catch (_: Exception) { thumb.setImageDrawable(null) }
                }
                badge.text = when {
                    item.isAudio -> "音频"
                    item.isVideo -> "视频"
                    else -> "图片"
                }
                name.text = item.name
                del.setOnClickListener { onDelete(item) }
            }
        }
    }
}
