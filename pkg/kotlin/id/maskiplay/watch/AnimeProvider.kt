package id.maskiplay.watch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.net.URLEncoder

class AnimeProvider(context: Context) : WatchProvider {
    override val id: String = "anime"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("watch_anime_provider", Context.MODE_PRIVATE)

    @Volatile private var activeBase: String? = null

    fun setAnimeBase(url: String) {
        val normalized = url.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "Alamat sumber anime wajib HTTPS" }
        require(normalized.length <= 300) { "Alamat sumber terlalu panjang" }
        prefs.edit().putString(KEY_CUSTOM_BASE, normalized).apply()
        activeBase = normalized
    }

    override fun search(q: String, offset: Int): List<Item> {
        val query = URLEncoder.encode(q.trim(), "UTF-8")
        val json = request("/search?q=$query")
        val data = json.optJSONObject("data") ?: return emptyList()
        val rows = data.optJSONArray("animes") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val animeId = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Item(
                id = animeId,
                title = row.optString("name", "Tanpa judul"),
                cover = row.optString("poster").takeIf { it.startsWith("https://") },
                totalEps = row.optInt("episodes", 0),
                status = row.optString("type"),
                lang = "Sub Indo"
            )
        }
    }

    override fun episodes(seriesId: String): List<Episode> {
        val id = encodePathSegment(seriesId)
        val json = request("/anime/$id/episodes")
        val rows = json.optJSONObject("data")?.optJSONArray("episodes") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val episodeId = row.optString("episodeId").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Episode(
                index = row.optInt("number", index + 1) - 1,
                videoId = episodeId,
                title = row.optString("title").ifBlank { "Episode ${row.optInt("number", index + 1)}" }
            )
        }
    }

    override fun resolve(videoId: String): Playback {
        val encoded = URLEncoder.encode(videoId, "UTF-8")
        val json = request("/episode/sources?animeEpisodeId=$encoded&server=hd-1&category=sub")
        val data = json.optJSONObject("data") ?: throw IllegalStateException("Sumber anime tidak tersedia")
        val rows = data.optJSONArray("sources") ?: JSONArray()
        val variants = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val url = row.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            Variant(
                label = row.optString("quality").ifBlank { "Otomatis" },
                url = url,
                codec = null,
                isHls = row.optBoolean("isM3U8") || url.substringBefore('?').endsWith(".m3u8", true),
                referer = WatchEndpoints.ANIME_REFERER
            )
        }.distinctBy { it.url }
        if (variants.isEmpty()) throw IllegalStateException("Mirror anime tidak mengembalikan stream")
        val subtitleRows = data.optJSONArray("subtitles") ?: JSONArray()
        val subtitles = (0 until subtitleRows.length()).mapNotNull { index ->
            val row = subtitleRows.optJSONObject(index) ?: return@mapNotNull null
            val url = row.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val format = row.optString("format", row.optString("mimeType"))
            if (!url.substringBefore('?').endsWith(".vtt", true) &&
                !format.contains("vtt", true)) return@mapNotNull null
            Subtitle(
                label = row.optString("label").ifBlank { row.optString("lang", "Subtitle") },
                url = url,
                language = row.optString("lang").takeIf { it.isNotBlank() }
            )
        }
        return Playback(
            variants = variants.sortedByDescending { qualityScore(it.label) },
            duration = data.optLong("duration", 0L),
            poster = data.optString("poster").takeIf { it.startsWith("https://") },
            subtitles = subtitles
        )
    }

    private fun request(path: String): org.json.JSONObject {
        val now = System.currentTimeMillis()
        val cached = activeBase ?: prefs.getString(KEY_CUSTOM_BASE, null)
            ?: prefs.getString(KEY_GOOD_BASE, null)?.takeIf {
                now - prefs.getLong(KEY_GOOD_AT, 0L) < GOOD_TTL
            }
        val candidates = buildList {
            if (!cached.isNullOrBlank()) add(cached)
            addAll(WatchEndpoints.ANIME_BASES.filter { it != cached })
        }.distinct()
        var lastError: Exception? = null
        for (base in candidates) {
            if (prefs.getLong(BAD_PREFIX + base, 0L) > now) continue
            try {
                val response = WatchHttp.get(
                    base.trimEnd('/') + path,
                    mapOf("Referer" to WatchEndpoints.ANIME_REFERER)
                )
                if (!response.has("data")) throw IllegalStateException("Respons mirror bukan JSON API")
                activeBase = base
                prefs.edit().putString(KEY_GOOD_BASE, base).putLong(KEY_GOOD_AT, now).apply()
                return response
            } catch (e: Exception) {
                lastError = e
                prefs.edit().putLong(BAD_PREFIX + base, now + BAD_TTL).apply()
            }
        }
        throw IllegalStateException("Semua sumber anime gagal; atur instance sendiri di Setelan", lastError)
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun qualityScore(label: String): Int =
        Regex("(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        private const val KEY_CUSTOM_BASE = "custom_base"
        private const val KEY_GOOD_BASE = "good_base"
        private const val KEY_GOOD_AT = "good_at"
        private const val BAD_PREFIX = "bad_until:"
        private const val GOOD_TTL = 24L * 60 * 60 * 1000
        private const val BAD_TTL = 60L * 60 * 1000
    }
}
