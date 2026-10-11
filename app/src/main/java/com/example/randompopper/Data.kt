package com.example.randompopper

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

data class PopItem(
    val uri: String,
    val name: String,
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
    val durationMs: Long
)

object AssetStore {

    private const val PREF = "random_popper"
    private const val KEY  = "assets"

    fun buildItem(ctx: Context, uri: Uri): PopItem? {
        val mime = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null } ?: return null
        val isVideo = mime.startsWith("video/")

        var w = 16
        var h = 9
        var duration = 0L

        if (isVideo) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.takeIf { it > 0 } ?: 16
                h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.takeIf { it > 0 } ?: 9
                duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        } else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                if (opts.outWidth  > 0) w = opts.outWidth
                if (opts.outHeight > 0) h = opts.outHeight
            } catch (_: Exception) {}
        }

        return PopItem(
            uri = uri.toString(),
            name = queryName(ctx, uri),
            isVideo = isVideo,
            width = w,
            height = h,
            durationMs = duration
        )
    }

    private fun queryName(ctx: Context, uri: Uri): String {
        var name: String? = null
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment ?: "media"
    }

    fun load(ctx: Context): List<PopItem> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val uri = o.optString("uri")
                if (uri.isNullOrEmpty()) return@mapNotNull null
                PopItem(
                    uri = uri,
                    name = o.optString("name", "media"),
                    isVideo = o.optBoolean("isVideo", false),
                    width = o.optInt("w", 16),
                    height = o.optInt("h", 9),
                    durationMs = o.optLong("dur", 0L)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun save(ctx: Context, list: List<PopItem>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("uri", a.uri)
                put("name", a.name)
                put("isVideo", a.isVideo)
                put("w", a.width)
                put("h", a.height)
                put("dur", a.durationMs)
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun addAll(ctx: Context, newItems: List<PopItem>) {
        val cur = load(ctx).toMutableList()
        cur.addAll(newItems)
        save(ctx, cur)
    }

    fun remove(ctx: Context, uri: String) {
        save(ctx, load(ctx).filterNot { it.uri == uri })
    }

    fun clear(ctx: Context) {
        save(ctx, emptyList())
    }
}
