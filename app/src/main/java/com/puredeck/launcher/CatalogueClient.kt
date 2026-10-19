package com.puredeck.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** [logoPath] arrives with the catalogue, so the hero needs no extra lookup per title. */
data class Show(
    val id: Long,
    val mediaType: String,
    val title: String,
    val backdropUrl: String,
    val logoPath: String? = null,
    /** "added" or "trending": decides the line above the title. Null reads as "added". */
    val tag: String? = null
)

data class SearchHit(
    val id: Long,
    val mediaType: String,
    val title: String,
    val year: String?,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?
)

/** [kind]: included | free | ads | rent | buy. [url] is a link to this exact title, when known. */
data class Provider(val id: Int, val name: String, val logoPath: String?, val kind: String, val url: String?)

data class TitleDetail(
    val id: Long,
    val mediaType: String,
    val title: String,
    val year: String?,
    val overview: String,
    val rating: Double?,
    val genres: List<String>,
    val meta: String?,
    val backdropPath: String?,
    val logoPath: String?,
    val providers: List<Provider>
)

/** Keeps alpha, forces every colour channel to white: for logos too dark to read on artwork. */
val LogoWhitenFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

/** A title logo plus whether it is too dark to read on artwork and should be drawn white. */
class Logo(val bitmap: Bitmap, val needsLightening: Boolean)

/**
 * Talks to the Vercel catalogue service. The TV never contacts TMDB or JustWatch itself: some ISPs
 * (mine included) poison those hostnames, so every request goes through this one origin.
 */
class CatalogueClient(baseUrl: String, private val region: String, private val logoDir: File? = null) {

    private val base = baseUrl.trimEnd('/')
    val enabled get() = base.isNotBlank()

    private val bitmaps = object : LruCache<String, Bitmap>(BITMAP_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val results = JSONObject(Http.text("$base/api/search?q=${enc(query)}"))
            .optJSONArray("results") ?: return@withContext emptyList()
        results.objects().mapNotNull { item ->
            val title = item.text("title") ?: return@mapNotNull null
            SearchHit(
                id = item.optLong("id"),
                mediaType = item.text("mediaType") ?: return@mapNotNull null,
                title = title,
                year = item.text("year"),
                overview = item.text("overview").orEmpty(),
                posterPath = item.text("posterPath"),
                backdropPath = item.text("backdropPath")
            )
        }
    }

    suspend fun detail(mediaType: String, id: Long): TitleDetail = withContext(Dispatchers.IO) {
        val body = JSONObject(Http.text("$base/api/title?type=${enc(mediaType)}&id=$id&region=${enc(region)}"))
        TitleDetail(
            id = body.optLong("id"),
            mediaType = mediaType,
            title = body.text("title").orEmpty(),
            year = body.text("year"),
            overview = body.text("overview").orEmpty(),
            rating = body.optDouble("rating").takeUnless { it.isNaN() },
            genres = body.optJSONArray("genres")?.strings().orEmpty(),
            meta = body.text("meta"),
            backdropPath = body.text("backdropPath"),
            logoPath = body.text("logoPath"),
            providers = body.optJSONArray("providers")?.objects().orEmpty().mapNotNull { provider ->
                Provider(
                    id = provider.optInt("id"),
                    name = provider.text("name") ?: return@mapNotNull null,
                    logoPath = provider.text("logoPath"),
                    kind = provider.text("kind") ?: "included",
                    url = provider.text("url")
                )
            }
        )
    }

    suspend fun logoPath(mediaType: String, id: Long): String? = withContext(Dispatchers.IO) {
        JSONObject(Http.text("$base/api/logo?type=${enc(mediaType)}&id=$id")).text("logoPath")
    }

    /** Opaque artwork (backdrops, posters). Null on any failure: callers show a placeholder. */
    suspend fun image(path: String, size: String): Bitmap? = cached("$size$path") {
        Http.bitmap(artworkUrl(path, size), Bitmap.Config.RGB_565)
    }

    /**
     * Logos are kept on disk as well as in memory: a title's logo never changes, and without this
     * every restart (or a moment offline) sent the hero back to plain text until the network answered.
     */
    suspend fun logo(path: String): Logo? {
        val bitmap = cached("logo$path") {
            val file = logoDir?.let { File(it, path.removePrefix("/")) }
            file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                ?: Http.bytes(artworkUrl(path, LOGO_SIZE)).also { bytes ->
                    runCatching { file?.apply { parentFile?.mkdirs() }?.writeBytes(bytes) }
                }.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } ?: return null
        return Logo(bitmap, isDark(bitmap))
    }

    fun artworkUrl(path: String, size: String) = "$base/api/artwork?path=${enc(path)}&size=$size"

    private suspend fun cached(key: String, load: () -> Bitmap?): Bitmap? {
        bitmaps.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                load()
            } catch (_: IOException) {
                null
            }
        }?.also { bitmaps.put(key, it) }
    }

    /** Alpha-weighted brightness on a thumbnail; dark wordmarks vanish on dark artwork. */
    private fun isDark(bitmap: Bitmap): Boolean {
        val thumb = Bitmap.createScaledBitmap(bitmap, THUMB, THUMB, true)
        var weighted = 0.0
        var opacity = 0.0
        for (y in 0 until THUMB) for (x in 0 until THUMB) {
            val pixel = thumb.getPixel(x, y)
            val alpha = Color.alpha(pixel) / 255.0
            val luma = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)) / 255.0
            weighted += luma * alpha
            opacity += alpha
        }
        if (thumb !== bitmap) thumb.recycle()
        // A logo that is mostly solid (its own background) would turn into a white block if whitened;
        // only see-through wordmarks can safely be recoloured.
        val mostlyTransparent = opacity < THUMB * THUMB * MAX_OPAQUE_SHARE
        return mostlyTransparent && opacity > 0 && weighted / opacity < DARK_LUMA
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private companion object {
        const val BITMAP_CACHE_KB = 14 * 1024
        const val LOGO_SIZE = "w500"
        const val THUMB = 24
        const val DARK_LUMA = 0.3
        const val MAX_OPAQUE_SHARE = 0.6
    }
}
