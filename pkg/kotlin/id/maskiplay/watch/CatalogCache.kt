package id.maskiplay.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small offline fallback for the automatically refreshed public catalogue. */
object CatalogCache {
    private const val PREFS = "maskiplay_catalog_cache"
    private const val POPULAR_KEY = "melolo_popular"
    private const val UPDATED_KEY = "melolo_updated_at"

    fun savePopular(context: Context, items: List<Item>) {
        if (items.isEmpty()) return
        val rows = JSONArray()
        items.forEach { item ->
            rows.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("cover", item.cover)
                put("synopsis", item.synopsis)
                put("totalEps", item.totalEps)
                put("status", item.status)
                put("lang", item.lang)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(POPULAR_KEY, rows.toString())
            .putLong(UPDATED_KEY, System.currentTimeMillis())
            .apply()
    }

    fun loadPopular(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(POPULAR_KEY, null) ?: return emptyList()
        return try {
            val rows = JSONArray(raw)
            (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Item(
                    id = id,
                    title = row.optString("title", "Tanpa judul"),
                    cover = row.optString("cover").takeIf { it.startsWith("https://") },
                    synopsis = row.optString("synopsis"),
                    totalEps = row.optInt("totalEps"),
                    status = row.optString("status"),
                    lang = row.optString("lang")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun updatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(UPDATED_KEY, 0L)
}
