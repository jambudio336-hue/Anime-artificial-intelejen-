package id.maskiplay.watch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** All provider hosts live here; do not scatter source domains through UI code. */
object WatchEndpoints {
    const val MELOLO_API = "https://api.tmtreader.com"
    const val MELOLO_REFERER = "https://melolo.org/"
    const val MELOLO_STATIC_SUFFIX = ".melolostatic.com"
    const val ANIME_REFERER = "https://hianime.to/"
    val ANIME_BASES = listOf(
        "https://aniwatch-api-tau.vercel.app/api/v2/hianime",
        "https://aniwatch-api-rouge.vercel.app/api/v2/hianime",
        "https://api-anime-rouge.vercel.app/aniwatch"
    )
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
}

object WatchHttp {
    private val lastRequestAt = AtomicLong(0L)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    @Synchronized
    private fun throttle() {
        val now = System.currentTimeMillis()
        val wait = 300L - (now - lastRequestAt.get())
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt.set(System.currentTimeMillis())
    }

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                throttle()
                val builder = Request.Builder().url(url).get()
                    .header("User-Agent", WatchEndpoints.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                headers.forEach { (key, value) -> builder.header(key, value) }
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) throw IOException("Respons kosong")
                    return JSONObject(body)
                }
            } catch (e: Exception) {
                failure = e
                if (attempt < 2) Thread.sleep(250L * (attempt + 1))
            }
        }
        throw IOException("Request gagal setelah 3 percobaan", failure)
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): Pair<ByteArray, String> {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                throttle()
                val builder = Request.Builder().url(url).get()
                    .header("User-Agent", WatchEndpoints.USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .header("Accept-Encoding", "gzip")
                headers.forEach { (key, value) -> builder.header(key, value) }
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP gambar ${response.code}")
                    val body = response.body ?: throw IOException("Gambar kosong")
                    if (body.contentLength() > MAX_IMAGE_BYTES) throw IOException("Poster terlalu besar")
                    val bytes = body.bytes()
                    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) throw IOException("Ukuran poster tidak valid")
                    val mime = body.contentType()?.toString()?.substringBefore(';') ?: "image/jpeg"
                    if (!mime.startsWith("image/")) throw IOException("Konten poster bukan gambar")
                    return Pair(bytes, mime)
                }
            } catch (e: Exception) {
                failure = e
                if (attempt < 2) Thread.sleep(250L * (attempt + 1))
            }
        }
        throw IOException("Gagal mengambil poster", failure)
    }

    @Throws(IOException::class)
    fun postJson(
        url: String,
        payload: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                throttle()
                val body = payload.toString().toRequestBody(jsonType)
                val builder = Request.Builder().url(url).post(body)
                    .header("User-Agent", WatchEndpoints.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                headers.forEach { (key, value) -> builder.header(key, value) }
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank()) throw IOException("Respons kosong")
                    return JSONObject(text)
                }
            } catch (e: Exception) {
                failure = e
                if (attempt < 2) Thread.sleep(250L * (attempt + 1))
            }
        }
        throw IOException("Request gagal setelah 3 percobaan", failure)
    }
}
