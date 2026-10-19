package com.puredeck.launcher

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** Fetches provider-specific TMDB artwork and keeps the last useful catalogue on-device. */
class TrendingSource(
    context: Context,
    private val apiKey: String,
    private val region: String,
    private val catalogueBaseUrl: String
) {

    /** Also serves search and title details; shares its image cache with the hero. */
    val client = CatalogueClient(catalogueBaseUrl, region, java.io.File(context.cacheDir, "logos"))

    private val prefs = context.getSharedPreferences("hero_catalogue_cache", Context.MODE_PRIVATE)
    private val lists = HashMap<String, List<Show>>()
    private val cacheNameByPackage = HashMap<String, String>()
    private val images = LruCache<String, Bitmap>(IMAGE_CACHE_SIZE)
    private val logoPaths = HashMap<String, String?>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Newest catalogue for the app, ordered so titles shown recently come last. */
    suspend fun forPackage(packageName: String, label: String): List<Show> {
        if (apiKey.isBlank() && catalogueBaseUrl.isBlank()) return emptyList()
        val provider = providerFor(packageName, label) ?: return emptyList()
        val cacheName = "catalogue_${region}_$provider"
        cacheNameByPackage[packageName] = cacheName
        synchronized(lists) { lists[cacheName]?.let { return diversified(cacheName, it) } }

        val cached = readCached(cacheName)
        val cachedAt = prefs.getLong(cacheTimeKey(cacheName), 0L)
        if (cached.isNotEmpty()) {
            synchronized(lists) { lists[cacheName] = cached }
            if (System.currentTimeMillis() - cachedAt >= CACHE_TTL_MS) {
                refreshScope.launch { refresh(cacheName, provider) }
            }
            return diversified(cacheName, cached)
        }

        val fetched = fetchProvider(provider)
        if (fetched.isNotEmpty()) {
            synchronized(lists) { lists[cacheName] = fetched }
            writeCached(cacheName, fetched)
        }
        return diversified(cacheName, fetched)
    }

    /** Remembers [show] was displayed so the next rotation, even after a restart, picks fresh titles. */
    fun markShown(packageName: String, show: Show) {
        val cacheName = cacheNameByPackage[packageName] ?: return
        val seen = seenKeys(cacheName).apply { remove(show.key()); add(show.key()) }
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        prefs.edit().putString(seenKey(cacheName), seen.joinToString(",")).apply()
    }

    suspend fun backdrop(show: Show): Bitmap? {
        images.get(show.backdropUrl)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                Http.bitmap(show.backdropUrl, Bitmap.Config.RGB_565)
            } catch (_: IOException) {
                null
            }
        }?.also { images.put(show.backdropUrl, it) }
    }

    /** The show's own title logo, or null (no logo, no proxy, or offline) so the caller keeps its text. */
    suspend fun logo(show: Show): Logo? {
        if (!client.enabled) return null
        val path = show.logoPath ?: if (show.id > 0L) cachedLogoPath(show.key()) { client.logoPath(show.mediaType, show.id) } else null
        return path?.let { client.logo(it) }
    }

    private suspend fun cachedLogoPath(key: String, lookup: suspend () -> String?): String? {
        // A missing logo is remembered for the session too, so it isn't re-asked on every rotation.
        synchronized(logoPaths) { if (logoPaths.containsKey(key)) return logoPaths[key] }
        // Persisted: a title's logo almost never changes.
        prefs.getString("logo_$key", null)?.let { saved ->
            synchronized(logoPaths) { logoPaths[key] = saved }
            return saved
        }
        val path = try {
            lookup()
        } catch (_: IOException) {
            return null // transient: try again next time
        }
        synchronized(logoPaths) { logoPaths[key] = path }
        if (path != null) prefs.edit().putString("logo_$key", path).apply()
        return path
    }

    private suspend fun refresh(cacheName: String, provider: String) {
        val fetched = fetchProvider(provider)
        if (fetched.isNotEmpty()) {
            synchronized(lists) { lists[cacheName] = fetched }
            writeCached(cacheName, fetched)
        }
    }

    private fun providerFor(packageName: String, label: String): String? {
        PROVIDERS[packageName]?.let { return it }
        val name = label.lowercase(Locale.ROOT)
        return when {
            "netflix" in name -> "8"
            "prime video" in name || "amazon video" in name -> "9|119"
            // Disney+ Hotstar and JioHotstar share one app package. TMDB moved the Indian catalogue
            // to JioHotstar (2336); provider 122 alone returns a single stray title.
            "hotstar" in name -> HOTSTAR
            "disney" in name -> "337"
            "apple tv" in name -> "350"
            "sonyliv" in name || "sony liv" in name -> "237"
            "zee5" in name -> "232"
            "crunchyroll" in name -> "283"
            else -> null
        }
    }

    /** Titles seen recently go last; once everything has been seen, start over. */
    private fun diversified(cacheName: String, shows: List<Show>): List<Show> {
        val seen = seenKeys(cacheName)
        val (old, fresh) = shows.shuffled().partition { it.key() in seen }
        if (fresh.isNotEmpty()) return fresh + old
        prefs.edit().remove(seenKey(cacheName)).apply()
        return old
    }

    private fun seenKeys(cacheName: String): LinkedHashSet<String> =
        LinkedHashSet(prefs.getString(seenKey(cacheName), null)?.split(',')?.filter { it.isNotBlank() }.orEmpty())

    private suspend fun fetchProvider(provider: String): List<Show> = try {
        withContext(Dispatchers.IO) {
            if (catalogueBaseUrl.isNotBlank()) return@withContext fetchFromCatalogue(provider)
            val providerIds = provider.split('|').filter { it.isNotBlank() }
            val tvShows = providerIds.flatMap { providerId -> fetchPage(providerId, "tv") }
            val shows = if (tvShows.isNotEmpty()) tvShows else {
                providerIds.flatMap { providerId -> fetchPage(providerId, "movie") }
            }
            val unique = LinkedHashMap<String, Show>()
            shows.forEach { show -> unique.putIfAbsent(show.backdropUrl, show) }
            unique.values.take(MAX_SHOWS)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList() // offline or the server is down; the hero falls back to its gradient
    }

    /** Direct-to-TMDB fallback, used only when no catalogue proxy is configured. */
    private fun fetchPage(providerId: String, mediaType: String): List<Show> {
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val encodedRegion = URLEncoder.encode(region, "UTF-8")
        val encodedProvider = URLEncoder.encode(providerId, "UTF-8")
        val url = "$API/discover/$mediaType?api_key=$encodedKey&watch_region=$encodedRegion" +
            "&with_watch_providers=$encodedProvider&sort_by=popularity.desc&include_adult=false" +
            "&with_watch_monetization_types=flatrate%7Cfree%7Cads&page=1"
        val results = JSONObject(Http.text(url)).getJSONArray("results")
        return (0 until results.length()).mapNotNull { index ->
            val item = results.getJSONObject(index)
            val path = item.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
            val title = item.optString("name").takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("title").takeIf { it.isNotBlank() && it != "null" }
            if (path != null && title != null) Show(item.optLong("id"), mediaType, title, "$IMAGES$path") else null
        }.take(MAX_SHOWS)
    }

    private fun fetchFromCatalogue(provider: String): List<Show> {
        val endpoint = catalogueBaseUrl.trimEnd('/')
        val query = "provider=${URLEncoder.encode(provider, "UTF-8")}" +
            "&region=${URLEncoder.encode(region, "UTF-8")}"
        val items = JSONObject(Http.text("$endpoint/api/catalogue?$query")).optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() }
            val path = item.optString("backdropPath").takeIf { it.startsWith('/') }
            if (title != null && path != null) {
                Show(
                    item.optLong("id"),
                    item.optString("mediaType").ifBlank { "tv" },
                    title,
                    "$endpoint/api/artwork?path=${URLEncoder.encode(path, "UTF-8")}",
                    item.optString("logoPath").takeIf { it.startsWith('/') },
                    item.optString("tag").takeIf { it.isNotBlank() }
                )
            } else null
        }.take(MAX_SHOWS)
    }

    private fun readCached(cacheName: String): List<Show> = try {
        val raw = prefs.getString(cacheKey(cacheName), null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title")
            val url = item.optString("url")
            val id = item.optLong("id")
            if (title.isNotBlank() && url.startsWith("http") && id > 0) {
                Show(
                    id, item.optString("type").ifBlank { "tv" }, title, url,
                    item.optString("logo").takeIf { it.startsWith('/') },
                    item.optString("tag").takeIf { it.isNotBlank() }
                )
            } else null
        }.take(MAX_SHOWS)
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeCached(cacheName: String, shows: List<Show>) {
        val array = JSONArray()
        shows.forEach { show ->
            array.put(
                JSONObject().put("id", show.id).put("type", show.mediaType)
                    .put("title", show.title).put("url", show.backdropUrl).put("logo", show.logoPath ?: "")
                    .put("tag", show.tag ?: "")
            )
        }
        prefs.edit().putString(cacheKey(cacheName), array.toString())
            .putLong(cacheTimeKey(cacheName), System.currentTimeMillis()).apply()
    }

    private fun Show.key() = "$mediaType:$id"
    private fun cacheKey(cacheName: String) = "shows_$cacheName"
    private fun cacheTimeKey(cacheName: String) = "time_$cacheName"
    private fun seenKey(cacheName: String) = "seen_$cacheName"

    private companion object {
        const val API = "https://api.themoviedb.org/3"
        const val IMAGES = "https://image.tmdb.org/t/p/w1280"
        const val HOTSTAR = "2336|122"
        const val MAX_SHOWS = 48
        const val MAX_SEEN = 80
        const val IMAGE_CACHE_SIZE = 6
        // New releases move faster than a day, but not by the hour; 6 h matches the server cache.
        const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L

        val PROVIDERS = mapOf(
            "com.netflix.ninja" to "8",
            "com.amazon.amazonvideo.livingroom" to "9|119",
            "in.startv.hotstar" to HOTSTAR,
            "in.startv.hotstar.dplus.tv" to HOTSTAR,
            "com.jio.hotstar" to HOTSTAR,
            "com.jiohotstar.tv" to HOTSTAR,
            "com.disney.disneyplus" to "337",
            "com.apple.atve.androidtv.appletv" to "350",
            "com.apple.atve.sony.appletv" to "350",
            "com.sonyliv" to "237",
            "com.graymatrix.did" to "232",
            "com.crunchyroll.crunchyroid" to "283"
        )
    }
}
