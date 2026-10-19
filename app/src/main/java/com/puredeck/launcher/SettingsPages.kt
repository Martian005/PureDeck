package com.puredeck.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import java.net.Inet4Address

/** PureDeck's own settings pages. */
class SettingsPages(
    private val activity: Activity,
    private val nav: Navigator,
    private val apps: AppActions
) {
    interface Navigator {
        fun push(page: Page)
        fun refresh()
        fun launch(intent: Intent)
    }

    interface AppActions {
        fun all(): List<AppEntry>
        fun isPinned(pkg: String): Boolean
        /** False if the app could not be pinned (top row full). */
        fun togglePin(pkg: String): Boolean
        fun open(pkg: String)
    }

    private val pm = activity.packageManager
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val connectivity = activity.getSystemService(ConnectivityManager::class.java)

    val deviceName: String =
        Settings.Global.getString(activity.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun root() = Page(null) {
        listOfNotNull(
            item(R.string.qs_settings, R.drawable.ic_qs_settings, chevron = true) { nav.push(home()) },
            external(R.string.qs_wifi, R.drawable.ic_qs_wifi, Settings.ACTION_WIFI_SETTINGS, wifiSummary()),
            external(R.string.qs_bluetooth, R.drawable.ic_qs_bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS),
            item(R.string.qs_sound, R.drawable.ic_qs_sound, volumeSummary(), chevron = true) { nav.push(sound()) },
            item(R.string.qs_apps, R.drawable.ic_qs_apps, chevron = true) { nav.push(appList()) },
            Header(str(R.string.qs_section_screensaver)),
            externalIntent(R.string.qs_screensaver_now, R.drawable.ic_qs_screensaver, screensaverNow()),
            external(R.string.qs_screensaver, R.drawable.ic_qs_settings, Settings.ACTION_DREAM_SETTINGS)
        )
    }

    fun home() = Page(str(R.string.qs_settings)) {
        listOfNotNull(
            item(R.string.st_network, R.drawable.ic_qs_wifi, connection().summary, chevron = true) { nav.push(network()) },
            item(R.string.qs_sound, R.drawable.ic_qs_sound, volumeSummary(), chevron = true) { nav.push(sound()) },
            external(R.string.qs_display, R.drawable.ic_qs_display, Settings.ACTION_DISPLAY_SETTINGS),
            item(R.string.qs_apps, R.drawable.ic_qs_apps, chevron = true) { nav.push(appList()) },
            item(R.string.qs_section_screensaver, R.drawable.ic_qs_screensaver, chevron = true) { nav.push(screensaver()) },
            item(R.string.st_storage, R.drawable.ic_qs_storage, storage().free + " " + str(R.string.st_free), chevron = true) { nav.push(storagePage()) },
            item(R.string.st_about, R.drawable.ic_qs_info, deviceName, chevron = true) { nav.push(about()) },
            Header(str(R.string.st_system)),
            external(R.string.st_all_settings, R.drawable.ic_qs_settings, Settings.ACTION_SETTINGS)
        )
    }

    private fun network() = Page(str(R.string.st_network)) {
        val c = connection()
        listOfNotNull(
            info(R.string.st_status, c.summary),
            c.ip?.let { info(R.string.st_ip, it) },
            external(R.string.st_wifi_settings, R.drawable.ic_qs_wifi, Settings.ACTION_WIFI_SETTINGS)
        )
    }

    private fun sound() = Page(str(R.string.qs_sound)) {
        val stream = AudioManager.STREAM_MUSIC
        val muted = audio.isStreamMute(stream)
        listOfNotNull(
            Slider(
                str(R.string.st_volume), icon(R.drawable.ic_qs_sound), audio.getStreamMaxVolume(stream),
                get = { audio.getStreamVolume(stream) },
                set = { audio.setStreamVolume(stream, it, 0) }
            ),
            item(
                R.string.st_mute, if (muted) R.drawable.ic_qs_mute else R.drawable.ic_qs_sound,
                value = str(if (muted) R.string.st_on else R.string.st_off)
            ) {
                val direction = if (audio.isStreamMute(stream)) {
                    AudioManager.ADJUST_UNMUTE
                } else {
                    AudioManager.ADJUST_MUTE
                }
                audio.adjustStreamVolume(stream, direction, 0)
                nav.refresh()
            },
            external(R.string.st_sound_settings, R.drawable.ic_qs_settings, Settings.ACTION_SOUND_SETTINGS)
        )
    }

    private fun appList() = Page(str(R.string.qs_apps)) {
        apps.all().map { app ->
            Item(
                app.label, app.icon.fresh(activity.resources),
                subtitle = if (apps.isPinned(app.packageName)) str(R.string.st_in_top_row) else null,
                chevron = true, tintIcon = false
            ) { nav.push(appDetail(app)) }
        }
    }

    private fun appDetail(app: AppEntry) = Page(app.label) {
        val pkg = app.packageName
        val system = runCatching {
            pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(true)
        listOfNotNull(
            item(R.string.st_open, R.drawable.ic_qs_open) { apps.open(pkg) },
            item(
                if (apps.isPinned(pkg)) R.string.st_unpin else R.string.st_pin, R.drawable.ic_qs_pin
            ) { if (apps.togglePin(pkg)) nav.refresh() },
            item(R.string.st_app_info, R.drawable.ic_qs_info) {
                nav.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
            },
            // System apps can only be disabled from App info, not uninstalled.
            if (system) null else item(R.string.st_uninstall, R.drawable.ic_qs_delete) {
                nav.launch(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
            }
        )
    }

    private fun screensaver() = Page(str(R.string.qs_section_screensaver)) {
        listOfNotNull(
            externalIntent(R.string.qs_screensaver_now, R.drawable.ic_qs_screensaver, screensaverNow()),
            external(R.string.qs_screensaver, R.drawable.ic_qs_settings, Settings.ACTION_DREAM_SETTINGS)
        )
    }

    private fun storagePage() = Page(str(R.string.st_storage)) {
        val s = storage()
        listOf(info(R.string.st_free, s.free), info(R.string.st_used, s.used), info(R.string.st_total, s.total))
    }

    private fun about() = Page(str(R.string.st_about)) {
        listOfNotNull(
            info(R.string.st_name, deviceName),
            info(R.string.st_model, "${Build.MANUFACTURER} ${Build.MODEL}"),
            info(R.string.st_android, "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            connection().ip?.let { info(R.string.st_ip, it) },
            info(R.string.st_launcher, "PureDeck ${BuildConfig.VERSION_NAME}"),
            info(R.string.st_tmdb, "This product uses the TMDB API but is not endorsed or certified by TMDB.")
        )
    }

    // ---- data ----

    private class Connection(val summary: String, val ip: String?, val onWifi: Boolean = false)

    private fun connection(): Connection {
        val net = connectivity.activeNetwork ?: return Connection(str(R.string.st_offline), null)
        val caps = connectivity.getNetworkCapabilities(net)
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> R.string.st_ethernet
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> R.string.qs_wifi
            else -> R.string.st_connected
        }
        val ip = connectivity.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
        return Connection(str(type), ip, onWifi = type == R.string.qs_wifi)
    }

    /** Under a row already labelled "Wi-Fi", say whether it's connected rather than repeat the word. */
    private fun wifiSummary(): String = connection().let { if (it.onWifi) str(R.string.st_connected) else it.summary }

    private class Storage(val free: String, val used: String, val total: String)

    private fun storage(): Storage {
        val stat = StatFs(Environment.getDataDirectory().path)
        val fmt = { bytes: Long -> Formatter.formatShortFileSize(activity, bytes) }
        return Storage(fmt(stat.availableBytes), fmt(stat.totalBytes - stat.availableBytes), fmt(stat.totalBytes))
    }

    private fun volumeSummary(): String {
        val stream = AudioManager.STREAM_MUSIC
        if (audio.isStreamMute(stream)) return str(R.string.st_muted)
        val pct = audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return activity.getString(R.string.percent, pct)
    }

    private fun screensaverNow() =
        Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")

    // ---- row helpers ----

    private fun item(
        @StringRes label: Int, @DrawableRes icon: Int, subtitle: String? = null,
        value: String? = null, chevron: Boolean = false, onClick: () -> Unit
    ) = Item(str(label), icon(icon), subtitle, value, chevron, onClick = onClick)

    private fun info(@StringRes label: Int, value: String) = Item(str(label), null, value = value)

    /** A row that opens a system screen, or nothing if this TV doesn't have that screen. */
    private fun external(@StringRes label: Int, @DrawableRes icon: Int, action: String, subtitle: String? = null) =
        externalIntent(label, icon, Intent(action), subtitle)

    private fun externalIntent(@StringRes label: Int, @DrawableRes icon: Int, intent: Intent, subtitle: String? = null) =
        if (intent.resolveActivity(pm) == null) null
        else Item(str(label), icon(icon), subtitle, onClick = { nav.launch(intent) })

    private fun str(@StringRes id: Int) = activity.getString(id)
    private fun icon(@DrawableRes id: Int) = ContextCompat.getDrawable(activity, id)
}
