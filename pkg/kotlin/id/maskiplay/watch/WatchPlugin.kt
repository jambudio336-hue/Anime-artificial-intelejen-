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
    }

    @PluginMethod
    fun search(call: PluginCall) = runWorker(call) {
        val source = call.getString("source", "melolo") ?: "melolo"
        val q = call.getString("q", "") ?: ""
        val offset = call.getInt("offset", 0) ?: 0
        if (source == "manual" && q.startsWith("http", true)) manual.register(q)
        val items = registry.get(source).search(q, offset)
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
        val playback = registry.get(source).resolve(videoId)
        val preferred = playback.variants.firstOrNull { !it.codec.orEmpty().contains("bytevc1", true) }
            ?: playback.variants.first()
        saveHistory(source, videoId, title, playback.poster)
        val intent = Intent(activity, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_VARIANTS, playbackJson(playback).toString())
            putExtra(PlayerActivity.EXTRA_SELECTED_URL, preferred.url)
            putExtra(PlayerActivity.EXTRA_DURATION, playback.duration)
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
    fun history(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val rows = try { org.json.JSONArray(prefs.getString(KEY_HISTORY, "[]")) }
            catch (_: Exception) { org.json.JSONArray() }
        call.resolve(JSObject().apply { put("items", rows) })
    }

    @PluginMethod
    fun watchlist(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val rows = try { org.json.JSONArray(prefs.getString(KEY_WATCHLIST, "[]")) }
            catch (_: Exception) { org.json.JSONArray() }
        val action = call.getString("action", "list") ?: "list"
        if (action == "add" || action == "remove") {
            val id = call.getString("id") ?: return call.reject("id wajib diisi")
            val kept = org.json.JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("id") != id) kept.put(row)
            }
            if (action == "add") {
                kept.put(JSObject().apply {
                    put("id", id)
                    put("source", call.getString("source", "melolo"))
                    put("title", call.getString("title", id))
                    put("cover", call.getString("cover"))
                })
            }
            prefs.edit().putString(KEY_WATCHLIST, kept.toString()).apply()
            call.resolve(JSObject().apply { put("items", kept) })
        } else {
            call.resolve(JSObject().apply { put("items", rows) })
        }
    }

    private fun saveHistory(source: String, videoId: String, title: String, poster: String?) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val old = try { org.json.JSONArray(prefs.getString(KEY_HISTORY, "[]")) }
            catch (_: Exception) { org.json.JSONArray() }
        val next = org.json.JSONArray()
        next.put(JSObject().apply {
            put("id", videoId); put("videoId", videoId); put("seriesId", videoId)
            put("source", source); put("title", title); put("cover", poster)
            put("updatedAt", System.currentTimeMillis())
        })
        for (i in 0 until old.length()) {
            val row = old.optJSONObject(i) ?: continue
            if (row.optString("videoId") != videoId || row.optString("source") != source) next.put(row)
            if (next.length() >= 100) break
        }
        prefs.edit().putString(KEY_HISTORY, next.toString()).apply()
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

    companion object {
        private const val PREFS = "watch_library"
        private const val KEY_HISTORY = "history"
        private const val KEY_WATCHLIST = "watchlist"
    }
}
