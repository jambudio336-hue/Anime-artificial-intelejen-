package id.maskiplay.watch

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MeloloProvider : WatchProvider {
    override val id: String = "melolo"

    private val common = "app_id=645713&version_name=5.1.8&device_id=7268586056781432322"

    override fun search(q: String, offset: Int): List<Item> {
        val query = URLEncoder.encode(q.trim(), "UTF-8")
        val json = WatchHttp.get(
            "${WatchEndpoints.MELOLO_API}/i18n_novel/search/page/v1/?query=$query&offset=${offset.coerceAtLeast(0)}&count=20&$common",
            mapOf("Referer" to WatchEndpoints.MELOLO_REFERER)
        )
        if (json.optInt("code", -1) != 0) return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val rows = data.optJSONArray("search_data") ?: JSONArray()
        val output = LinkedHashMap<String, Item>()
        for (i in 0 until rows.length()) {
            val books = rows.optJSONObject(i)?.optJSONArray("books") ?: continue
            val book = books.optJSONObject(0) ?: continue
            val bookId = book.optString("book_id").takeIf { it.isNotBlank() } ?: continue
            output.putIfAbsent(
                bookId,
                Item(
                    id = bookId,
                    title = book.optString("book_name", "Tanpa judul"),
                    cover = book.optString("thumb_url").takeIf { it.startsWith("https://") },
                    synopsis = book.optString("abstract"),
                    totalEps = book.optInt("serial_count", 0),
                    status = book.optString("show_creation_status"),
                    lang = book.optString("language")
                )
            )
        }
        return output.values.toList()
    }

    fun searchPopular(): List<Item> {
        val terms = listOf("love", "ceo", "revenge", "marriage", "heir", "rebirth")
        val pool = Executors.newFixedThreadPool(terms.size)
        return try {
            val futures = terms.map { term -> pool.submit<List<Item>> { search(term, 0) } }
            val merged = LinkedHashMap<String, Item>()
            futures.forEach { future ->
                try { future.get(35, TimeUnit.SECONDS).forEach { merged.putIfAbsent(it.id, it) } }
                catch (_: Exception) { }
            }
            merged.values.toList()
        } finally { pool.shutdownNow() }
    }

    override fun episodes(seriesId: String): List<Episode> {
        val id = URLEncoder.encode(seriesId, "UTF-8")
        val json = WatchHttp.get(
            "${WatchEndpoints.MELOLO_API}/i18n_novel/book/directory/info/v1/?book_id=$id&$common",
            mapOf("Referer" to WatchEndpoints.MELOLO_REFERER)
        )
        if (json.optInt("code", -1) != 0) return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val rows = data.optJSONArray("item_list") ?: JSONArray()
        val paid = data.optJSONArray("paid_item_id_set")
        val lockedIds = buildSet {
            if (paid != null) for (i in 0 until paid.length()) add(paid.optString(i))
        }
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val videoId = row.optString("item_id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Episode(
                index = index,
                videoId = videoId,
                title = row.optString("title").ifBlank { "Episode ${index + 1}" },
                locked = lockedIds.contains(videoId)
            )
        }
    }

    override fun resolve(videoId: String): Playback {
        val payload = JSONObject().apply {
            put("video_id", videoId)
            put("biz_param", JSONObject().apply {
                put("video_platform", 1024)
                put("need_all_video_definition", true)
                put("use_os_player", false)
                put("video_id_type", 0)
                put("caller_scene", "series")
            })
        }
        val response = WatchHttp.postJson(
            "${WatchEndpoints.MELOLO_API}/video_model",
            payload,
            mapOf("Referer" to WatchEndpoints.MELOLO_REFERER)
        )
        if (response.optInt("code", -1) != 0) {
            throw IllegalStateException("Melolo menolak permintaan video (code ${response.optInt("code")})")
        }
        val data = response.optJSONObject("data") ?: throw IllegalStateException("Data video tidak tersedia")
        val rawModel = data.opt("video_model")
        val model = when (rawModel) {
            is JSONObject -> rawModel
            is String -> JSONObject(rawModel)
            else -> throw IllegalStateException("Format video_model tidak dikenal")
        }
        val videoList = model.optJSONObject("video_list")
            ?: model.optJSONObject("video_info")?.optJSONObject("video_list")
            ?: throw IllegalStateException("Daftar kualitas video kosong")
        val variants = mutableListOf<Variant>()
        for (key in listOf("video_2", "video_3", "video_4", "video_5")) {
            val entry = videoList.optJSONObject(key) ?: continue
            val url = decodeUrl(entry.optString("main_url"))
                ?: decodeUrl(entry.optString("backup_url_1"))
                ?: continue
            if (!url.startsWith("https://") || !url.contains(".melolostatic.com")) continue
            val definition = entry.optString("definition").ifBlank {
                when (key) { "video_2" -> "360p"; "video_3" -> "480p"; "video_4" -> "540p"; else -> "720p" }
            }
            variants += Variant(
                label = definition,
                url = url,
                codec = entry.optString("codec_type").takeIf { it.isNotBlank() },
                isHls = url.substringBefore('?').endsWith(".m3u8", true),
                referer = WatchEndpoints.MELOLO_REFERER
            )
        }
        val duration = when (val d = model.opt("video_duration")) {
            is Number -> d.toLong()
            is JSONObject -> d.optLong("duration", d.optLong("video_duration", 0L))
            else -> 0L
        }
        if (variants.isEmpty()) throw IllegalStateException("Tidak ada stream Melolo yang dapat diputar")
        return Playback(
            variants = variants.distinctBy { it.url }.sortedByDescending { qualityScore(it.label) },
            duration = duration,
            poster = model.optString("poster").takeIf { it.startsWith("https://") }
        )
    }

    private fun decodeUrl(encoded: String): String? {
        if (encoded.isBlank()) return null
        if (encoded.startsWith("https://")) return encoded
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun qualityScore(label: String): Int =
        Regex("(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
