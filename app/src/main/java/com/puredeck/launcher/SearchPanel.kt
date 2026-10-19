package com.puredeck.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.widget.doAfterTextChanged
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Global search: type a title, see its artwork, description and which services in India carry it,
 * then pick a service to open that exact title in its app.
 */
class SearchPanel(
    private val activity: Activity,
    root: View,
    private val anchor: View,
    private val client: CatalogueClient,
    private val launcher: OttLauncher,
    private val scope: CoroutineScope,
    private val onOpened: () -> Unit,
    private val onClosed: () -> Unit
) {
    private val rootView = root
    private val card: View = root.findViewById(R.id.searchCard)
    private val scrim: View = root.findViewById(R.id.searchScrim)
    private val input: EditText = root.findViewById(R.id.searchInput)
    private val status: TextView = root.findViewById(R.id.searchStatus)
    private val results: RecyclerView = root.findViewById(R.id.searchResults)
    private val detail: View = root.findViewById(R.id.detailScroll)
    private val backdrop: ImageView = root.findViewById(R.id.detailBackdrop)
    private val title: TextView = root.findViewById(R.id.detailTitle)
    private val titleLogo: ImageView = root.findViewById(R.id.detailLogo)
    private val meta: TextView = root.findViewById(R.id.detailMeta)
    private val overview: TextView = root.findViewById(R.id.detailOverview)
    private val providerRow: LinearLayout = root.findViewById(R.id.providerRow)
    private val providerNote: TextView = root.findViewById(R.id.providerNote)

    private val adapter = ResultAdapter()
    private val popX = pop(DynamicAnimation.SCALE_X)
    private val popY = pop(DynamicAnimation.SCALE_Y)
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var shownQuery = ""

    var isOpen = false
        private set

    init {
        results.layoutManager = LinearLayoutManager(activity)
        results.adapter = adapter
        input.doAfterTextChanged { schedule(it?.toString().orEmpty()) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search(input.text.toString().trim())
                hideKeyboard()
                results.post { results.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                true
            } else false
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        rootView.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(SCRIM_MS).setInterpolator(EASE).start()
        anchor.animate().alpha(0f).setDuration(FADE_MS).start()

        // Grows out of the top-right, where the search button is.
        card.pivotX = card.width.toFloat()
        card.pivotY = 0f
        popX.cancel()
        popY.cancel()
        card.scaleX = START_SCALE
        card.scaleY = START_SCALE
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(EASE).withLayer().start()
        popX.animateToFinalPosition(1f)
        popY.animateToFinalPosition(1f)

        if (adapter.itemCount == 0) showStatus(activity.getString(R.string.search_prompt))
        input.requestFocus()
        input.post { showKeyboard() }
        onOpened()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        searchJob?.cancel()
        detailJob?.cancel()
        hideKeyboard()
        scrim.animate().alpha(0f).setDuration(CLOSE_MS).setInterpolator(EASE).start()
        anchor.animate().alpha(1f).setDuration(CLOSE_MS).start()
        popX.animateToFinalPosition(END_SCALE)
        popY.animateToFinalPosition(END_SCALE)
        card.animate().alpha(0f).setDuration(CLOSE_MS).setInterpolator(EASE).withLayer()
            .withEndAction { if (!isOpen) rootView.visibility = View.INVISIBLE }
            .start()
        onClosed()
    }

    // ---- searching ----

    /** Waits for a pause in typing so each keystroke doesn't become a request. */
    private fun schedule(text: String) {
        searchJob?.cancel()
        val query = text.trim()
        if (query.isEmpty()) {
            adapter.submit(emptyList())
            detail.alpha = 0f
            showStatus(activity.getString(R.string.search_prompt))
            return
        }
        searchJob = scope.launch {
            delay(TYPING_PAUSE_MS)
            runSearch(query)
        }
    }

    private fun search(query: String) {
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = scope.launch { runSearch(query) }
    }

    private suspend fun runSearch(query: String) {
        if (query == shownQuery && adapter.itemCount > 0) return
        showStatus(activity.getString(R.string.search_searching))
        try {
            val hits = client.search(query)
            shownQuery = query
            adapter.submit(hits)
            if (hits.isEmpty()) {
                detail.alpha = 0f
                showStatus(activity.getString(R.string.search_none, query))
            } else {
                status.visibility = View.GONE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            adapter.submit(emptyList())
            // 404: the catalogue service predates search and hasn't been redeployed yet.
            val missing = (e as? HttpStatusException)?.code == 404
            showStatus(activity.getString(if (missing) R.string.search_unavailable else R.string.search_error))
        }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    // ---- detail pane ----

    /** Called as focus moves down the result list; waits briefly so skimming doesn't fetch every title. */
    private fun showDetail(hit: SearchHit) {
        detailJob?.cancel()
        detail.animate().alpha(1f).setDuration(FADE_MS).start()
        title.text = hit.title
        // Text first; the show's own logo replaces it once (and if) it loads.
        title.visibility = View.VISIBLE
        titleLogo.visibility = View.GONE
        meta.text = describe(hit.year, hit.mediaType, null, null, emptyList())
        overview.text = hit.overview
        backdrop.setImageDrawable(null)
        renderProviders(null)
        detailJob = scope.launch {
            hit.backdropPath?.let { path -> client.image(path, "w780")?.let { setBackdrop(it) } }
            delay(DETAIL_DEBOUNCE_MS)
            try {
                val full = client.detail(hit.mediaType, hit.id)
                meta.text = describe(full.year, full.mediaType, full.meta, full.rating, full.genres)
                if (full.overview.isNotBlank()) overview.text = full.overview
                renderProviders(full)
                full.logoPath?.let { client.logo(it) }?.let { logo ->
                    titleLogo.setImageBitmap(logo.bitmap)
                    titleLogo.colorFilter = if (logo.needsLightening) LogoWhitenFilter else null
                    titleLogo.contentDescription = full.title
                    titleLogo.visibility = View.VISIBLE
                    title.visibility = View.GONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                providerRow.removeAllViews()
                providerNote.text = activity.getString(R.string.search_error)
            }
        }
    }

    private fun setBackdrop(bitmap: Bitmap) {
        backdrop.setImageBitmap(bitmap)
        backdrop.alpha = 0f
        backdrop.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    private fun describe(year: String?, type: String, extra: String?, rating: Double?, genres: List<String>): String =
        listOfNotNull(
            year,
            activity.getString(if (type == "tv") R.string.type_tv else R.string.type_movie),
            extra,
            rating?.let { "★ $it" },
            genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() }
        ).joinToString("  ·  ")

    /** [detail] null = still loading. */
    private fun renderProviders(detail: TitleDetail?) {
        providerRow.removeAllViews()
        providerNote.text = ""
        if (detail == null) {
            providerNote.text = activity.getString(R.string.checking_availability)
            return
        }
        if (detail.providers.isEmpty()) {
            providerNote.text = activity.getString(R.string.no_providers)
            return
        }
        val inflater = LayoutInflater.from(activity)
        detail.providers.forEach { provider ->
            val installed = launcher.installedApp(provider) != null
            val chip = inflater.inflate(R.layout.item_provider, providerRow, false)
            chip.findViewById<TextView>(R.id.providerName).text = provider.name
            chip.findViewById<TextView>(R.id.providerKind).text =
                if (installed) kindLabel(provider.kind) else activity.getString(R.string.not_installed)
            chip.alpha = if (installed) 1f else NOT_INSTALLED_ALPHA
            val logo = chip.findViewById<ImageView>(R.id.providerLogo)
            provider.logoPath?.let { path -> scope.launch { client.image(path, "w92")?.let { logo.setImageDrawable(rounded(it, PROVIDER_RADIUS_DP)) } } }
            chip.setOnClickListener { open(provider, installed) }
            providerRow.addView(chip)
        }
        if (detail.providers.none { it.url != null }) {
            providerNote.text = activity.getString(R.string.provider_note_no_link)
        }
    }

    private fun kindLabel(kind: String) = activity.getString(
        when (kind) {
            "free" -> R.string.kind_free
            "ads" -> R.string.kind_ads
            "rent" -> R.string.kind_rent
            "buy" -> R.string.kind_buy
            else -> R.string.kind_included
        }
    )

    private fun open(provider: Provider, installed: Boolean) {
        if (!installed) {
            toast(activity.getString(R.string.app_missing, provider.name))
            return
        }
        val shown = title.text.toString()
        when (launcher.open(provider)) {
            OttLauncher.Result.OPENED_TITLE -> {
                toast(activity.getString(R.string.opening_title, shown, provider.name))
                close()
            }
            OttLauncher.Result.OPENED_APP_ONLY -> {
                toast(activity.getString(R.string.opened_app_only, provider.name, shown))
                close()
            }
            OttLauncher.Result.NOT_INSTALLED -> toast(activity.getString(R.string.app_missing, provider.name))
            OttLauncher.Result.FAILED -> toast(activity.getString(R.string.cannot_open_app, provider.name))
        }
    }

    // ---- plumbing ----

    /**
     * Corners baked into the drawable. Rounding the ImageView with clipToOutline worked on the
     * emulator but left square corners on some images on the Android 8 TV.
     */
    private fun rounded(bitmap: Bitmap, radiusDp: Float): Drawable =
        RoundedBitmapDrawableFactory.create(activity.resources, bitmap).apply {
            cornerRadius = radiusDp * activity.resources.displayMetrics.density
            setAntiAlias(true)
        }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun showKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun pop(property: DynamicAnimation.ViewProperty) =
        SpringAnimation(card, property).setSpring(
            SpringForce(1f).setStiffness(STIFFNESS).setDampingRatio(DAMPING)
        )

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.VH>() {
        private var hits: List<SearchHit> = emptyList()

        fun submit(list: List<SearchHit>) {
            hits = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = hits.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(hits[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val poster: ImageView = view.findViewById(R.id.resultPoster)
            private val name: TextView = view.findViewById(R.id.resultTitle)
            private val sub: TextView = view.findViewById(R.id.resultSub)
            private var hit: SearchHit? = null

            init {
                view.setOnFocusChangeListener { _, focused -> if (focused) hit?.let(::showDetail) }
                view.setOnClickListener { hit?.let(::showDetail) }
            }

            fun bind(item: SearchHit) {
                hit = item
                name.text = item.title
                sub.text = listOfNotNull(
                    item.year,
                    activity.getString(if (item.mediaType == "tv") R.string.type_tv else R.string.type_movie)
                ).joinToString("  ·  ")
                poster.setImageDrawable(null)
                val path = item.posterPath ?: return
                poster.tag = path
                scope.launch {
                    val bitmap = client.image(path, "w154") ?: return@launch
                    // Rows are recycled: only paint if this one still wants this poster.
                    if (poster.tag == path) poster.setImageDrawable(rounded(bitmap, POSTER_RADIUS_DP))
                }
            }
        }
    }

    private companion object {
        const val TYPING_PAUSE_MS = 450L
        const val DETAIL_DEBOUNCE_MS = 250L
        const val START_SCALE = 0.92f
        const val END_SCALE = 0.96f
        const val STIFFNESS = 380f
        const val DAMPING = 0.78f
        const val SCRIM_MS = 260L
        const val FADE_MS = 200L
        const val CLOSE_MS = 170L
        const val NOT_INSTALLED_ALPHA = 0.55f
        const val PROVIDER_RADIUS_DP = 10f
        const val POSTER_RADIUS_DP = 8f
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
