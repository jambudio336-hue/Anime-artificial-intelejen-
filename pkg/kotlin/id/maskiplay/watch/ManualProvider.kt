package id.maskiplay.watch

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Manual URLs are intentionally session-local; no arbitrary script execution is supported. */
class ManualProvider : WatchProvider {
    override val id: String = "manual"
    private val entries = ConcurrentHashMap<String, Playback>()

    fun register(url: String): String {
        val normalized = url.trim()
        val uri = try { URI(normalized) } catch (_: Exception) {
            throw IllegalArgumentException("URL tidak valid")
        }
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "URL harus menggunakan HTTP atau HTTPS"
        }
        require(uri.host != null) { "Host URL tidak valid" }
        val path = uri.path.orEmpty().lowercase()
        require(path.endsWith(".m3u8") || path.endsWith(".mp4") ||
            path.contains(".m3u8/") || path.contains(".mp4/")) {
            "Tempel URL berakhiran .m3u8 atau .mp4"
        }
        val id = "manual:${normalized.hashCode()}"
        entries[id] = Playback(
            variants = listOf(
                Variant(
                    label = "Sumber tempelan",
                    url = normalized,
                    isHls = path.contains(".m3u8"),
                    referer = null
                )
            )
        )
        return id
    }

    override fun search(q: String, offset: Int): List<Item> =
        entries.entries.map { (key, _) ->
            Item(id = key, title = "Video manual", synopsis = key)
        }.filter { q.isBlank() || it.title.contains(q, true) || it.synopsis.orEmpty().contains(q, true) }

    override fun episodes(seriesId: String): List<Episode> =
        if (entries.containsKey(seriesId)) listOf(Episode(0, seriesId, "Video")) else emptyList()

    override fun resolve(videoId: String): Playback =
        entries[videoId] ?: throw IllegalArgumentException("URL manual tidak ditemukan; tempel ulang URL")
}
