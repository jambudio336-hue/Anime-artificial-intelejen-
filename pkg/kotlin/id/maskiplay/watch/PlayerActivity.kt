package id.maskiplay.watch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import ai.maskiplay.app.R
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
import kotlin.math.abs

@UnstableApi
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var variants: List<JSONObject>
    private var selectedUrl: String = ""
    private var currentTitle: String = "Video"
    private var videoId: String = ""
    private var source: String = "melolo"
    private var seriesId: String = ""
    private var previousId: String? = null
    private var previousTitle: String? = null
    private var nextId: String? = null
    private var nextTitle: String? = null
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
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty().ifBlank { "melolo" }
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        previousId = intent.getStringExtra(EXTRA_PREVIOUS_ID)
        previousTitle = intent.getStringExtra(EXTRA_PREVIOUS_TITLE)
        nextId = intent.getStringExtra(EXTRA_NEXT_ID)
        nextTitle = intent.getStringExtra(EXTRA_NEXT_TITLE)
        selectedUrl = intent.getStringExtra(EXTRA_SELECTED_URL).orEmpty()
        val raw = intent.getStringExtra(EXTRA_VARIANTS).orEmpty()
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        findViewById<TextView>(R.id.player_quality).setOnClickListener { showQualityPicker() }
        findViewById<TextView>(R.id.player_previous).setOnClickListener {
            previousId?.let { loadNeighbor(it, previousTitle ?: "Episode sebelumnya") }
        }
        findViewById<TextView>(R.id.player_next).setOnClickListener {
            nextId?.let { loadNeighbor(it, nextTitle ?: "Episode berikutnya") }
        }
        updateEpisodeControls()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        variants = try {
            val array = JSONObject(raw).optJSONArray("variants") ?: org.json.JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) { emptyList() }
        if (selectedUrl.isBlank() && variants.isNotEmpty()) selectedUrl = variants.first().optString("url")
        if (selectedUrl.isBlank()) {
            finish()
            return
        }
        initializePlayer(selectedUrl)
        installGestures()
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

    private fun loadNeighbor(id: String, title: String) {
        val provider: WatchProvider = when (source) {
            "melolo" -> MeloloProvider()
            "anime" -> AnimeProvider(this)
            else -> return
        }
        findViewById<TextView>(R.id.player_previous).isEnabled = false
        findViewById<TextView>(R.id.player_next).isEnabled = false
        Thread {
            try {
                val playback = provider.resolve(id)
                val allEpisodes = provider.episodes(seriesId)
                val index = allEpisodes.indexOfFirst { it.videoId == id }
                val prev = allEpisodes.getOrNull(index - 1)
                val next = allEpisodes.getOrNull(index + 1)
                runOnUiThread {
                    savePosition()
                    videoId = id
                    currentTitle = title
                    previousId = prev?.videoId
                    previousTitle = prev?.title
                    nextId = next?.videoId
                    nextTitle = next?.title
                    variants = playback.variants.map { v ->
                        JSONObject().put("label", v.label).put("url", v.url)
                            .put("codec", v.codec).put("isHls", v.isHls).put("referer", v.referer)
                    }
                    val payload = JSONObject().apply {
                        put("duration", playback.duration)
                        put("poster", playback.poster)
                        put("variants", org.json.JSONArray(variants))
                        put("subtitles", org.json.JSONArray().also { rows ->
                            playback.subtitles.forEach { sub -> rows.put(
                                JSONObject().put("label", sub.label).put("url", sub.url)
                                    .put("language", sub.language)
                            ) }
                        })
                    }
                    intent.putExtra(EXTRA_VARIANTS, payload.toString())
                    intent.putExtra(EXTRA_VIDEO_ID, videoId)
                    intent.putExtra(EXTRA_TITLE, currentTitle)
                    selectedUrl = playback.variants.firstOrNull {
                        !it.codec.orEmpty().contains("bytevc1", true)
                    }?.url ?: playback.variants.first().url
                    updateEpisodeControls()
                    initializePlayer(selectedUrl)
                    dbWorker.execute {
                        try {
                            WatchDb.get(applicationContext).watchDao().putHistory(
                                HistoryEntity(videoId, seriesId, source, currentTitle,
                                    playback.poster, System.currentTimeMillis())
                            )
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateEpisodeControls()
                    AlertDialog.Builder(this).setMessage(e.message ?: "Gagal memuat episode")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun updateEpisodeControls() {
        findViewById<TextView>(R.id.player_previous)?.apply {
            isEnabled = !previousId.isNullOrBlank()
            alpha = if (isEnabled) 1f else 0.35f
        }
        findViewById<TextView>(R.id.player_next)?.apply {
            isEnabled = !nextId.isNullOrBlank()
            alpha = if (isEnabled) 1f else 0.35f
        }
    }

    private fun installGestures() {
        val surface = playerView.videoSurfaceView ?: return
        var startX = 0f
        var startY = 0f
        var startBrightness = 0.5f
        var startVolume = 0
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        surface.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    startBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (abs(dy) > abs(dx) && abs(dy) > 18f) {
                        if (startX < view.width / 2f) {
                            val params = window.attributes
                            params.screenBrightness = (startBrightness - dy / view.height).coerceIn(0.05f, 1f)
                            window.attributes = params
                        } else {
                            val target = (startVolume - dy / view.height * maxVolume).toInt()
                                .coerceIn(0, maxVolume)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        }
                        true
                    } else false
                }
                else -> false
            }
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
        const val EXTRA_SOURCE = "watch.source"
        const val EXTRA_SERIES_ID = "watch.series_id"
        const val EXTRA_PREVIOUS_ID = "watch.previous_id"
        const val EXTRA_PREVIOUS_TITLE = "watch.previous_title"
        const val EXTRA_NEXT_ID = "watch.next_id"
        const val EXTRA_NEXT_TITLE = "watch.next_title"
        private const val PREFS = "watch_player"
        private const val KEY_LANDSCAPE = "landscape"
    }
}
