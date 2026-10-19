package com.puredeck.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale
import kotlin.math.abs

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    /** Wide TV artwork, used as the hero backdrop. Not every app ships one. */
    val banner: Drawable?,
    /** Dominant colour of the app's artwork, used to tint the hero when there is no banner. */
    val accent: Int
)

/** Reads installed launcher activities; system/vendor apps may expose incomplete metadata. */
class AppRepository(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    fun loadApps(): List<AppEntry> {
        val leanback = resolve(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val standard = resolve(Intent.CATEGORY_LAUNCHER)

        return (leanback + standard)
            .filter { it.activityInfo.packageName != context.packageName }
            .groupBy { it.activityInfo.packageName }
            .values
            .mapNotNull { activities -> runCatching { toEntry(launchedActivity(activities)) }.getOrNull() }
            .sortedBy { it.label.lowercase() }
    }

    private fun resolve(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    /**
     * A package can expose several launcher activities (vendor apps often do). The tile must show
     * the label and icon of the one that actually opens, or it advertises one thing and starts another.
     */
    private fun launchedActivity(activities: List<ResolveInfo>): ResolveInfo {
        if (activities.size == 1) return activities.first()
        val target = launchIntentFor(activities.first().activityInfo.packageName)?.component
        return activities.firstOrNull {
            ComponentName(it.activityInfo.packageName, it.activityInfo.name) == target
        } ?: activities.first()
    }

    private fun toEntry(info: ResolveInfo): AppEntry {
        val label = runCatching { info.loadLabel(pm).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: info.activityInfo.packageName.substringAfterLast('.')
        val banner = loadBanner(info)
        val icon = loadIcon(info, label)
        return AppEntry(
            label = label,
            packageName = info.activityInfo.packageName,
            icon = icon,
            banner = banner,
            accent = (banner ?: icon).dominantColor()
        )
    }

    /** Leanback apps only need a banner, so many TV system apps declare no icon at all. */
    private fun loadIcon(info: ResolveInfo, label: String): Drawable {
        val activity = info.activityInfo
        val declared = activity.icon != 0 || activity.applicationInfo.icon != 0 || info.iconResource != 0
        if (declared) {
            val candidates = listOf<() -> Drawable?>(
                { info.loadIcon(pm) },
                { activity.loadIcon(pm) },
                { activity.applicationInfo.loadIcon(pm) }
            )
            for (load in candidates) {
                val drawable = runCatching { load() }.getOrNull() ?: continue
                if (!isDefaultIcon(drawable)) return drawable.fresh(context.resources)
            }
        }
        return letterIcon(label)
    }

    private fun isDefaultIcon(drawable: Drawable): Boolean {
        val default = runCatching { pm.defaultActivityIcon }.getOrNull() ?: return false
        val state = drawable.constantState ?: return false
        return state === default.constantState
    }

    private fun loadBanner(info: ResolveInfo): Drawable? {
        val candidates = listOf<() -> Drawable?>(
            { info.activityInfo.loadBanner(pm) },
            { info.activityInfo.applicationInfo.loadBanner(pm) }
        )
        for (load in candidates) {
            val drawable = runCatching { load() }.getOrNull() ?: continue
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            // XML banners (vectors, layer-lists) often report no intrinsic size; rejecting
            // them threw away real banners. Only refuse artwork that is clearly square.
            val unknownSize = width <= 0 || height <= 0
            if (unknownSize || width.toFloat() / height >= MIN_BANNER_RATIO) return drawable.fresh(context.resources)
        }
        return null
    }

    /** Stable per-app colour so the same app always gets the same tile. */
    private fun letterIcon(label: String): Drawable {
        val size = LETTER_ICON_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (abs(label.lowercase(Locale.ROOT).hashCode()) % 360).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.62f)) }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, paint)
        paint.apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.5f
            isFakeBoldText = true
        }
        val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return BitmapDrawable(context.resources, bitmap)
    }

    /** Built-in apps can't be uninstalled, only disabled from the system's app info screen. */
    fun isSystem(packageName: String): Boolean = runCatching {
        pm.getApplicationInfo(packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(true)

    /** Null when the app has no launchable entry point, so the caller must handle it. */
    fun launchIntentFor(packageName: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)

    private companion object {
        const val MIN_BANNER_RATIO = 1.3f
        const val LETTER_ICON_PX = 192
    }
}

/**
 * Drawables are stateful (bounds, callback): one instance shown by two views at once gets resized
 * and invalidated by whichever laid out last. Hand each view its own copy.
 */
fun Drawable.fresh(resources: Resources): Drawable =
    constantState?.newDrawable(resources)?.mutate() ?: this

/** Averages the drawable down to one pixel without changing bounds used elsewhere. */
private fun Drawable.dominantColor(): Int {
    val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val oldBounds = android.graphics.Rect(bounds)
    try {
        setBounds(0, 0, 1, 1)
        draw(Canvas(bmp))
        return bmp.getPixel(0, 0)
    } finally {
        setBounds(oldBounds)
        bmp.recycle()
    }
}
