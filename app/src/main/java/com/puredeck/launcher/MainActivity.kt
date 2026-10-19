package com.puredeck.launcher

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class MainActivity : AppCompatActivity(), LauncherAdapter.Listener {

    private lateinit var repo: AppRepository
    private lateinit var favorites: FavoritesStore
    private lateinit var adapter: LauncherAdapter
    private lateinit var grid: RecyclerView
    private lateinit var hero: HeroController
    private lateinit var panel: QuickPanel
    private lateinit var search: SearchPanel
    private lateinit var ott: OttLauncher
    private lateinit var actionsSheet: AppActionsSheet
    private lateinit var profile: View
    private lateinit var searchButton: View
    private lateinit var clock: TextView
    private lateinit var moveHint: TextView

    private var lastFocused: String? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            ticker.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    // A formatter built once keeps the time zone it was created with, so changing the TV's
    // zone or time left the old time on screen until the app restarted. Build one per update.
    private fun updateClock() {
        val now = DateFormat.getTimeFormat(this).format(Date())
        clock.text = now
        panel.setTime(now)
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    // A sideload or uninstall while the home screen is showing never passes through
    // onResume, so listen for it directly.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = AppRepository(this)
        favorites = FavoritesStore(this)
        clock = findViewById(R.id.clock)
        moveHint = findViewById(R.id.moveHint)
        profile = findViewById(R.id.profile)
        searchButton = findViewById(R.id.searchButton)

        val trending = TrendingSource(
            this,
            BuildConfig.TMDB_API_KEY,
            BuildConfig.TMDB_REGION,
            BuildConfig.CATALOGUE_BASE_URL
        )
        hero = HeroController(
            upper = findViewById(R.id.heroUpper),
            lower = findViewById(R.id.heroLower),
            tint = findViewById(R.id.heroTint),
            dimmer = findViewById(R.id.heroDimmer),
            titleBlock = findViewById(R.id.titleBlock),
            title = findViewById(R.id.heroTitle),
            logoView = findViewById(R.id.heroLogo),
            subtitle = findViewById(R.id.heroSubtitle),
            trending = trending,
            scope = lifecycleScope
        )

        panel = QuickPanel(
            this, findViewById(R.id.panelRoot), anchor = profile, apps = appActions,
            // While the card is up the grid behind it must not take focus: a stray
            // Left from a row would otherwise land on a tile you can't see.
            onOpened = { setBackgroundFocusable(false) },
            onClosed = {
                setBackgroundFocusable(true)
                profile.requestFocus()
            }
        )
        profile.setOnClickListener { panel.open() }

        ott = OttLauncher(this, repo) { adapter.allApps() }
        search = SearchPanel(
            this, findViewById(R.id.searchRoot), anchor = searchButton, client = trending.client,
            launcher = ott,
            scope = lifecycleScope,
            onOpened = { setBackgroundFocusable(false) },
            onClosed = {
                setBackgroundFocusable(true)
                searchButton.requestFocus()
            }
        )
        searchButton.setOnClickListener { search.open() }

        actionsSheet = AppActionsSheet(
            this, findViewById(R.id.actionsRoot),
            onOpened = { setBackgroundFocusable(false) },
            onClosed = {
                setBackgroundFocusable(true)
                focusPackage(lastFocused)
            }
        )

        adapter = LauncherAdapter(COLUMNS, this)
        grid = findViewById(R.id.appGrid)
        grid.layoutManager = FocusScrollLayoutManager(this, COLUMNS) { it < adapter.topRowSize }.apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = adapter.spanSize(position)
            }
        }
        grid.adapter = adapter
        grid.addItemDecoration(
            DockDecoration(
                ContextCompat.getDrawable(this, R.drawable.dock_bg)!!,
                resources.getDimensionPixelSize(R.dimen.dock_pad),
                resources.getDimensionPixelSize(R.dimen.grid_row_gap),
                resources.getDimensionPixelSize(R.dimen.dock_to_grid_gap),
                COLUMNS
            ) { adapter.topRowSize }
        )

        // Focus leaving the grid (profile pill, panel) clears the dim on every tile.
        grid.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null || !newFocus.isInside(grid)) {
                adapter.focusedPackage = null
                adapter.refreshTileStates(grid)
                hero.setDimmed(false)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // As the home app there is nowhere to go back to; Back only closes the panel.
            override fun handleOnBackPressed() {
                when {
                    actionsSheet.isOpen -> actionsSheet.close()
                    search.isOpen -> search.close()
                    panel.isOpen -> panel.back()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val timeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeReceiver, timeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(packageReceiver)
        unregisterReceiver(timeReceiver)
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
        reload()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        if (adapter.movingPackage != null) endMove()
    }

    private fun reload() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { repo.loadApps() }
            val byPackage = apps.associateBy { it.packageName }

            // First run seeds the top row; afterwards it is whatever the user arranged.
            // Uninstalled favourites drop out; new installs land in the grid, not the row.
            val saved = favorites.load()
                ?: apps.take(COLUMNS).map { it.packageName }.also(favorites::save)
            val pinned = saved.mapNotNull(byPackage::get).take(COLUMNS)
            val pinnedSet = pinned.mapTo(HashSet()) { it.packageName }

            adapter.submit(pinned, apps.filter { it.packageName !in pinnedSet })
            if (!panel.isOpen && !search.isOpen) focusPackage(lastFocused)
        }
    }

    /** Scrolls to and focuses a package's tile, or the first tile if it is gone. */
    private fun focusPackage(pkg: String?) {
        val pos = pkg?.let(adapter::positionOf)?.takeIf { it != RecyclerView.NO_POSITION } ?: 0
        grid.scrollToPosition(pos)
        grid.post { grid.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    override fun onAppFocused(entry: AppEntry, pinned: Boolean) {
        lastFocused = entry.packageName
        adapter.focusedPackage = entry.packageName
        adapter.refreshTileStates(grid)
        // App artwork only belongs to the pinned dock; the all-apps grid gets landscape photos.
        if (pinned) hero.show(entry) else hero.showScenery()
        hero.setDimmed(!pinned)
    }

    override fun onAppSelected(entry: AppEntry, view: View) {
        // The TV's own Settings tile opens PureDeck's glass settings instead; the system
        // app is still one row away inside it ("All Android TV settings").
        if (entry.packageName == settingsPackage) {
            panel.open(atSettings = true)
            return
        }
        // The dock launches straight away. In the all-apps grid, selecting an app offers actions
        // (including uninstall) so management lives there and quick launching stays in the dock.
        if (adapter.isPinned(entry.packageName)) launchApp(entry.packageName, entry.label, view)
        else showActions(entry, view)
    }

    private fun showActions(entry: AppEntry, from: View) {
        val pkg = entry.packageName
        fun row(label: Int, icon: Int, action: () -> Unit) = Item(
            getString(label), ContextCompat.getDrawable(this, icon), chevron = false,
            onClick = {
                actionsSheet.close()
                action()
            }
        )
        val rows = buildList<Row> {
            add(row(R.string.st_open, R.drawable.ic_qs_open) { launchApp(pkg, entry.label, from) })
            add(row(R.string.st_pin, R.drawable.ic_qs_pin) { appActions.togglePin(pkg) })
            add(row(R.string.st_app_info, R.drawable.ic_qs_info) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
            })
            if (!repo.isSystem(pkg)) {
                add(row(R.string.st_uninstall, R.drawable.ic_qs_delete) {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
                })
            }
        }
        actionsSheet.open(entry, rows)
    }

    /** Whichever app handles "open settings" on this TV, some makers don't use AOSP's package. */
    private val settingsPackage: String? by lazy {
        Intent(Settings.ACTION_SETTINGS).resolveActivity(packageManager)?.packageName
    }

    /** [from] is the tile to zoom out of, like tvOS opening from the icon; null = no zoom. */
    private fun launchApp(pkg: String, label: CharSequence, from: View?) {
        val intent = repo.launchIntentFor(pkg)
        if (intent == null) {
            toast(getString(R.string.cannot_launch, label))
            return
        }
        val options = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        try {
            startActivity(intent, options)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.cannot_launch, label))
        }
    }

    /** What the settings card's Apps pages can do to the launcher. */
    private val appActions = object : SettingsPages.AppActions {
        override fun all() = adapter.allApps()
        override fun isPinned(pkg: String) = adapter.isPinned(pkg)

        override fun togglePin(pkg: String): Boolean {
            val ok = if (adapter.isPinned(pkg)) {
                adapter.unpin(pkg)
                true
            } else {
                adapter.pin(pkg).also { if (!it) toast(getString(R.string.row_full)) }
            }
            if (ok) favorites.save(adapter.pinned.map { it.packageName })
            return ok
        }

        override fun open(pkg: String) {
            panel.close()
            launchApp(pkg, adapter.allApps().firstOrNull { it.packageName == pkg }?.label ?: pkg, from = null)
        }
    }

    override fun onAppLongPressed(entry: AppEntry) {
        adapter.movingPackage = entry.packageName
        adapter.refreshTileStates(grid)
        updateMoveHint(entry.packageName)
        moveHint.animate().alpha(1f).setDuration(HINT_MS).start()
    }

    /** Only offer the moves that apply where the app currently sits. */
    private fun updateMoveHint(pkg: String) {
        moveHint.text = getString(if (adapter.isPinned(pkg)) R.string.move_hint_pinned else R.string.move_hint_grid)
    }

    private fun endMove() {
        adapter.movingPackage = null
        adapter.refreshTileStates(grid)
        favorites.save(adapter.pinned.map { it.packageName })
        moveHint.animate().alpha(0f).setDuration(HINT_MS).start()
    }

    /** While an app is being moved, the D-pad drives the move instead of focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The remote's Settings button, when the TV delivers it to the launcher at all
        // (some builds handle it in the system first). Act on key-up so the matching
        if (event.keyCode == KeyEvent.KEYCODE_SETTINGS && adapter.movingPackage == null) {
            if (event.action == KeyEvent.ACTION_UP && !panel.isOpen && !search.isOpen) panel.open(atSettings = true)
            return true
        }
        val pkg = adapter.movingPackage ?: return super.dispatchKeyEvent(event)
        if (event.keyCode !in MOVE_KEYS) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleMoveKey(pkg, event.keyCode)
        return true
    }

    private fun handleMoveKey(pkg: String, keyCode: Int) {
        val pinned = adapter.isPinned(pkg)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (pinned) adapter.shiftPinned(pkg, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (pinned) adapter.shiftPinned(pkg, +1)
            KeyEvent.KEYCODE_DPAD_UP -> if (!pinned) {
                if (adapter.pin(pkg)) {
                    updateMoveHint(pkg)
                    focusPackage(pkg)
                } else {
                    toast(getString(R.string.row_full))
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (pinned) {
                adapter.unpin(pkg)
                endMove()
                focusPackage(pkg)
            }
            else -> endMove()
        }
    }

    /** While an overlay is up, nothing behind it may take focus: a stray D-pad press would land unseen. */
    private fun setBackgroundFocusable(focusable: Boolean) {
        grid.descendantFocusability =
            if (focusable) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        profile.isFocusable = focusable
        searchButton.isFocusable = focusable
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun View.isInside(parent: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    private companion object {
        const val COLUMNS = 6
        const val CLOCK_INTERVAL_MS = 20_000L
        const val HINT_MS = 200L
        val MOVE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK
        )
    }
}
