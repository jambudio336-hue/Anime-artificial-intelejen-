package id.maskiplay.watch

/**
 * Stable provider contract for watch sources. Implementations must keep network
 * access in native Kotlin and return only validated playback URLs.
 */
interface WatchProvider {
    val id: String
    fun search(q: String, offset: Int): List<Item>
    fun episodes(seriesId: String): List<Episode>
    fun resolve(videoId: String): Playback
}

data class Item(
    val id: String,
    val title: String,
    val cover: String? = null,
    val synopsis: String? = null,
    val totalEps: Int = 0,
    val status: String? = null,
    val lang: String? = null
)

data class Episode(
    val index: Int,
    val videoId: String,
    val title: String,
    val locked: Boolean = false
)

data class Subtitle(
    val label: String,
    val url: String,
    val language: String? = null
)

data class Playback(
    val variants: List<Variant>,
    val duration: Long = 0L,
    val poster: String? = null,
    val subtitles: List<Subtitle> = emptyList()
)

data class Variant(
    val label: String,
    val url: String,
    val codec: String? = null,
    val isHls: Boolean = false,
    val referer: String? = null
)

/** Source selection is centralized so the UI never depends on provider classes. */
class ProviderRegistry(providers: Collection<WatchProvider>) {
    private val byId = providers.associateBy { it.id }

    fun get(source: String): WatchProvider =
        byId[source.lowercase()] ?: throw IllegalArgumentException("Sumber tidak dikenal: $source")

    fun sources(): Set<String> = byId.keys
}
