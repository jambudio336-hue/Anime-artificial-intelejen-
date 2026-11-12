package id.maskiplay.watch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.concurrent.Executors

@UnstableApi
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var variants: List<JSONObject>
    private var selectedUrl: String = ""
    private var currentTitle: String = "Video"
    private var videoId: String = ""
    private val dbWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val saveProgress = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = if (getSharedPreferences(PREFS, 0).getBoolean(KEY_LANDSCAPE, false))
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        selectedUrl = intent.getStringExtra(EXTRA_SELECTED_URL).orEmpty()
        val raw = intent.getStringExtra(EXTRA_VARIANTS).orEmpty()
        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        playerView = PlayerView(this).apply {
            useController = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        root.addView(playerView)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(12, 24, 12, 8)
            setBackgroundColor(0x66000000)
        }
        val quality = TextView(this).apply {
            text = "KUALITAS"
            setTextColor(-1)
            textSize = 12f
            setPadding(14, 10, 14, 10)
            setOnClickListener { showQualityPicker() }
        }
        top.addView(quality)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        variants = try {
            val array = JSONObject(raw).optJSONArray("variants") ?: org.json.JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) { emptyList() }
        if (selectedUrl.isBlank() && variants.isNotEmpty()) selectedUrl = variants.last().optString("url")
        if (selectedUrl.isBlank()) {
            finish()
            return
        }
        initializePlayer(selectedUrl)
        handler.postDelayed(saveProgress, 5_000L)
    }

    private fun initializePlayer(url: String) {
        player?.release()
        val variant = variants.firstOrNull { it.optString("url") == url }
        val headers = mutableMapOf<String, String>()
        variant?.optString("referer")?.takeIf { it.startsWith("https://") }?.let {
            headers["Referer"] = it
        }
        headers["User-Agent"] = WatchEndpoints.USER_AGENT
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val subtitleRows = try {
            JSONObject(intent.getStringExtra(EXTRA_VARIANTS).orEmpty()).optJSONArray("subtitles")
        } catch (_: Exception) { null }
        val subtitleConfigs = (0 until (subtitleRows?.length() ?: 0)).mapNotNull { index ->
            val row = subtitleRows?.optJSONObject(index) ?: return@mapNotNull null
            val subtitleUrl = row.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(row.optString("language").ifBlank { "id" })
                .setLabel(row.optString("label").ifBlank { "Subtitle" })
                .setSelectionFlags(0)
                .build()
        }
        val item = MediaItem.Builder().setUri(Uri.parse(url))
            .setSubtitleConfigurations(subtitleConfigs).build()
        val mediaSource = if (variant?.optBoolean("isHls") == true ||
            url.substringBefore('?').endsWith(".m3u8", true)) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item)
        }
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val message = error.message.orEmpty()
                if (message.contains("decoder", true) || message.contains("codec", true)) {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Video tidak didukung")
                        .setMessage("Codec video ini tidak dapat didekode perangkat. Coba player eksternal?")
                        .setNegativeButton("Tutup", null)
                        .setPositiveButton("Player eksternal") { _, _ -> openExternal(url) }
                        .show()
                }
            }
        })
        exo.setMediaSource(mediaSource)
        val key = progressKey(url)
        val resume = getSharedPreferences(PREFS, 0).getLong(key, 0L)
        exo.prepare()
        if (resume > 5_000L) {
            AlertDialog.Builder(this)
                .setTitle("Lanjutkan tontonan?")
                .setMessage("Lanjutkan dari ${formatTime(resume)}")
                .setNegativeButton("Dari awal") { _, _ -> exo.seekTo(0L); exo.playWhenReady = true }
                .setPositiveButton("Lanjutkan") { _, _ -> exo.seekTo(resume); exo.playWhenReady = true }
                .show()
        } else {
            exo.playWhenReady = true
        }
    }

    private fun showQualityPicker() {
        if (variants.isEmpty()) return
        val labels = variants.map { it.optString("label", "Otomatis") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Pilih kualitas")
            .setItems(labels) { _, which ->
                val url = variants[which].optString("url")
                if (url.isNotBlank()) {
                    savePosition()
                    selectedUrl = url
                    initializePlayer(url)
                }
            }.show()
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url),
                if (url.substringBefore('?').endsWith(".m3u8", true)) "application/vnd.apple.mpegurl" else "video/mp4"))
        } catch (_: Exception) {
            AlertDialog.Builder(this).setMessage("Tidak ada player eksternal yang tersedia").setPositiveButton("OK", null).show()
        }
    }

    private fun savePosition() {
        val exo = player ?: return
        if (selectedUrl.isBlank()) return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val duration = exo.duration.takeIf { it > 0L } ?: 0L
        getSharedPreferences(PREFS, 0).edit()
            .putLong(progressKey(selectedUrl), position).apply()
        if (videoId.isNotBlank()) dbWorker.execute {
            try {
                WatchDb.get(applicationContext).watchDao().putProgress(
                    ProgressEntity(
                        key = videoId,
                        videoId = videoId,
                        positionMs = position,
                        durationMs = duration,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) { }
        }
    }

    private fun progressKey(url: String) = "progress:" + url.hashCode()
    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    override fun onPause() {
        savePosition()
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveProgress)
        savePosition()
        playerView.player = null
        player?.release()
        player = null
        dbWorker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "watch.title"
        const val EXTRA_VARIANTS = "watch.variants"
        const val EXTRA_SELECTED_URL = "watch.selected_url"
        const val EXTRA_DURATION = "watch.duration"
        const val EXTRA_VIDEO_ID = "watch.video_id"
        private const val PREFS = "watch_player"
        private const val KEY_LANDSCAPE = "landscape"
    }
}
