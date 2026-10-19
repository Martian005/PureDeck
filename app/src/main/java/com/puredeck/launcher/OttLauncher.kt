package com.puredeck.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/** Opens a title inside the streaming app that carries it, rather than just that app's home screen. */
class OttLauncher(
    private val context: Context,
    private val repo: AppRepository,
    private val installedApps: () -> List<AppEntry>
) {

    enum class Result { OPENED_TITLE, OPENED_APP_ONLY, NOT_INSTALLED, FAILED }

    fun installedApp(provider: Provider): AppEntry? {
        val apps = installedApps()
        val known = PACKAGES[provider.id].orEmpty()
        apps.firstOrNull { it.packageName in known }?.let { return it }
        // Services without a known package are matched by name, e.g. "MX Player" to its app label.
        val wanted = normalize(provider.name)
        return apps.firstOrNull { app ->
            val label = normalize(app.label)
            label.length >= MIN_NAME_MATCH && wanted.length >= MIN_NAME_MATCH &&
                (label in wanted || wanted in label)
        }
    }

    /**
     * Tries the title's own link inside the app first. If the app doesn't claim that link it falls
     * back to opening the app, which is the most that can be done without a vendor deep-link API.
     */
    fun open(provider: Provider): Result {
        val app = installedApp(provider) ?: return Result.NOT_INSTALLED
        for (intent in intentsFor(provider, app.packageName)) {
            try {
                context.startActivity(intent)
                return Result.OPENED_TITLE
            } catch (_: ActivityNotFoundException) {
                // This app doesn't handle that link; try the next form.
            } catch (_: SecurityException) {
                // Same: the matching activity isn't exported to us.
            }
        }
        val launch = repo.launchIntentFor(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return Result.FAILED
        return try {
            context.startActivity(launch)
            Result.OPENED_APP_ONLY
        } catch (_: ActivityNotFoundException) {
            Result.FAILED
        }
    }

    /** Intents to try, best first. */
    private fun intentsFor(provider: Provider, pkg: String): List<Intent> {
        val intents = mutableListOf<Intent>()
        val netflixId = provider.url?.let { NETFLIX_TITLE.find(it)?.groupValues?.get(1) }
        if (pkg == NETFLIX_PACKAGE && netflixId != null) {
            // The form Netflix's own recommendations use on the Android TV home: an explicit
            // component plus the source and deeplink extras it reads to open a title.
            intents += Intent(Intent.ACTION_VIEW, Uri.parse("http://www.netflix.com/title/$netflixId"))
                .setClassName(NETFLIX_PACKAGE, "$NETFLIX_PACKAGE.MainActivity")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("source_type", NETFLIX_SOURCE_TYPE)
                .putExtra("deeplink", "m=$netflixId&source_type=$NETFLIX_SOURCE_TYPE")
        }
        for (link in linksFor(provider)) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
            // Use the activity the app itself says handles this link, when there is one.
            val handler = context.packageManager.queryIntentActivities(view, 0).firstOrNull()?.activityInfo
            if (handler != null) view.setClassName(handler.packageName, handler.name)
            intents += view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intents
    }

    /** The service's web link plus the app-native forms of it that are known to exist. */
    private fun linksFor(provider: Provider): List<String> {
        val url = provider.url?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val links = mutableListOf(url)
        PRIME_DP.find(url)?.let { links += "https://app.primevideo.com/detail?gti=${it.groupValues[1]}" }
        NETFLIX_TITLE.find(url)?.let { links += "nflx://www.netflix.com/title/${it.groupValues[1]}" }
        return links
    }

    private fun normalize(name: String) = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private companion object {
        const val MIN_NAME_MATCH = 5
        const val NETFLIX_PACKAGE = "com.netflix.ninja"
        const val NETFLIX_SOURCE_TYPE = 27
        val PRIME_DP = Regex("""primevideo\.com/dp/(amzn1\.dv\.gti\.[\w-]+)""")
        val NETFLIX_TITLE = Regex("""netflix\.com/title/(\d+)""")

        private val HOTSTAR = setOf(
            "in.startv.hotstar", "in.startv.hotstar.dplus.tv", "com.jio.hotstar", "com.jiohotstar.tv"
        )
        private val PRIME = setOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")

        /** TMDB provider id -> Android packages of that service's TV app. */
        val PACKAGES = mapOf(
            8 to setOf("com.netflix.ninja"),
            9 to PRIME,
            119 to PRIME,
            2100 to PRIME,
            122 to HOTSTAR,
            2336 to HOTSTAR,
            337 to setOf("com.disney.disneyplus"),
            350 to setOf("com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv"),
            232 to setOf("com.graymatrix.did"),
            237 to setOf("com.sonyliv"),
            192 to setOf("com.google.android.youtube.tv"),
            283 to setOf("com.crunchyroll.crunchyroid")
        )
    }
}
