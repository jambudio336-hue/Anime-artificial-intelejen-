package id.maskiplay.watch

import android.content.Intent
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.Executors

@CapacitorPlugin(name = "Watch")
class WatchPlugin : Plugin() {
    private val worker = Executors.newFixedThreadPool(3)
    private lateinit var melolo: MeloloProvider
    private lateinit var anime: AnimeProvider
    private lateinit var manual: ManualProvider
    private lateinit var registry: ProviderRegistry

    override fun load() {
        super.load()
        melolo = MeloloProvider()
        anime = AnimeProvider(context)
        manual = ManualProvider()
        registry = ProviderRegistry(listOf(melolo, anime, manual))
        SyncWorker.schedule(context)
    }

    @PluginMethod
    fun search(call: PluginCall) = runWorker(call) {
        val source = call.getString("source", "melolo") ?: "melolo"
        val q = call.getString("q", "") ?: ""
        val offset = call.getInt("offset", 0) ?: 0
        if (source == "manual" && q.startsWith("http", true)) manual.register(q)
        val items = if (source == "melolo" && q.isBlank() && offset == 0)
            melolo.searchPopular() else registry.get(source).search(q, offset)
        JSObject().apply { put("items", items.toJsonArray()) }
    }

    @PluginMethod
    fun episodes(call: PluginCall) = runWorker(call) {
        val source = call.getString("source", "melolo") ?: "melolo"
        val seriesId = call.getString("seriesId") ?: throw IllegalArgumentException("seriesId wajib diisi")
        JSObject().apply { put("episodes", registry.get(source).episodes(seriesId).toJsonArray()) }
    }

    @PluginMethod
    fun resolve(call: PluginCall) = runWorker(call) {
        val source = call.getString("source", "melolo") ?: "melolo"
        val videoId = call.getString("videoId") ?: throw IllegalArgumentException("videoId wajib diisi")
        playbackJson(registry.get(source).resolve(videoId))
    }

    @PluginMethod
    fun play(call: PluginCall) = runWorker(call) {
        val source = call.getString("source", "melolo") ?: "melolo"
        val videoId = call.getString("videoId") ?: throw IllegalArgumentException("videoId wajib diisi")
        val title = call.getString("title", "Video") ?: "Video"
        val seriesId = call.getString("seriesId", videoId) ?: videoId
        val playback = registry.get(source).resolve(videoId)
        val preferred = playback.variants.firstOrNull { !it.codec.orEmpty().contains("bytevc1", true) }
            ?: playback.variants.first()
        saveHistory(source, seriesId, videoId, title, playback.poster)
        val intent = Intent(activity, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_VARIANTS, playbackJson(playback).toString())
            putExtra(PlayerActivity.EXTRA_SELECTED_URL, preferred.url)
            putExtra(PlayerActivity.EXTRA_DURATION, playback.duration)
            putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId)
        }
        activity.runOnUiThread { activity.startActivity(intent) }
        JSObject().apply { put("started", true) }
    }

    @PluginMethod
    fun setAnimeBase(call: PluginCall) = runWorker(call) {
        val url = call.getString("url") ?: throw IllegalArgumentException("URL sumber anime wajib diisi")
        anime.setAnimeBase(url)
        JSObject().apply { put("saved", true) }
    }

    @PluginMethod
    fun history(call: PluginCall) = runWorker(call) {
        val dao = WatchDb.get(context).watchDao()
        if (call.getString("action") == "remove") {
            val id = call.getString("id") ?: throw IllegalArgumentException("id wajib diisi")
            dao.removeHistory(id)
        }
        val rows = org.json.JSONArray()
        dao.getHistory().forEach { row -> rows.put(JSObject().apply {
            put("id", row.videoId); put("videoId", row.videoId); put("seriesId", row.seriesId)
            put("source", row.source); put("title", row.title); put("cover", row.cover)
            put("updatedAt", row.updatedAt)
        }) }
        JSObject().apply { put("items", rows) }
    }

    @PluginMethod
    fun watchlist(call: PluginCall) = runWorker(call) {
        val dao = WatchDb.get(context).watchDao()
        val action = call.getString("action", "list") ?: "list"
        if (action == "add" || action == "remove") {
            val id = call.getString("id") ?: throw IllegalArgumentException("id wajib diisi")
            if (action == "remove") dao.removeWatchlist(id)
            else dao.putWatchlist(WatchlistEntity(
                id = id,
                source = call.getString("source", "melolo") ?: "melolo",
                title = call.getString("title", id) ?: id,
                cover = call.getString("cover"),
                addedAt = System.currentTimeMillis()
            ))
        }
        val rows = org.json.JSONArray()
        dao.getWatchlist().forEach { row -> rows.put(JSObject().apply {
            put("id", row.id); put("source", row.source); put("title", row.title)
            put("cover", row.cover); put("addedAt", row.addedAt)
        }) }
        JSObject().apply { put("items", rows) }
    }

    private fun saveHistory(source: String, seriesId: String, videoId: String, title: String, poster: String?) {
        WatchDb.get(context).watchDao().putHistory(
            HistoryEntity(
                videoId = videoId,
                seriesId = seriesId,
                source = source,
                title = title,
                cover = poster,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun runWorker(call: PluginCall, action: () -> JSObject) {
        worker.execute {
            try { call.resolve(action()) }
            catch (e: Exception) { call.reject(e.message ?: "Kesalahan sumber tontonan", e) }
        }
    }

    private fun List<Item>.toJsonArray(): org.json.JSONArray =
        org.json.JSONArray().also { array ->
            forEach { item -> array.put(JSObject().apply {
                put("id", item.id); put("title", item.title); put("cover", item.cover)
                put("synopsis", item.synopsis); put("totalEps", item.totalEps)
                put("status", item.status); put("lang", item.lang)
            }) }
        }

    private fun List<Episode>.toJsonArray(): org.json.JSONArray =
        org.json.JSONArray().also { array ->
            forEach { ep -> array.put(JSObject().apply {
                put("index", ep.index); put("videoId", ep.videoId)
                put("title", ep.title); put("locked", ep.locked)
            }) }
        }

    private fun playbackJson(playback: Playback): JSObject = JSObject().apply {
        put("duration", playback.duration)
        put("poster", playback.poster)
        put("subtitles", org.json.JSONArray().also { array ->
            playback.subtitles.forEach { subtitle -> array.put(JSObject().apply {
                put("label", subtitle.label); put("url", subtitle.url); put("language", subtitle.language)
            }) }
        })
        put("variants", org.json.JSONArray().also { array ->
            playback.variants.forEach { variant -> array.put(JSObject().apply {
                put("label", variant.label); put("url", variant.url); put("codec", variant.codec)
                put("isHls", variant.isHls); put("referer", variant.referer)
            }) }
        })
    }

    override fun handleOnDestroy() {
        worker.shutdownNow()
        super.handleOnDestroy()
    }

    companion object {}
}
