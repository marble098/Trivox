package com.trivox.client.ui

// TRIVOX_V21_STABILITY_AUTO_LEAK_UI

// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

// TRIVOX_V19_NATIVE_WIREGUARD_LEAK_GUARD

import android.Manifest
import android.app.StatusBarManager
import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.compose.runtime.mutableIntStateOf
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trivox.client.R
import com.trivox.client.ui.compose.LegacyLayoutBridge
import com.trivox.client.ui.compose.ComposeBatchState
import com.trivox.client.ui.compose.ComposeProfileStats
import com.trivox.client.importing.SmartClipboardImporter
import com.trivox.client.ui.compose.MainComposeActions
import com.trivox.client.ui.compose.MainComposeScreen
import com.trivox.client.config.ConfigParser
import com.trivox.client.config.OpenSshProfileCodec
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManager
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ProfileImportResult
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import com.trivox.client.data.ProfileSortMode
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.SubscriptionKind
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.data.TestStatus
import com.trivox.client.network.CommunityConfigManager
import com.trivox.client.network.ConnectionInfoManager
import com.trivox.client.network.LeakProtectionManager
import com.trivox.client.network.PingManager
import com.trivox.client.network.SubscriptionManager
import com.trivox.client.network.SubscriptionRefreshCoordinator
import com.trivox.client.network.TunnelHealthVerifier
import com.trivox.client.update.UpdateChecker
import com.trivox.client.service.ConnectionPauseController
import com.trivox.client.service.ConnectionSwitchCoordinator
import com.trivox.client.service.ConnectionUsageStore
import com.trivox.client.service.ConnectionService
import com.trivox.client.service.NotificationSupport
import com.trivox.client.service.QuickConnectTileService
import com.trivox.client.service.TrivoxVpnService
import com.trivox.client.util.Diagnostics
import com.trivox.client.util.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ThemedActivity(), MainComposeActions {
    private lateinit var repository: ConfigRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var coreManager: CoreManager
    private lateinit var adapter: ProfileAdapter
    private lateinit var pingManager: PingManager

    private lateinit var list: RecyclerView
    private lateinit var stateText: TextView
    private lateinit var durationText: TextView
    private lateinit var selectedText: TextView
    private lateinit var livePingText: Button
    private lateinit var realDelayText: Button
    private lateinit var exitInfoText: TextView
    private lateinit var emptyText: TextView
    private lateinit var connectButton: Button
    private lateinit var pauseButton: Button
    private lateinit var tcpPingButton: Button
    private lateinit var realDelayAllButton: Button
    private lateinit var quickToolsPanel: View
    private lateinit var quickToolsToggle: AppCompatImageButton
    private lateinit var modeSpinner: Spinner
    private lateinit var refreshExitButton: Button
    private lateinit var copySummaryButton: Button
    private lateinit var subscriptionTabs: LinearLayout
    private lateinit var refreshSubscriptionsButton: Button
    private lateinit var subscriptionUpdater: SubscriptionRefreshCoordinator

    private val worker =
        Executors.newSingleThreadExecutor()
    private val latencyWorker =
        Executors.newSingleThreadExecutor()
    private val storageWorker =
        Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val livePingBusy =
        AtomicBoolean(false)
    private val manualLivePingRequested =
        AtomicBoolean(false)
    private val realDelayBusy =
        AtomicBoolean(false)
    private val leakCheckBusy =
        AtomicBoolean(false)
    private val lastAutoLeakSessionId =
        AtomicLong(-1L)
    private val connectionActionBusy =
        AtomicBoolean(false)
    private val connectionStartPending =
        AtomicBoolean(false)
    private val pingGeneration =
        AtomicLong(0)
    private val pingTasks =
        mutableListOf<Future<*>>()
    private val subscriptionRefreshBusy =
        AtomicBoolean(false)
    private val communitySyncBusy =
        AtomicBoolean(false)
    private val activityDestroyed =
        AtomicBoolean(false)
    private val uiResumed =
        AtomicBoolean(false)
    private val pingResultBuffer =
        ConcurrentHashMap<String, PingResult>()
    private val pingFlushScheduled =
        AtomicBoolean(false)
    private val composeRevisionState = mutableIntStateOf(0)
    private val composeBatchRunning = AtomicBoolean(false)
    private val composeBatchCompleted = AtomicInteger(0)
    private val composeBatchTotal = AtomicInteger(0)
    @Volatile
    private var composeBatchMethod: PingMethod? = null

    @Volatile
    private var livePingResult:
        PingResult? = null
    @Volatile
    private var realDelayResult:
        PingResult? = null

    private var pendingSwitchProfileId:
        String? = null
    private var pendingSwitchMode:
        ConnectionMode? = null
    private var filter = ""
    private var importDialogInput: EditText? = null
    private var pendingFileImportTargetId: String? = null
    private var pendingVpnProfile: String? = null
    private var lastInfoProfileId: String? = null
    private var activeSubscriptionId: String? = null
    private var subscriptionTabsSignature = ""
    private var quickToolsExpanded = false
    private var lastUiRuntimeState: ConnectionState? = null
    private val magicClipboardBusy = AtomicBoolean(false)
    @Volatile private var composeUpdateStatusText = ""
    @Volatile private var composeCommunityStatusText = ""
    @Volatile private var composeLeakStatusText = ""
    @Volatile private var composeProfileStatsCache = ComposeProfileStats()

    private val filePicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            val targetId =
                pendingFileImportTargetId
                    .also {
                        pendingFileImportTargetId = null
                    }
            uri ?: return@registerForActivityResult

            worker.execute {
                runCatching {
                    contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use(
                            ::readConfigurationText
                        )
                        ?: error(
                            "Unable to open the selected file"
                        )
                }.onSuccess { content ->
                    importText(
                        content,
                        targetId
                    )
                }.onFailure {
                        throwable ->
                    runOnUiThread {
                        toast(
                            getString(
                                R.string.import_failed,
                                throwable.message
                                    ?: getString(
                                        R.string.unknown_error
                                    )
                            )
                        )
                    }
                }
            }
        }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val id = pendingVpnProfile
            pendingVpnProfile = null

            if (
                id != null &&
                VpnService.prepare(this) == null
            ) {
                startVpn(id)
            } else if (id != null) {
                clearConnectionStartPending()
                toast(
                    getString(
                        R.string.permission_required
                    )
                )
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val runtimeListener: (ConnectionRuntime.Snapshot) -> Unit = {
        runOnUiThread {
            renderState(it)
            completePendingProfileSwitch(it)
            scheduleAutomaticLeakCheck(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val composeHost = LegacyLayoutBridge.install(this, "activity_main")

        repository = ConfigRepository(this)
        settingsRepository = SettingsRepository(this)
        coreManager = CoreManager(this)
        pingManager =
            PingManager(
                coreManager.adapter
            )
        subscriptionUpdater =
            SubscriptionRefreshCoordinator(this)

        bindViews()
        setupToolbar()
        setupList()
        setupControls()
        repairStaleTestingStates()
        handleShareIntent(intent)

        ConnectionRuntime.addListener(runtimeListener)
        renderState(ConnectionRuntime.current())
        composeHost.compose.setContent {
            MainComposeScreen(this@MainActivity, this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun bindViews() {
        list = findViewById(R.id.profileList)
        stateText = findViewById(R.id.stateText)
        durationText = findViewById(R.id.durationText)
        selectedText = findViewById(R.id.selectedText)
        livePingText = findViewById(R.id.livePingText)
        realDelayText = findViewById(R.id.realDelayText)
        exitInfoText = findViewById(R.id.exitInfoText)
        emptyText = findViewById(R.id.emptyText)
        connectButton = findViewById(R.id.connectButton)
        pauseButton = findViewById(R.id.pauseButton)
        tcpPingButton = findViewById(R.id.testButton)
        realDelayAllButton =
            findViewById(R.id.realDelayAllButton)
        quickToolsPanel =
            findViewById(R.id.quickToolsPanel)
        quickToolsToggle =
            findViewById(R.id.quickToolsToggle)
        modeSpinner = findViewById(R.id.modeSpinner)
        refreshExitButton = findViewById(R.id.refreshExitButton)
        copySummaryButton = findViewById(R.id.copySummaryButton)
        subscriptionTabs = findViewById(R.id.subscriptionTabs)
        refreshSubscriptionsButton =
            findViewById(R.id.refreshSubscriptionsButton)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.subtitle = null
        val viewItem =
            toolbar.menu.add(
                0,
                MENU_VIEW,
                0,
                R.string.grid
            )
        viewItem.icon =
            AppCompatResources.getDrawable(
                this,
                R.drawable.ic_view_grid_compact
            )
        viewItem.setShowAsAction(
            MenuItem.SHOW_AS_ACTION_ALWAYS
        )
        viewItem.contentDescription =
            getString(R.string.grid)
        toolbar.menu.add(0, MENU_FASTEST, 1, R.string.select_fastest)
        toolbar.menu.add(0, MENU_COPY_PROXY, 2, R.string.copy_local_proxy)
        toolbar.menu.add(0, MENU_EXPORT_BACKUP, 3, R.string.export_backup)
        toolbar.menu.add(0, MENU_CLEAR_DEAD, 4, R.string.clear_dead_profiles)
        toolbar.menu.add(0, MENU_SETTINGS, 5, R.string.settings)
        toolbar.menu.add(0, MENU_ROUTING, 6, R.string.app_routing)
        toolbar.menu.add(0, MENU_DIAGNOSTICS, 7, R.string.diagnostics)

        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_VIEW -> {
                    val settings = settingsRepository.load()
                    settings.gridMode = !settings.gridMode
                    settingsRepository.save(settings)
                    applyLayout()
                    true
                }

                MENU_FASTEST -> {
                    selectFastestProfile()
                    true
                }

                MENU_COPY_PROXY -> {
                    copyLocalProxyEndpoint()
                    true
                }

                MENU_EXPORT_BACKUP -> {
                    exportCompleteBackup()
                    true
                }

                MENU_CLEAR_DEAD -> {
                    confirmClearDeadProfiles()
                    true
                }

                MENU_SETTINGS -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                MENU_ROUTING -> {
                    startActivity(Intent(this, AppRoutingActivity::class.java))
                    true
                }

                MENU_DIAGNOSTICS -> {
                    startActivity(Intent(this, DiagnosticsActivity::class.java))
                    true
                }

                else -> false
            }
        }
    }

    private fun setupList() {
        adapter = ProfileAdapter(
            onClick = ::selectProfile,
            onLongClick = ::showActions,
            onAction = ::showActions,
            onTcpPing = {
                testSingleProfile(
                    it,
                    PingMethod.TCP_CONNECT
                )
            },
            onRealPing = {
                testSingleProfile(
                    it,
                    PingMethod.XRAY_HTTP
                )
            }
        )

        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        applyLayout()
    }

    private fun applyLayout() {
        val grid = settingsRepository.load().gridMode
        list.layoutManager =
            if (grid) {
                GridLayoutManager(
                    this,
                    if (resources.configuration.screenWidthDp >= 700) 3 else 2
                )
            } else {
                LinearLayoutManager(this)
            }
        refresh()
    }

    private fun setupControls() {
        activeSubscriptionId =
            getSharedPreferences(
                MAIN_UI_PREFS,
                MODE_PRIVATE
            ).getString(
                KEY_ACTIVE_SUBSCRIPTION,
                null
            )

        renderSubscriptionTabs(
            force = true
        )

        quickToolsExpanded =
            getSharedPreferences(
                MAIN_UI_PREFS,
                MODE_PRIVATE
            ).getBoolean(
                KEY_QUICK_TOOLS_EXPANDED,
                false
            )
        renderQuickTools()
        quickToolsToggle
            .setOnClickListener {
                quickToolsExpanded =
                    !quickToolsExpanded
                getSharedPreferences(
                    MAIN_UI_PREFS,
                    MODE_PRIVATE
                ).edit()
                    .putBoolean(
                        KEY_QUICK_TOOLS_EXPANDED,
                        quickToolsExpanded
                    )
                    .apply()
                renderQuickTools()
            }

        livePingText
            .setOnClickListener {
                requestLivePingNow()
            }
        realDelayText
            .setOnClickListener {
                requestRealDelayNow()
            }

        modeSpinner.adapter = compactAdapter(
            arrayOf(
                getString(R.string.proxy_mode),
                getString(R.string.vpn_mode)
            )
        )
        modeSpinner.setSelection(
            if (settingsRepository.load().mode == ConnectionMode.PROXY) 0 else 1
        )
        modeSpinner.onItemSelectedListener =
            SimpleItemSelectedListener { position ->
                val settings = settingsRepository.load()
                settings.mode =
                    if (position == 0) ConnectionMode.PROXY else ConnectionMode.VPN
                settingsRepository.save(settings)
            }

        findViewById<EditText>(R.id.searchInput)
            .addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        filter = s?.toString().orEmpty()
                        refresh()
                    }

                    override fun afterTextChanged(s: Editable?) = Unit
                }
            )

        findViewById<Button>(R.id.addButton)
            .setOnClickListener { showAddOptions() }
        refreshSubscriptionsButton
            .setOnClickListener { refreshSubscriptionsFromMain() }
        tcpPingButton
            .setOnClickListener {
                testProfiles(
                    PingMethod.TCP_CONNECT
                )
            }
        realDelayAllButton
            .setOnClickListener {
                testProfiles(
                    PingMethod.XRAY_HTTP
                )
            }
        connectButton.setOnClickListener {
            toggleConnection()
        }
        pauseButton.setOnClickListener {
            showPauseOptions()
        }
        refreshExitButton.setOnClickListener {
            refreshExitInfo(force = true, showError = true)
        }
        copySummaryButton.setOnClickListener { copyConnectionSummary() }
    }


    private fun showAddOptions() {
        val labels = arrayOf(
            getString(R.string.import_existing),
            getString(R.string.add_url_subscription),
            getString(R.string.add_nordvpn_subscription),
            getString(R.string.add_manual_config),
            getString(R.string.add_proxy_chain)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.add_options)
            .setItems(labels) { _, position ->
                when (position) {
                    0 -> showImportDialog()
                    1 -> openSubscriptionManager(
                        kind = SubscriptionKind.URL
                    )
                    2 -> openSubscriptionManager(
                        kind = SubscriptionKind.NORDVPN
                    )
                    3 -> startActivity(
                        Intent(
                            this,
                            ManualConfigActivity::class.java
                        )
                    )
                    4 -> startActivity(
                        Intent(
                            this,
                            ProxyChainActivity::class.java
                        )
                    )
                }
            }
            .show()
    }

    private fun openSubscriptionManager(
        sourceId: String? = null,
        kind: SubscriptionKind? = null
    ) {
        if (subscriptionRefreshBusy.get()) {
            toast(getString(R.string.subscription_update_busy))
            return
        }

        if (!sourceId.isNullOrBlank()) {
            val source = SubscriptionRepository(this).find(sourceId) ?: return
            if (source.kind == SubscriptionKind.NORDVPN) {
                showInlineNordVpnSubscriptionEditor(source)
            } else {
                showInlineUrlSubscriptionEditor(source)
            }
            return
        }

        when (kind) {
            SubscriptionKind.URL -> showInlineUrlSubscriptionEditor(null)
            SubscriptionKind.NORDVPN -> showInlineNordVpnSubscriptionEditor(null)
            null -> showInlineSubscriptionTypePicker()
        }
    }

    private fun showInlineSubscriptionTypePicker() {
        val options = arrayOf(
            getString(R.string.url_subscription),
            getString(R.string.nordvpn_subscription)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_subscription_type)
            .setItems(options) { _, which ->
                if (which == 0) showInlineUrlSubscriptionEditor(null)
                else showInlineNordVpnSubscriptionEditor(null)
            }
            .show()
    }

    private fun showInlineUrlSubscriptionEditor(source: SubscriptionSource?) {
        val view = LegacyLayoutBridge.dialog_subscription(this)
        val nameInput = view.findViewById<EditText>(R.id.subscriptionNameInput)
        val urlInput = view.findViewById<EditText>(R.id.subscriptionUrlInput)
        val enabled = view.findViewById<CheckBox>(R.id.subscriptionEnabledCheck)

        nameInput.setText(source?.name.orEmpty())
        urlInput.setText(source?.url.orEmpty())
        enabled.isChecked = source?.enabled ?: true

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (source == null) R.string.add_subscription else R.string.edit_subscription)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val rawUrl = urlInput.text.toString().trim()
                val normalizedUrl = if (rawUrl.isBlank()) "" else {
                    runCatching { SubscriptionManager.normalizeUrl(rawUrl).toASCIIString() }
                        .getOrElse {
                            toast(getString(R.string.invalid_subscription))
                            return@setOnClickListener
                        }
                }
                if (name.isBlank()) {
                    toast(getString(R.string.invalid_subscription))
                    return@setOnClickListener
                }

                val saved = if (source == null) {
                    SubscriptionSource(
                        name = name,
                        url = normalizedUrl,
                        enabled = enabled.isChecked
                    )
                } else {
                    source.copy(
                        name = name,
                        url = normalizedUrl,
                        enabled = enabled.isChecked
                    )
                }

                if (source != null && source.name != name) {
                    repository.renameSubscription(source.id, name)
                }
                SubscriptionRepository(this).save(saved)
                dialog.dismiss()
                subscriptionTabsSignature = ""
                renderSubscriptionTabs(force = true)
                refresh()
                notifyComposeChanged()

                if (saved.url.isNotBlank() && (source == null || source.url != saved.url)) {
                    refreshSubscriptionsFromMain(setOf(saved.id))
                }
            }
        }
        dialog.show()
    }

    private fun showInlineNordVpnSubscriptionEditor(source: SubscriptionSource?) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.subscription_name)
            setText(source?.name ?: getString(R.string.nordvpn_subscription))
        }
        val tokenInput = EditText(this).apply {
            hint = if (source == null) {
                getString(R.string.nordvpn_token)
            } else {
                getString(R.string.nordvpn_token_keep)
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val enabled = CheckBox(this).apply {
            text = getString(R.string.subscription_enabled)
            isChecked = source?.enabled ?: true
        }
        container.addView(nameInput)
        container.addView(tokenInput)
        container.addView(enabled)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.nordvpn_subscription)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val token = tokenInput.text.toString().trim()
                if (name.isBlank() || (source == null && token.isBlank())) {
                    toast(getString(R.string.invalid_nordvpn_token))
                    return@setOnClickListener
                }

                val id = source?.id ?: UUID.randomUUID().toString()
                val secretAlias = source?.secretAlias
                    ?.takeIf { it.isNotBlank() }
                    ?: "nordvpn_$id"

                if (token.isNotBlank()) {
                    runCatching { SecretStore.put(this, secretAlias, token) }
                        .onFailure {
                            toast(getString(R.string.secure_store_failed))
                            return@setOnClickListener
                        }
                }

                val saved = SubscriptionSource(
                    id = id,
                    name = name,
                    url = "nordvpn://all-countries",
                    enabled = enabled.isChecked,
                    lastSuccessAt = source?.lastSuccessAt ?: 0L,
                    lastError = source?.lastError.orEmpty(),
                    kind = SubscriptionKind.NORDVPN,
                    secretAlias = secretAlias
                )

                if (source != null && source.name != name) {
                    repository.renameSubscription(source.id, name)
                }
                SubscriptionRepository(this).save(saved)
                dialog.dismiss()
                subscriptionTabsSignature = ""
                renderSubscriptionTabs(force = true)
                refresh()
                notifyComposeChanged()

                if (source == null || token.isNotBlank()) {
                    refreshSubscriptionsFromMain(setOf(saved.id))
                }
            }
        }
        dialog.show()
    }

    private fun renderQuickTools() {
        quickToolsPanel.visibility =
            if (quickToolsExpanded) {
                View.VISIBLE
            } else {
                View.GONE
            }
        quickToolsToggle.setImageResource(
            R.drawable.ic_expand_more_rounded
        )
        quickToolsToggle.rotation =
            if (quickToolsExpanded) 180f else 0f
        quickToolsToggle.contentDescription =
            getString(R.string.toggle_connection_tools)
    }

    private fun refreshSubscriptionsFromMain(
        sourceIds: Collection<String>? = null
    ) {
        if (!subscriptionRefreshBusy.compareAndSet(false, true)) {
            toast(getString(R.string.subscription_update_busy))
            return
        }

        val requested = sourceIds
            ?.asSequence()
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.toSet()

        Diagnostics.info(
            if (requested.isNullOrEmpty()) {
                "Subscription refresh requested for all enabled sources"
            } else {
                "Subscription refresh requested for ${requested.size} selected source(s)"
            }
        )

        notifyComposeChanged()
        refreshSubscriptionsButton.isEnabled = false
        refreshSubscriptionsButton.text = "…"
        refreshSubscriptionsButton.contentDescription =
            getString(R.string.subscription_updating)

        val progressCallback:
            (SubscriptionRefreshCoordinator.Progress) -> Unit = { progress ->
                runOnUiThreadIfAlive {
                    if (subscriptionRefreshBusy.get()) {
                        refreshSubscriptionsButton.text =
                            if (progress.total > 0) {
                                "${progress.completed + 1}/${progress.total}"
                            } else {
                                "…"
                            }
                        refreshSubscriptionsButton.contentDescription =
                            getString(
                                R.string.subscription_progress_format,
                                (progress.completed + 1).toString(),
                                progress.total.toString(),
                                progress.sourceName
                            )
                        notifyComposeChanged()
                    }
                }
            }

        val completionCallback:
            (SubscriptionRefreshCoordinator.Summary) -> Unit = { summary ->
                runOnUiThreadIfAlive {
                    subscriptionRefreshBusy.set(false)
                    refreshSubscriptionsButton.isEnabled = true
                    refreshSubscriptionsButton.text = "↻"
                    refreshSubscriptionsButton.contentDescription =
                        getString(R.string.update_subscriptions)
                    subscriptionTabsSignature = ""
                    renderSubscriptionTabs(force = true)
                    refresh()

                    if (!summary.cancelled) {
                        toast(
                            getString(
                                R.string.subscription_update_done_detailed,
                                summary.updated,
                                summary.profiles,
                                summary.partial,
                                summary.failed
                            )
                        )
                    }
                }
            }

        val accepted =
            if (requested.isNullOrEmpty()) {
                subscriptionUpdater.refreshEnabled(
                    progress = progressCallback,
                    callback = completionCallback
                )
            } else {
                subscriptionUpdater.refreshSources(
                    sourceIds = requested,
                    progress = progressCallback,
                    callback = completionCallback
                )
            }

        if (!accepted) {
            subscriptionRefreshBusy.set(false)
            notifyComposeChanged()
            refreshSubscriptionsButton.isEnabled = true
            refreshSubscriptionsButton.text = "↻"
            refreshSubscriptionsButton.contentDescription =
                getString(R.string.update_subscriptions)
            toast(getString(R.string.subscription_update_busy))
        }
    }

    private fun requestLivePingNow() {
        val snapshot =
            ConnectionRuntime.current()

        if (
            snapshot.state !=
            ConnectionState.CONNECTED
        ) {
            toast(
                getString(
                    R.string.connect_first
                )
            )
            return
        }

        if (livePingBusy.get()) {
            toast(
                getString(
                    R.string
                        .live_ping_in_progress
                )
            )
            return
        }

        manualLivePingRequested.set(true)
        livePingResult = null
        livePingText.setText(
            R.string
                .live_ping_measuring
        )
        Diagnostics.info(
            "UI live ping requested; session=${snapshot.sessionId}, " +
                "mode=${snapshot.mode}, profile=${snapshot.profileId.orEmpty()}"
        )
        notifyComposeChanged()
        handler.removeCallbacks(
            livePingTick
        )
        handler.post(
            livePingTick
        )
    }

    private fun selectProfile(
        profile: ConfigProfile
    ) {
        repository.select(profile.id)
        val current = ConnectionRuntime.current()
        if (
            current.profileId != profile.id &&
            current.state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)
        ) {
            val mode = current.mode ?: settingsRepository.load().mode
            toast(getString(R.string.profile_switching, profile.name))
            if (!ConnectionSwitchCoordinator.queue(
                    context = this,
                    profileId = profile.id,
                    targetMode = mode,
                    reason = "main_profile_selection"
                )
            ) {
                toast(getString(R.string.connection_start_failed))
            }
        }
        refresh()
    }

    private fun completePendingProfileSwitch(
        snapshot:
            ConnectionRuntime.Snapshot
    ) {
        val profileId =
            pendingSwitchProfileId
                ?: return

        if (
            snapshot.state !in
            setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            return
        }

        val mode =
            pendingSwitchMode
                ?: settingsRepository
                    .load()
                    .mode

        pendingSwitchProfileId =
            null
        pendingSwitchMode =
            null

        handler.postDelayed(
            {
                val profile =
                    repository.find(
                        profileId
                    )

                if (
                    profile == null ||
                    !profile.enabled ||
                    repository.selectedId() !=
                    profileId
                ) {
                    return@postDelayed
                }

                if (
                    mode ==
                    ConnectionMode.PROXY
                ) {
                    startProxy(
                        profileId
                    )
                } else {
                    requestVpn(
                        profileId
                    )
                }
            },
            PROFILE_SWITCH_DELAY_MS
        )
    }

    private fun stopActiveConnection(
        snapshot:
            ConnectionRuntime.Snapshot
    ) {
        when (
            snapshot.mode
                ?: settingsRepository
                    .load()
                    .mode
        ) {
            ConnectionMode.PROXY ->
                startService(
                    Intent(
                        this,
                        ConnectionService::
                            class.java
                    ).setAction(
                        ConnectionService
                            .ACTION_STOP
                    )
                )

            ConnectionMode.VPN ->
                startService(
                    Intent(
                        this,
                        TrivoxVpnService::
                            class.java
                    ).setAction(
                        TrivoxVpnService
                            .ACTION_STOP
                    )
                )
        }
    }

    private fun requestRealDelayNow() {
        val snapshot =
            ConnectionRuntime.current()

        if (
            snapshot.state !=
            ConnectionState.CONNECTED
        ) {
            toast(
                getString(
                    R.string.connect_first
                )
            )
            return
        }

        if (
            !realDelayBusy
                .compareAndSet(
                    false,
                    true
                )
        ) {
            toast(
                getString(
                    R.string
                        .real_delay_in_progress
                )
            )
            return
        }

        realDelayResult = null
        realDelayText.setText(
            R.string
                .real_delay_measuring
        )
        Diagnostics.info(
            "UI real delay requested; session=${snapshot.sessionId}, " +
                "mode=${snapshot.mode}, profile=${snapshot.profileId.orEmpty()}"
        )
        notifyComposeChanged()
        val sessionId =
            snapshot.sessionId

        latencyWorker.execute {
            val settings =
                settingsRepository
                    .load()
            val result =
                runCatching {
                    TunnelHealthVerifier.measure(
                        settings = settings,
                        mode = snapshot.mode,
                        attempts = settings.testAttempts.coerceIn(2, 3),
                        budgetMs = REAL_DELAY_BUDGET_MS
                    )
                }.getOrElse {
                    Diagnostics
                        .recordThrowable(
                            "Real delay",
                            it
                        )
                    failedPingResult(
                        PingMethod.XRAY_HTTP,
                        it
                    )
                }

            val latest =
                ConnectionRuntime.current()

            if (
                latest.state ==
                ConnectionState.CONNECTED &&
                latest.sessionId ==
                sessionId
            ) {
                realDelayResult =
                    result
            }

            realDelayBusy.set(false)
            Diagnostics.info(
                "UI real delay completed; success=${result.success}, " +
                    "latency=${result.latencyMs}, error=${result.errorCategory.orEmpty()}"
            )

            runOnUiThread(
                ::refresh
            )
        }
    }

    private fun renderSubscriptionTabs(
        force: Boolean = false
    ) {
        val sources =
            SubscriptionRepository(this)
                .all()
                .sortedBy {
                    it.name.lowercase(
                        Locale.ROOT
                    )
                }
        val profiles =
            repository.all()
        val availableIds =
            sources
                .mapTo(
                    mutableSetOf()
                ) {
                    it.id
                }
                .apply {
                    add(CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID)
                }

        val selectedSourceId =
            activeSubscriptionId

        if (
            selectedSourceId != null &&
            selectedSourceId !in
            availableIds
        ) {
            activeSubscriptionId = null
            storeActiveSubscription()
        }

        val counts =
            profiles
                .groupingBy {
                    it.subscriptionId
                }
                .eachCount()
        val signature =
            buildString {
                append(
                    activeSubscriptionId
                        .orEmpty()
                )
                append('|')
                append(profiles.size)
                sources.forEach {
                    append('|')
                    append(it.id)
                    append(':')
                    append(it.name)
                    append(':')
                    append(
                        counts[it.id] ?: 0
                    )
                }
            }

        if (
            !force &&
            signature ==
            subscriptionTabsSignature
        ) {
            return
        }

        subscriptionTabsSignature =
            signature
        subscriptionTabs.removeAllViews()

        addSubscriptionTab(
            id = null,
            label =
                getString(
                    R.string
                        .subscription_all_count,
                    profiles.size
                )
        )

        if ((counts[CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID] ?: 0) > 0) {
            addSubscriptionTab(
                id = CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID,
                label = "@${settingsRepository.load().communityChannelUsername} " +
                    "(${counts[CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID] ?: 0})"
            )
        }

        sources.forEach { source ->
            addSubscriptionTab(
                id = source.id,
                label =
                    getString(
                        R.string
                            .subscription_tab_count,
                        source.name,
                        counts[source.id] ?: 0
                    )
            )
        }
    }

    private fun addSubscriptionTab(
        id: String?,
        label: String
    ) {
        val selected =
            activeSubscriptionId ==
                id
        val tab =
            TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                minHeight = dp(38)
                setPadding(
                    dp(14),
                    0,
                    dp(14),
                    0
                )
                textSize = 11f
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (selected) {
                            R.color.blue
                        } else {
                            R.color
                                .text_primary
                        }
                    )
                )
                setBackgroundResource(
                    if (selected) {
                        R.drawable
                            .subscription_tab_selected
                    } else {
                        R.drawable
                            .subscription_tab_normal
                    }
                )
                isClickable = true
                isFocusable = true
                setOnLongClickListener {
                    id?.let(
                        ::showSubscriptionActions
                    )
                    id != null
                }
                setOnClickListener {
                    if (
                        activeSubscriptionId !=
                        id
                    ) {
                        activeSubscriptionId = id
                        storeActiveSubscription()
                        subscriptionTabsSignature =
                            ""
                        renderSubscriptionTabs(
                            force = true
                        )
                        refresh()
                    }
                }
            }

        subscriptionTabs.addView(
            tab,
            LinearLayout.LayoutParams(
                LinearLayout
                    .LayoutParams
                    .WRAP_CONTENT,
                dp(38)
            ).apply {
                marginEnd = dp(6)
            }
        )
    }

    private fun storeActiveSubscription() {
        getSharedPreferences(
            MAIN_UI_PREFS,
            MODE_PRIVATE
        ).edit()
            .putString(
                KEY_ACTIVE_SUBSCRIPTION,
                activeSubscriptionId
            )
            .apply()
    }


    private fun showSubscriptionActions(
        subscriptionId: String
    ) {
        val source = SubscriptionRepository(this)
            .find(subscriptionId)
            ?: return
        val profiles = repository.all()
            .filter { it.subscriptionId == subscriptionId }
        val labels = arrayOf(
            getString(R.string.subscription_open_settings),
            getString(R.string.v15_subscription_update),
            getString(
                if (source.enabled) {
                    R.string.v15_subscription_toggle_disable
                } else {
                    R.string.v15_subscription_toggle_enable
                }
            ),
            getString(R.string.subscription_export_links),
            getString(R.string.subscription_delete_all_profiles),
            getString(R.string.subscription_delete_without_tcp),
            getString(R.string.subscription_delete_without_real),
            getString(R.string.subscription_delete_without_both),
            getString(R.string.v15_subscription_delete)
        )

        AlertDialog.Builder(this)
            .setTitle(source.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> openSubscriptionManager(sourceId = subscriptionId)
                    1 -> refreshSubscriptionsFromMain(setOf(subscriptionId))
                    2 -> {
                        SubscriptionRepository(this).update(subscriptionId) {
                            it.enabled = !it.enabled
                        }
                        subscriptionTabsSignature = ""
                        renderSubscriptionTabs(force = true)
                        refresh()
                    }
                    3 -> exportSubscriptionLinks(source, profiles)
                    4 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) { true }
                    5 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.tcpTestStatus != TestStatus.ALIVE ||
                            it.tcpLatencyMs == null
                    }
                    6 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.realTestStatus != TestStatus.ALIVE ||
                            it.realLatencyMs == null
                    }
                    7 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        (
                            it.tcpTestStatus != TestStatus.ALIVE ||
                                it.tcpLatencyMs == null
                            ) &&
                            (
                                it.realTestStatus != TestStatus.ALIVE ||
                                    it.realLatencyMs == null
                                )
                    }
                    8 -> confirmDeleteSubscriptionFromMain(source, profiles.size)
                }
            }
            .show()
    }


    private fun exportSubscriptionLinks(
        source: SubscriptionSource,
        profiles: List<ConfigProfile>
    ) {
        val links = profiles
            .asSequence()
            .map { it.raw.trim() }
            .filter(String::isNotBlank)
            .filter { value ->
                runCatching { URI(value).scheme }
                    .getOrNull()
                    ?.lowercase(Locale.ROOT) in SHARE_SCHEMES
            }
            .distinct()
            .toList()

        if (links.isEmpty()) {
            toast(getString(R.string.subscription_export_none))
            return
        }

        val payload = links.joinToString("\n")
        val title = getString(
            R.string.subscription_export_title,
            source.name
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, title)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, payload)
                },
                title
            )
        )
    }

    private fun confirmSubscriptionProfileCleanup(
        source: SubscriptionSource,
        profiles: List<ConfigProfile>,
        title: String,
        predicate: (ConfigProfile) -> Boolean
    ) {
        val targets = profiles.filter(predicate)
        val runtime = ConnectionRuntime.current()
        if (
            runtime.state !in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            ) &&
            targets.any { it.id == runtime.profileId }
        ) {
            toast(getString(R.string.disconnect_subscription_first))
            return
        }
        if (targets.isEmpty()) {
            toast(getString(R.string.subscription_cleanup_none))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                getString(
                    R.string.subscription_cleanup_confirm,
                    targets.size,
                    source.name
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storageWorker.execute {
                    val removed = repository.deleteMany(
                        targets.map(ConfigProfile::id)
                    )
                    runOnUiThread {
                        subscriptionTabsSignature = ""
                        renderSubscriptionTabs(force = true)
                        refresh()
                        toast(
                            getString(
                                R.string.subscription_cleanup_done,
                                removed
                            )
                        )
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteSubscriptionFromMain(
        source: SubscriptionSource,
        profileCount: Int
    ) {
        val current = ConnectionRuntime.current()
        val connectedProfile = repository.find(current.profileId)
        if (
            current.state !in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            ) &&
            connectedProfile?.subscriptionId == source.id
        ) {
            toast(getString(R.string.disconnect_subscription_first))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.v15_subscription_delete)
            .setMessage(
                getString(
                    R.string.v15_subscription_delete_confirm,
                    source.name,
                    profileCount
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storageWorker.execute {
                    repository.deleteSubscription(source.id)
                    SubscriptionRepository(this).delete(source.id)
                    if (source.secretAlias.isNotBlank()) {
                        SecretStore.delete(this, source.secretAlias)
                    }
                    runOnUiThreadIfAlive {
                        if (activeSubscriptionId == source.id) {
                            activeSubscriptionId = null
                            storeActiveSubscription()
                        }
                        subscriptionTabsSignature = ""
                        renderSubscriptionTabs(force = true)
                        refresh()
                        toast(getString(R.string.v15_subscription_deleted))
                    }
                }
            }
            .show()
    }

    private fun runMagicClipboardImport() {
        if (!magicClipboardBusy.compareAndSet(false, true)) {
            toast(getString(R.string.v15_magic_busy))
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (text.isBlank()) {
            magicClipboardBusy.set(false)
            toast(getString(R.string.v15_magic_empty))
            notifyComposeChanged()
            return
        }

        Diagnostics.info(
            "Magic clipboard import requested; target=" +
                (activeSubscriptionId ?: "local")
        )
        notifyComposeChanged()
        performSmartImport(
            text = text,
            requestedTargetId = activeSubscriptionId,
            magic = true
        )
    }

    private fun performSmartImport(
        text: String,
        requestedTargetId: String?,
        magic: Boolean
    ) {
        worker.execute {
            runCatching {
                SmartClipboardImporter.import(
                    context = this,
                    rawText = text,
                    targetSubscriptionId = requestedTargetId
                )
            }.onSuccess { result ->
                runOnUiThreadIfAlive {
                    if (magic) {
                        magicClipboardBusy.set(false)
                    }
                    result.sourceId
                        ?.takeIf {
                            result.kind != SmartClipboardImporter.Kind.CONFIGS ||
                                requestedTargetId == null
                        }
                        ?.let {
                            activeSubscriptionId = it
                            storeActiveSubscription()
                        }

                    subscriptionTabsSignature = ""
                    renderSubscriptionTabs(force = true)
                    refresh()

                    val message = when (result.kind) {
                        SmartClipboardImporter.Kind.URL_SUBSCRIPTION ->
                            getString(
                                R.string.v15_magic_subscription_done,
                                result.sourceName,
                                result.received
                            )

                        SmartClipboardImporter.Kind.CONFIGS ->
                            getString(
                                R.string.v15_magic_configs_done,
                                result.added,
                                result.updated,
                                result.moved,
                                result.skipped
                            )

                        SmartClipboardImporter.Kind.NORDVPN -> {
                            val suffix =
                                if (result.warningCount > 0) {
                                    getString(
                                        R.string.v15_magic_warning_suffix,
                                        result.warningCount
                                    )
                                } else {
                                    ""
                                }
                            getString(
                                R.string.v15_magic_nord_done,
                                result.received,
                                suffix
                            )
                        }
                    }
                    Diagnostics.info(
                        "Smart import completed; kind=${result.kind}, " +
                            "received=${result.received}, changed=${result.changed}"
                    )
                    toast(message)
                }
            }.onFailure { error ->
                runOnUiThreadIfAlive {
                    if (magic) {
                        magicClipboardBusy.set(false)
                    }
                    notifyComposeChanged()
                    Diagnostics.recordThrowable("Smart import", error)
                    toast(
                        getString(
                            R.string.import_failed,
                            error.message ?: getString(R.string.unknown_error)
                        )
                    )
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density
            ).toInt()

    private fun compactAdapter(values: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, values).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun refresh() {
        val selectedId =
            repository.selectedId()
        val current =
            ConnectionRuntime.current()
        val settings =
            settingsRepository.load()
        val all =
            repository.all()
        composeProfileStatsCache = ComposeProfileStats(
            total = all.size,
            alive = all.count {
                it.tcpTestStatus == TestStatus.ALIVE ||
                    it.realTestStatus == TestStatus.ALIVE
            },
            failed = all.count {
                it.tcpTestStatus != TestStatus.ALIVE &&
                    it.realTestStatus != TestStatus.ALIVE &&
                    (
                        it.tcpTestStatus in setOf(TestStatus.DEAD, TestStatus.ERROR) ||
                            it.realTestStatus in setOf(TestStatus.DEAD, TestStatus.ERROR)
                        )
            }
        )
        val values =
            all.asSequence()
                .filter { profile ->
                    val sourceMatches =
                        activeSubscriptionId == null ||
                            profile.subscriptionId ==
                            activeSubscriptionId
                    val searchMatches =
                        filter.isBlank() ||
                            profile.name.contains(
                                filter,
                                true
                            ) ||
                            profile.server.contains(
                                filter,
                                true
                            ) ||
                            profile.protocol.contains(
                                filter,
                                true
                            ) ||
                            profile.exitCountry.contains(
                                filter,
                                true
                            ) ||
                            profile.exitIp.contains(
                                filter,
                                true
                            )

                    sourceMatches &&
                        (
                            profile.id == current.profileId ||
                                searchMatches
                            )
                }
                .sortedWith(
                    profileComparator(
                        settings = settings,
                        connectedId =
                            current.profileId
                    )
                )
                .toList()

        adapter.submit(
            values,
            selectedId,
            current.profileId,
            settings.hideIpOnMain,
            settings.gridMode
        )
        emptyText.visibility =
            if (values.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val infoProfile =
            all.firstOrNull {
                it.id ==
                    (
                        current.profileId
                            ?: selectedId
                        )
            }

        if (current.error.isBlank()) {
            selectedText.text =
                infoProfile?.let {
                    if (settings.hideIpOnMain) {
                        it.name
                    } else {
                        "${it.name} • ${it.server}:${it.port}"
                    }
                }.orEmpty()
        }

        renderConnectedInfo(
            current,
            infoProfile,
            settings
        )
        notifyComposeChanged()
    }

    private fun renderConnectedInfo(
        snapshot: ConnectionRuntime.Snapshot,
        profile: ConfigProfile?,
        settings: AppSettings
    ) {
        val hideIp = settings.hideIpOnMain
        val connectedProfile =
            profile.takeIf {
                snapshot.state ==
                    ConnectionState.CONNECTED &&
                    it?.id == snapshot.profileId
            }

        val live =
            livePingResult

        livePingText.text =
            if (connectedProfile == null) {
                getString(
                    R.string.live_ping_off
                )
            } else if (
                livePingBusy.get() &&
                live == null
            ) {
                getString(
                    R.string
                        .live_ping_measuring
                )
            } else if (
                live?.success == true &&
                live.latencyMs != null
            ) {
                getString(
                    R.string
                        .live_ping_value_method,
                    live.latencyMs,
                    pingMethodLabel(
                        PingMethod.fromStored(
                            live.method
                        )
                    )
                )
            } else if (live != null) {
                getString(
                    R.string.live_ping_failed
                )
            } else if (!settings.livePingEnabled) {
                getString(
                    R.string.live_ping_auto_disabled
                )
            } else {
                getString(
                    R.string.live_ping_waiting
                )
            }

        livePingText.isEnabled =
            connectedProfile != null &&
                !livePingBusy.get()

        val realDelay =
            realDelayResult
        realDelayText.text =
            if (connectedProfile == null) {
                getString(
                    R.string
                        .real_delay_off
                )
            } else if (
                realDelayBusy.get() &&
                realDelay == null
            ) {
                getString(
                    R.string
                        .real_delay_measuring
                )
            } else if (
                realDelay?.success == true &&
                realDelay.latencyMs != null
            ) {
                getString(
                    R.string
                        .real_delay_value,
                    realDelay.latencyMs
                )
            } else if (
                realDelay != null
            ) {
                getString(
                    R.string
                        .real_delay_failed
                )
            } else {
                getString(
                    R.string
                        .real_delay_waiting
                )
            }
        realDelayText.isEnabled =
            connectedProfile != null &&
                !realDelayBusy.get()

        exitInfoText.text =
            connectedProfile
                ?.let { profile ->
                    profileExitLine(
                        profile,
                        hideIp
                    ).ifBlank {
                            getString(
                                R.string.exit_info_waiting
                            )
                        }
                }
                ?: getString(
                    R.string.exit_info_off
                )

        refreshExitButton.isEnabled =
            connectedProfile != null

        copySummaryButton.isEnabled =
            profile != null
    }

    /** TRIVOX_V7_IMPORT_WIREGUARD */
    private fun showImportDialog() {
        val targetId = activeSubscriptionId
        val target = SubscriptionRepository(this).find(targetId)
        val targetName = target?.name
            ?: getString(R.string.import_target_local)
        val view = LegacyLayoutBridge.dialog_import(this)
        val input = view.findViewById<EditText>(R.id.importInput)
        importDialogInput = input

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.import_title_target,
                    targetName
                )
            )
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        view.findViewById<Button>(R.id.clipboardButton)
            .setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                input.setText(
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.coerceToText(this)
                        ?: ""
                )
            }

        view.findViewById<Button>(R.id.fileButton)
            .setOnClickListener {
                pendingFileImportTargetId = targetId
                dialog.dismiss()
                filePicker.launch(
                    arrayOf(
                        "text/plain",
                        "application/json",
                        "application/octet-stream",
                        "application/x-wireguard-profile"
                    )
                )
            }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val text = input.text.toString()
                    if (text.isNotBlank()) {
                        dialog.dismiss()
                        importText(text, targetId)
                    }
                }
        }
        dialog.setOnDismissListener { importDialogInput = null }
        dialog.show()
    }

    private fun readConfigurationText(
        reader: java.io.Reader
    ): String {
        val output =
            StringBuilder()
        val buffer =
            CharArray(16 * 1024)

        while (true) {
            val count =
                reader.read(buffer)

            if (count < 0) {
                break
            }

            if (
                output.length + count >
                MAX_SHARED_INPUT_CHARS
            ) {
                throw IllegalArgumentException(
                    "Config file exceeds the 4 MiB safety limit"
                )
            }

            output.append(
                buffer,
                0,
                count
            )
        }

        return output.toString()
    }

    private fun importText(
        text: String,
        requestedTargetId: String?
    ) {
        performSmartImport(
            text = text,
            requestedTargetId = requestedTargetId,
            magic = false
        )
    }

    private fun showActions(profile: ConfigProfile) {
        val labels = arrayOf(
            getString(R.string.edit_config),
            getString(R.string.ping_now),
            getString(R.string.profile_details),
            getString(R.string.copy_link),
            getString(R.string.share_link),
            getString(R.string.copy_json),
            getString(R.string.share_json),
            getString(R.string.rename),
            getString(R.string.duplicate),
            getString(
                if (profile.favorite) R.string.not_favorite
                else R.string.favorite
            ),
            getString(
                if (profile.enabled) R.string.disabled
                else R.string.enabled
            ),
            getString(R.string.delete)
        )

        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> editProfile(profile)
                    1 -> testSingleProfile(profile)
                    2 -> showProfileDetails(profile)
                    3 -> copyProfileLink(profile)
                    4 -> shareProfileLink(profile)
                    5 -> copyProfileJson(profile)
                    6 -> shareProfileJson(profile)
                    7 -> rename(profile)
                    8 -> duplicateProfile(profile)
                    9 -> storageWorker.execute {
                        repository.update(profile.id) {
                            it.favorite = !it.favorite
                        }
                        runOnUiThread(::refresh)
                    }
                    10 -> storageWorker.execute {
                        repository.update(profile.id) {
                            it.enabled = !it.enabled
                        }
                        runOnUiThread(::refresh)
                    }
                    11 -> confirmDelete(profile)
                }
            }
            .show()
    }

    private fun editProfile(profile: ConfigProfile) {
        val target =
            if (profile.protocol == "chain") {
                ProxyChainActivity::class.java
            } else {
                ConfigEditorActivity::class.java
            }
        startActivity(
            Intent(this, target)
                .putExtra(
                    if (profile.protocol == "chain") {
                        ProxyChainActivity.EXTRA_PROFILE_ID
                    } else {
                        ConfigEditorActivity.EXTRA_PROFILE_ID
                    },
                    profile.id
                )
        )
    }

    private fun repairStaleTestingStates() {
        storageWorker.execute {
            val staleIds = repository.all()
                .filter { profile ->
                    profile.testStatus == TestStatus.TESTING ||
                        profile.tcpTestStatus == TestStatus.TESTING ||
                        profile.realTestStatus == TestStatus.TESTING
                }
                .map(ConfigProfile::id)

            if (staleIds.isEmpty()) return@execute

            repository.updateMany(staleIds) { profile ->
                if (profile.testStatus == TestStatus.TESTING) {
                    profile.testStatus = TestStatus.UNTESTED
                }
                if (profile.tcpTestStatus == TestStatus.TESTING) {
                    profile.tcpTestStatus = TestStatus.UNTESTED
                }
                if (profile.realTestStatus == TestStatus.TESTING) {
                    profile.realTestStatus = TestStatus.UNTESTED
                }
            }
            runOnUiThreadIfAlive(::refresh)
        }
    }

    private fun testSingleProfile(
        profile: ConfigProfile,
        method: PingMethod = PingMethod.TCP_CONNECT
    ) {
        if (
            method == PingMethod.XRAY_HTTP &&
            ConnectionRuntime.current().state !in
                setOf(
                    ConnectionState.DISCONNECTED,
                    ConnectionState.ERROR
                )
        ) {
            toast(getString(R.string.xray_ping_requires_disconnect))
            return
        }

        Diagnostics.info(
            "Single profile test requested; method=${method.name}, " +
                "profile=${profile.id}"
        )
        cancelPingTasks()
        val generation =
            pingGeneration.incrementAndGet()

        storageWorker.execute prepareSingle@ {
            repository.update(profile.id) { current ->
                current.testStatus = TestStatus.TESTING
                when (method) {
                    PingMethod.TCP_CONNECT ->
                        current.tcpTestStatus = TestStatus.TESTING
                    PingMethod.XRAY_HTTP ->
                        current.realTestStatus = TestStatus.TESTING
                }
            }
            runOnUiThreadIfAlive(::refresh)

            if (
                activityDestroyed.get() ||
                pingGeneration.get() != generation
            ) {
                return@prepareSingle
            }

            val settings =
                settingsRepository.load().copy(
                    pingMethod = method
                )

            val future =
                try {
                    latencyWorker.submit singleNetwork@ {
                        val result =
                            runCatching {
                                pingManager.measureSingle(
                                    profile = profile,
                                    settings = settings,
                                    workDir = cacheDir
                                )
                            }.getOrElse { error ->
                                Diagnostics.recordThrowable(
                                    "Single ping test",
                                    error
                                )
                                failedPingResult(
                                    settings.pingMethod,
                                    error
                                )
                            }

                        if (
                            Thread.currentThread().isInterrupted ||
                            pingGeneration.get() != generation
                        ) {
                            return@singleNetwork
                        }

                        storageWorker.execute persistSingle@ {
                            if (
                                pingGeneration.get() != generation ||
                                activityDestroyed.get()
                            ) {
                                return@persistSingle
                            }

                            repository.update(profile.id) { current ->
                                applyPingResultToProfile(
                                    current,
                                    result
                                )
                            }
                            Diagnostics.info(
                                "Single profile test completed; " +
                                    "method=${method.name}, " +
                                    "profile=${profile.id}, " +
                                    "success=${result.success}, " +
                                    "latency=${result.latencyMs}, " +
                                    "error=${result.errorCategory.orEmpty()}"
                            )
                            runOnUiThreadIfAlive(::refresh)
                        }
                    }
                } catch (
                    _: java.util.concurrent.RejectedExecutionException
                ) {
                    return@prepareSingle
                }

            trackPingTasks(listOf(future))
        }
    }

    private fun testProfiles(
        method: PingMethod
    ) {
        val runtime =
            ConnectionRuntime.current()

        if (
            method == PingMethod.XRAY_HTTP &&
            runtime.state !in
            setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            toast(
                getString(
                    R.string
                        .xray_ping_requires_disconnect
                )
            )
            return
        }

        Diagnostics.info(
            "Batch profile test requested; method=${method.name}, " +
                "subscription=${activeSubscriptionId.orEmpty()}"
        )
        cancelPingTasks()
        val generation =
            pingGeneration.incrementAndGet()
        composeBatchMethod = method
        composeBatchCompleted.set(0)
        composeBatchTotal.set(0)
        composeBatchRunning.set(true)
        setBatchControlsEnabled(false)
        notifyComposeChanged()

        storageWorker.execute {
            val subscriptionId = activeSubscriptionId
            val profiles =
                repository.all()
                    .filter { profile ->
                        profile.enabled &&
                            (
                                subscriptionId == null ||
                                    profile.subscriptionId == subscriptionId
                                )
                    }

            if (profiles.isEmpty()) {
                runOnUiThread {
                    composeBatchRunning.set(false)
                    composeBatchMethod = null
                    composeBatchCompleted.set(0)
                    composeBatchTotal.set(0)
                    setBatchControlsEnabled(true)
                    notifyComposeChanged()
                    toast(getString(R.string.no_profiles))
                }
                return@execute
            }

            composeBatchTotal.set(profiles.size)
            Diagnostics.info(
                "Batch profile test started; method=${method.name}, " +
                    "generation=$generation, total=${profiles.size}"
            )
            notifyComposeChanged()

            repository.updateMany(
                profiles.map { it.id }
            ) { current ->
                current.testStatus = TestStatus.TESTING
                when (method) {
                    PingMethod.TCP_CONNECT -> current.tcpTestStatus = TestStatus.TESTING
                    PingMethod.XRAY_HTTP -> current.realTestStatus = TestStatus.TESTING
                }
            }

            runOnUiThread {
                refresh()
                val total = profiles.size
                if (method == PingMethod.XRAY_HTTP) {
                    realDelayAllButton.text = "0/$total"
                } else {
                    tcpPingButton.text = "0/$total"
                }
            }

            val settings =
                settingsRepository.load()
            settings.pingMethod = method
            val totalProfiles = profiles.size
            val remaining =
                AtomicInteger(totalProfiles)
            val completed =
                AtomicInteger(0)
            val tasks =
                pingManager.batch(
                    profiles = profiles,
                    settings = settings,
                    workDir = cacheDir
                ) { profile, result ->
                    if (
                        pingGeneration.get() ==
                        generation
                    ) {
                        pingResultBuffer[
                            profile.id
                        ] = result
                        schedulePingFlush(
                            generation
                        )
                    }

                    val completedCount =
                        completed.incrementAndGet()
                    composeBatchCompleted.set(completedCount)

                    if (
                        completedCount == totalProfiles ||
                        completedCount == 1 ||
                        completedCount %
                            maxOf(
                                1,
                                totalProfiles / 20
                            ) == 0
                    ) {
                        runOnUiThreadIfAlive {
                            if (
                                pingGeneration.get() ==
                                generation
                            ) {
                                val label =
                                    "$completedCount/$totalProfiles"
                                if (
                                    method ==
                                    PingMethod.XRAY_HTTP
                                ) {
                                    realDelayAllButton.text =
                                        label
                                } else {
                                    tcpPingButton.text =
                                        label
                                }
                                notifyComposeChanged()
                            }
                        }
                    }

                    if (
                        remaining.decrementAndGet() ==
                        0
                    ) {
                        schedulePingFlush(
                            generation,
                            immediate = true
                        )
                        Diagnostics.info(
                            "Batch profile test completed; method=${method.name}, " +
                                "generation=$generation, total=$totalProfiles"
                        )
                        runOnUiThread {
                            composeBatchRunning.set(false)
                            setBatchControlsEnabled(true)
                            notifyComposeChanged()
                        }
                    }
                }

            trackPingTasks(tasks)
        }
    }

    private fun setBatchControlsEnabled(
        enabled: Boolean
    ) {
        tcpPingButton.isEnabled = enabled
        realDelayAllButton.isEnabled = enabled

        if (enabled) {
            tcpPingButton.setText(
                R.string.tcp_ping_icon
            )
            realDelayAllButton.setText(
                R.string.real_delay_all_icon
            )
        }
    }

    private fun schedulePingFlush(
        generation: Long,
        immediate: Boolean = false
    ) {
        if (immediate) {
            /*
             * A delayed flush may already own the scheduling flag. The old
             * implementation discarded this final request, so the last batch
             * results could stay buffered after controls were re-enabled.
             */
            handler.post {
                drainPingResults(
                    generation
                )
            }
            return
        }

        if (
            !pingFlushScheduled.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        handler.postDelayed(
            {
                pingFlushScheduled.set(false)
                drainPingResults(
                    generation
                )
            },
            PING_RESULT_FLUSH_MS
        )
    }

    private fun drainPingResults(
        generation: Long
    ) {
        if (
            pingGeneration.get() !=
            generation
        ) {
            pingResultBuffer.clear()
            return
        }

        val results =
            HashMap(pingResultBuffer)

        results.keys.forEach(
            pingResultBuffer::remove
        )

        if (results.isEmpty()) {
            return
        }

        storageWorker.execute {
            if (
                pingGeneration.get() !=
                generation
            ) {
                return@execute
            }

            repository.updateMany(
                results.keys
            ) { profile ->
                results[profile.id]
                    ?.let {
                        applyPingResultToProfile(
                            profile,
                            it
                        )
                    }
            }

            runOnUiThread(::refresh)

            if (
                pingResultBuffer.isNotEmpty()
            ) {
                schedulePingFlush(
                    generation
                )
            }
        }
    }

    private fun selectFastestProfile() {
        val fastest =
            repository.all()
                .asSequence()
                .filter { it.enabled }
                .filter { dualLatencyTier(it) < 3 }
                .minWithOrNull(
                    compareBy<ConfigProfile> {
                        dualLatencyTier(it)
                    }.thenBy {
                        dualLatencyScore(it)
                    }.thenBy {
                        it.name.lowercase(Locale.ROOT)
                    }
                )

        if (fastest == null) {
            toast(
                getString(
                    R.string
                        .no_ping_results
                )
            )
            return
        }

        repository.select(
            fastest.id
        )
        refresh()
        toast(
            getString(
                R.string
                    .fastest_selected,
                fastest.name,
                dualLatencyScore(fastest)
            )
        )
    }

    private fun showProfileDetails(profile: ConfigProfile) {
        val stream = runCatching {
            JSONObject(profile.outboundJson).optJSONObject("streamSettings")
        }.getOrNull()
        val network = stream?.optString("network")
            ?.ifBlank { "tcp" }
            ?: "tcp"
        val security = stream?.optString("security")
            ?.ifBlank { "none" }
            ?: "none"

        val summary = buildString {
            appendLine(profile.name)
            appendLine("${profile.protocol.uppercase()} • $network • $security")
            appendLine("${profile.server}:${profile.port}")
            profile.latencyMs?.let {
                    latency ->
                appendLine(
                    getString(
                        R.string
                            .latency_method_format,
                        pingMethodLabel(
                            PingMethod
                                .fromStored(
                                    profile
                                        .latencyMethod
                                )
                        ),
                        latency
                    )
                )
                profile
                    .latencyJitterMs
                    ?.let {
                        jitter ->
                        appendLine(
                            getString(
                                R.string
                                    .ping_jitter_value,
                                jitter
                            )
                        )
                    }
                appendLine(
                    getString(
                        R.string
                            .ping_success_value,
                        (
                            profile
                                .latencySuccessRatio *
                                100.0
                            ).toInt()
                    )
                )
            }
            profileExitLine(profile).takeIf(String::isNotBlank)?.let {
                appendLine(it)
            }
            profile.exitIsp.takeIf(String::isNotBlank)?.let {
                appendLine(it)
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle(R.string.profile_details)
            .setMessage(summary)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.copy) { _, _ ->
                copyToClipboard(profile.name, summary)
            }
            .show()
    }

    private fun refreshExitInfo(
        force: Boolean,
        showError: Boolean
    ) {
        val snapshot = ConnectionRuntime.current()
        if (snapshot.state != ConnectionState.CONNECTED) {
            if (showError) toast(getString(R.string.connect_first))
            return
        }

        val profile = repository.find(snapshot.profileId) ?: return
        val fresh = profile.exitIp.isNotBlank() &&
            System.currentTimeMillis() - profile.lastExitCheckAt < EXIT_CACHE_MS

        if (!force && fresh) {
            refresh()
            return
        }

        refreshExitButton.isEnabled = false
        exitInfoText.setText(R.string.exit_info_loading)

        worker.execute {
            runCatching {
                ConnectionInfoManager().fetch(settingsRepository.load())
            }.onSuccess { info ->
                val latest = ConfigRepository(this).find(profile.id)
                    ?: return@onSuccess
                latest.exitIp = info.ip
                latest.exitCountry = info.country
                latest.exitCountryCode = info.countryCode
                latest.exitFlag = info.flagEmoji
                latest.exitIsp = info.isp
                latest.lastExitCheckAt = System.currentTimeMillis()
                ConfigRepository(this).save(latest)
                runOnUiThread(::refresh)
            }.onFailure { error ->
                runOnUiThread {
                    refreshExitButton.isEnabled = true
                    exitInfoText.setText(R.string.exit_info_failed)
                    if (showError) {
                        toast(
                            getString(
                                R.string.exit_lookup_failed,
                                error.message ?: getString(R.string.unknown_error)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun copyConnectionSummary() {
        val snapshot = ConnectionRuntime.current()
        val profile = repository.find(
            snapshot.profileId ?: repository.selectedId()
        ) ?: run {
            toast(getString(R.string.select_profile))
            return
        }

        val summary = buildString {
            appendLine("Trivox")
            appendLine(profile.name)
            appendLine("${profile.protocol.uppercase()} • ${profile.server}:${profile.port}")
            profile.latencyMs?.let {
                    latency ->
                appendLine(
                    getString(
                        R.string
                            .latency_method_format,
                        pingMethodLabel(
                            PingMethod
                                .fromStored(
                                    profile
                                        .latencyMethod
                                )
                        ),
                        latency
                    )
                )
                profile
                    .latencyJitterMs
                    ?.let {
                        jitter ->
                        appendLine(
                            getString(
                                R.string
                                    .ping_jitter_value,
                                jitter
                            )
                        )
                    }
                appendLine(
                    getString(
                        R.string
                            .ping_success_value,
                        (
                            profile
                                .latencySuccessRatio *
                                100.0
                            ).toInt()
                    )
                )
            }
            profileExitLine(profile).takeIf(String::isNotBlank)?.let {
                appendLine(it)
            }
            profile.exitIsp.takeIf(String::isNotBlank)?.let {
                appendLine(it)
            }
        }.trim()

        copyToClipboard(getString(R.string.connection_summary), summary)
    }

    private fun profileExitLine(
        profile: ConfigProfile,
        hideIp: Boolean = false
    ): String = buildString {
        profile.exitFlag.takeIf(String::isNotBlank)?.let {
            append(it)
            append(' ')
        }
        profile.exitCountry.takeIf(String::isNotBlank)?.let { append(it) }
        if (
            !hideIp &&
            profile.exitIp.isNotBlank()
        ) {
            if (isNotBlank()) append(" • ")
            append(profile.exitIp)
        }
    }

    private fun copyProfileLink(profile: ConfigProfile) {
        val link = shareLink(profile)
        if (link == null) {
            toast(getString(R.string.link_unavailable))
            return
        }
        copyToClipboard(getString(R.string.config_link), link)
    }

    private fun shareProfileLink(profile: ConfigProfile) {
        val link = shareLink(profile)
        if (link == null) {
            toast(getString(R.string.link_unavailable))
            return
        }
        shareText(profile.name, link)
    }

    private fun copyProfileJson(profile: ConfigProfile) {
        worker.execute {
            runCatching {
                fullJson(profile)
            }.onSuccess { value ->
                runOnUiThread {
                    copyToClipboard(
                        getString(R.string.full_json),
                        value
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    toast(
                        getString(
                            R.string.json_generation_failed,
                            error.message
                                ?: getString(R.string.unknown_error)
                        )
                    )
                }
            }
        }
    }

    private fun shareProfileJson(profile: ConfigProfile) {
        worker.execute {
            runCatching {
                fullJson(profile)
            }.onSuccess { value ->
                runOnUiThread {
                    shareText(profile.name, value)
                }
            }.onFailure { error ->
                runOnUiThread {
                    toast(
                        getString(
                            R.string.json_generation_failed,
                            error.message
                                ?: getString(R.string.unknown_error)
                        )
                    )
                }
            }
        }
    }

    private fun shareLink(profile: ConfigProfile): String? {
        val raw = profile.raw.trim()
        val scheme = raw.substringBefore("://", "").lowercase()
        return raw.takeIf {
            raw.isNotBlank() &&
                !raw.startsWith("{") &&
                scheme in SHARE_SCHEMES
        }
    }

    private fun fullJson(profile: ConfigProfile): String {
        val raw = profile.raw.trim()
        val rawRoot =
            raw.takeIf {
                it.startsWith("{")
            }?.let {
                runCatching(::JSONObject)
                    .getOrNull()
            }
        val complete =
            rawRoot != null &&
                rawRoot.has("inbounds") &&
                rawRoot.has("outbounds") &&
                rawRoot.has("dns") &&
                rawRoot.has("routing")

        return if (complete) {
            checkNotNull(rawRoot).toString(2)
        } else {
            XrayConfigBuilder.build(
                profile = profile,
                settings = settingsRepository.load(),
                mode = ConnectionMode.PROXY
            )
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(getString(R.string.copied))
    }

    private fun shareText(name: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .putExtra(Intent.EXTRA_TEXT, text)

        startActivity(
            Intent.createChooser(
                intent,
                getString(R.string.share_config_title, name)
            )
        )
    }

    private fun copyLocalProxyEndpoint() {
        val settings = settingsRepository.load()
        val runtime = ConnectionRuntime.current()
        val currentProfile = repository.find(runtime.profileId)

        if (
            runtime.state == ConnectionState.CONNECTED &&
            runtime.mode == ConnectionMode.VPN &&
            !settings.localProxyInVpn &&
            !currentProfile?.protocol.equals("wireguard", ignoreCase = true)
        ) {
            toast(getString(R.string.v15_proxy_disabled_vpn))
            return
        }

        val port = settings.socksPort
        val value = buildString {
            appendLine("socks5://127.0.0.1:$port")
            append("http://127.0.0.1:$port")
        }
        copyToClipboard(getString(R.string.local_mixed_proxy), value)

        if (
            runtime.state !in setOf(
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING
            )
        ) {
            toast(getString(R.string.v15_proxy_copied_offline))
        }
    }

    private fun exportCompleteBackup() {
        storageWorker.execute {
            val profiles = JSONArray().apply {
                repository.all().forEach {
                    put(it.toJson())
                }
            }
            val subscriptions =
                JSONArray().apply {
                    SubscriptionRepository(
                        this@MainActivity
                    ).all().forEach {
                        put(it.toJson())
                    }
                }
            val backup = JSONObject()
                .put("format", "trivox-backup-v2")
                .put("createdAt", System.currentTimeMillis())
                .put("settings", settingsRepository.load().toJson())
                .put("profiles", profiles)
                .put("subscriptions", subscriptions)
                .toString(2)

            runOnUiThread {
                shareText(
                    getString(R.string.export_backup),
                    backup
                )
            }
        }
    }

    private fun confirmClearDeadProfiles() {
        val dead = repository.all().filter { profile ->
            val tcpFailed = profile.tcpTestStatus in setOf(TestStatus.DEAD, TestStatus.ERROR)
            val realFailed = profile.realTestStatus in setOf(TestStatus.DEAD, TestStatus.ERROR)
            val anyAlive = profile.tcpTestStatus == TestStatus.ALIVE ||
                profile.realTestStatus == TestStatus.ALIVE
            !anyAlive && tcpFailed && realFailed
        }
        if (dead.isEmpty()) {
            toast(getString(R.string.no_dead_profiles))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_dead_profiles)
            .setMessage(getString(R.string.clear_dead_confirm, dead.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storageWorker.execute {
                    val selected = repository.selectedId()
                    repository.deleteMany(
                        dead.map { it.id }
                    )
                    if (dead.any { it.id == selected }) {
                        repository.select(null)
                    }
                    runOnUiThread {
                        subscriptionTabsSignature = ""
                        renderSubscriptionTabs(force = true)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun duplicateProfile(profile: ConfigProfile) {
        val copy =
            profile.copy(
                id = UUID.randomUUID().toString(),
                name = getString(
                    R.string.copy_suffix,
                    profile.name
                ),
                subscriptionId = null,
                exitIp = "",
                exitCountry = "",
                exitCountryCode = "",
                exitFlag = "",
                exitIsp = "",
                lastExitCheckAt = 0
            )

        storageWorker.execute {
            repository.save(copy)
            runOnUiThread {
                subscriptionTabsSignature = ""
                renderSubscriptionTabs(force = true)
                refresh()
            }
        }
    }

    private fun confirmDelete(profile: ConfigProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_config_title)
            .setMessage(getString(R.string.delete_config_message, profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storageWorker.execute {
                    repository.delete(profile.id)
                    if (repository.selectedId() == profile.id) {
                        repository.select(null)
                    }
                    runOnUiThread {
                        subscriptionTabsSignature = ""
                        renderSubscriptionTabs(force = true)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun rename(profile: ConfigProfile) {
        val input = EditText(this).apply {
            setText(profile.name)
            selectAll()
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.new_name)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                input.text.toString().trim()
                    .takeIf(String::isNotEmpty)
                    ?.let { newName ->
                        storageWorker.execute {
                            repository.update(profile.id) {
                                it.name = newName
                            }
                            runOnUiThread(::refresh)
                        }
                    }
            }
            .show()
    }

    private fun showPauseOptions() {
        val labels = arrayOf(
            getString(R.string.pause_15_minutes),
            getString(R.string.pause_30_minutes),
            getString(R.string.pause_custom)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.pause_connection)
            .setItems(labels) { _, position ->
                when (position) {
                    0 -> pauseConnection(15)
                    1 -> pauseConnection(30)
                    2 -> showCustomPauseDialog()
                }
            }
            .show()
    }

    private fun showCustomPauseDialog() {
        val input = EditText(this).apply {
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(
                R.string.pause_minutes_hint
            )
            setText("60")
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pause_custom)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pause_connection) {
                    _,
                    _ ->
                val minutes =
                    input.text.toString()
                        .toIntOrNull()
                        ?.coerceIn(1, 1440)

                if (minutes == null) {
                    toast(
                        getString(
                            R.string.invalid_pause_minutes
                        )
                    )
                } else {
                    pauseConnection(minutes)
                }
            }
            .show()
    }

    private fun pauseConnection(
        minutes: Int
    ) {
        val snapshot =
            ConnectionRuntime.current()
        val profileId =
            snapshot.profileId
                ?: return
        val mode =
            snapshot.mode
                ?: settingsRepository.load().mode

        if (
            snapshot.state !=
            ConnectionState.CONNECTED
        ) {
            return
        }

        ConnectionPauseController.schedule(
            context = this,
            profileId = profileId,
            mode = mode,
            durationMillis =
                minutes * 60_000L
        )
        stopActiveConnection(snapshot)
        toast(
            getString(
                R.string.pause_scheduled,
                minutes
            )
        )
    }

    private fun toggleConnection() {
        if (connectionStartPending.get()) {
            cancelPendingConnection()
            return
        }
        if (!connectionActionBusy.compareAndSet(false, true)) return
        handler.postDelayed(
            { connectionActionBusy.set(false) },
            CONNECTION_ACTION_COOLDOWN_MS
        )

        val runtime = ConnectionRuntime.current()
        if (
            runtime.state !in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            pendingSwitchProfileId = null
            pendingSwitchMode = null
            renderState(
                runtime.copy(state = ConnectionState.STOPPING)
            )
            stopActiveConnection(runtime)
            return
        }

        val profile =
            repository.find(
                repository.selectedId()
            )

        if (
            profile == null ||
            !profile.enabled
        ) {
            toast(
                getString(
                    R.string.select_profile
                )
            )
            return
        }

        if (
            !coreManager.adapter
                .isAvailable()
        ) {
            toast(
                getString(
                    R.string.core_missing
                )
            )
            return
        }

        showConnectionStartPending(
            profile.name
        )

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission
                    .POST_NOTIFICATIONS
            ) !=
            android.content.pm.PackageManager
                .PERMISSION_GRANTED
        ) {
            notificationPermission.launch(
                Manifest.permission
                    .POST_NOTIFICATIONS
            )
        }

        ConnectionPauseController
            .cancel(this)

        if (
            settingsRepository.load().mode ==
            ConnectionMode.PROXY
        ) {
            startProxy(profile.id)
        } else {
            requestVpn(profile.id)
        }
    }

    private fun cancelPendingConnection() {
        pendingVpnProfile = null
        connectionStartPending.set(false)
        handler.removeCallbacks(connectionStartTimeout)
        val mode = settingsRepository.load().mode
        val snapshot = ConnectionRuntime.current()
        renderState(
            snapshot.copy(
                state = ConnectionState.STOPPING,
                mode = snapshot.mode ?: mode
            )
        )
        if (mode == ConnectionMode.PROXY) {
            startService(
                Intent(this, ConnectionService::class.java)
                    .setAction(ConnectionService.ACTION_STOP)
            )
        } else {
            startService(
                Intent(this, TrivoxVpnService::class.java)
                    .setAction(TrivoxVpnService.ACTION_STOP)
            )
        }
    }

    private fun showConnectionStartPending(
        profileName: String
    ) {
        connectionStartPending.set(true)
        handler.removeCallbacks(
            connectionStartTimeout
        )

        stateText.setText(
            R.string.state_preparing
        )
        stateText.setTextColor(
            ContextCompat.getColor(
                this,
                R.color.blue
            )
        )
        selectedText.text = profileName
        connectButton.setText(
            R.string.disconnect
        )
        connectButton.isEnabled = true
        Diagnostics.info("UI connection start requested for $profileName")
        notifyComposeChanged()

        handler.postDelayed(
            connectionStartTimeout,
            CONNECTION_START_UI_TIMEOUT_MS
        )
    }

    private fun clearConnectionStartPending() {
        if (
            connectionStartPending
                .compareAndSet(
                    true,
                    false
                )
        ) {
            handler.removeCallbacks(
                connectionStartTimeout
            )
            renderState(
                ConnectionRuntime.current()
            )
        }
    }

    private val connectionStartTimeout =
        Runnable {
            if (
                connectionStartPending
                    .compareAndSet(
                        true,
                        false
                    ) &&
                ConnectionRuntime.current()
                    .state in
                setOf(
                    ConnectionState
                        .DISCONNECTED,
                    ConnectionState.ERROR
                )
            ) {
                renderState(
                    ConnectionRuntime.current()
                )
            }
        }

    private fun startProxy(id: String) {
        startConnectionService(
            Intent(this, ConnectionService::class.java)
                .setAction(ConnectionService.ACTION_START)
                .putExtra(ConnectionService.EXTRA_PROFILE_ID, id),
            "proxy"
        )
    }

    private fun requestVpn(id: String) {
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            startVpn(id)
        } else {
            pendingVpnProfile = id
            vpnPermission.launch(prepare)
        }
    }

    private fun startVpn(id: String) {
        startConnectionService(
            Intent(this, TrivoxVpnService::class.java)
                .setAction(TrivoxVpnService.ACTION_START)
                .putExtra(TrivoxVpnService.EXTRA_PROFILE_ID, id),
            "vpn"
        )
    }

    private fun startConnectionService(
        intent: Intent,
        mode: String
    ) {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                intent
            )
        }.onFailure {
            error ->
            clearConnectionStartPending()
            Diagnostics.recordThrowable(
                "Start $mode connection service",
                error
            )
            toast(
                getString(
                    R.string.connection_start_failed
                )
            )
        }
    }

    private fun renderState(
        snapshot:
            ConnectionRuntime.Snapshot
    ) {
        if (lastUiRuntimeState != snapshot.state) {
            Diagnostics.info(
                "UI runtime state changed: ${lastUiRuntimeState?.name ?: "INITIAL"} -> " +
                    "${snapshot.state.name}; session=${snapshot.sessionId}, " +
                    "profile=${snapshot.profileId.orEmpty()}, mode=${snapshot.mode}"
            )
            lastUiRuntimeState = snapshot.state
        }
        connectionStartPending.set(false)
        handler.removeCallbacks(
            connectionStartTimeout
        )

        stateText.setText(
            when (snapshot.state) {
                ConnectionState.DISCONNECTED ->
                    R.string.state_disconnected
                ConnectionState.PREPARING ->
                    R.string.state_preparing
                ConnectionState.CONNECTING ->
                    R.string.state_connecting
                ConnectionState.CONNECTED ->
                    R.string.state_connected
                ConnectionState.RECONNECTING ->
                    R.string.state_reconnecting
                ConnectionState.STOPPING ->
                    R.string.state_stopping
                ConnectionState.ERROR ->
                    R.string.state_error
            }
        )
        stateText.setTextColor(
            ContextCompat.getColor(
                this,
                when (snapshot.state) {
                    ConnectionState.CONNECTED ->
                        R.color.green
                    ConnectionState.ERROR ->
                        R.color.red
                    ConnectionState.PREPARING,
                    ConnectionState.CONNECTING,
                    ConnectionState.RECONNECTING ->
                        R.color.blue
                    else ->
                        R.color.text_primary
                }
            )
        )

        connectButton.setText(
            if (
                snapshot.state in setOf(
                    ConnectionState.DISCONNECTED,
                    ConnectionState.ERROR
                )
            ) {
                R.string.connect
            } else {
                R.string.disconnect
            }
        )

        modeSpinner.isEnabled =
            snapshot.state in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        connectButton.isEnabled =
            snapshot.state != ConnectionState.STOPPING

        if (snapshot.error.isNotBlank()) {
            selectedText.text = snapshot.error
        }

        updateDuration(snapshot)
        handler.removeCallbacks(durationTick)
        handler.removeCallbacks(livePingTick)

        if (
            snapshot.state ==
            ConnectionState.CONNECTED
        ) {
            if (
                lastInfoProfileId !=
                snapshot.profileId
            ) {
                livePingResult = null
                realDelayResult = null
                lastInfoProfileId =
                    snapshot.profileId
                refreshExitInfo(
                    force = true,
                    showError = false
                )
            }

            handler.postDelayed(
                durationTick,
                1_000
            )
            if (
                settingsRepository.load().livePingEnabled &&
                uiResumed.get()
            ) {
                handler.postDelayed(
                    livePingTick,
                    FIRST_LIVE_PING_DELAY_MS
                )
            }
        } else {
            lastInfoProfileId = null
            livePingResult = null
            realDelayResult = null
            livePingBusy.set(false)
            realDelayBusy.set(false)
        }

        val connected =
            snapshot.state !in
            setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        pauseButton.visibility =
            if (
                snapshot.state ==
                ConnectionState.CONNECTED
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        connectButton.backgroundTintList =
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (connected) {
                        R.color.red
                    } else {
                        R.color.blue
                    }
                )
            )

        refresh()
    }

    private fun updateDuration(snapshot: ConnectionRuntime.Snapshot) {
        val duration =
            if (snapshot.startedElapsed > 0) {
                SystemClock.elapsedRealtime() - snapshot.startedElapsed
            } else {
                0
            }
        durationText.text = NotificationSupport.formatDuration(duration)
    }

    private val durationTick = object : Runnable {
        override fun run() {
            val current = ConnectionRuntime.current()
            updateDuration(current)
            if (current.state == ConnectionState.CONNECTED) {
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private val livePingTick =
        object : Runnable {
            override fun run() {
                val snapshot = ConnectionRuntime.current()
                if (
                    snapshot.state != ConnectionState.CONNECTED ||
                    !uiResumed.get()
                ) {
                    return
                }

                val settings = settingsRepository.load()
                val manual = manualLivePingRequested.getAndSet(false)
                if (!settings.livePingEnabled && !manual) return

                val profile = repository.find(snapshot.profileId) ?: return
                val sessionId = snapshot.sessionId
                if (!livePingBusy.compareAndSet(false, true)) {
                    if (settings.livePingEnabled) {
                        handler.postDelayed(this, livePingIntervalMs(settings))
                    }
                    return
                }

                livePingText.setText(R.string.live_ping_measuring)
                notifyComposeChanged()

                latencyWorker.execute {
                    val result = runCatching {
                        if (
                            settings.livePingMethod == PingMethod.TCP_CONNECT &&
                            snapshot.mode == ConnectionMode.PROXY
                        ) {
                            pingManager.tcp(
                                profile = profile,
                                attempts = settings.testAttempts,
                                timeoutMs = 5_000
                            )
                        } else {
                            TunnelHealthVerifier.measure(
                                settings = settings,
                                mode = snapshot.mode,
                                attempts = settings.testAttempts.coerceIn(2, 3),
                                budgetMs = LIVE_PING_BUDGET_MS,
                                isCancelled = {
                                    val latest = ConnectionRuntime.current()
                                    latest.state != ConnectionState.CONNECTED ||
                                        latest.sessionId != sessionId
                                }
                            )
                        }
                    }.getOrElse {
                        Diagnostics.recordThrowable("Live ping", it)
                        failedPingResult(settings.livePingMethod, it)
                    }

                    val latest = ConnectionRuntime.current()
                    if (
                        latest.state == ConnectionState.CONNECTED &&
                        latest.sessionId == sessionId &&
                        latest.profileId == profile.id
                    ) {
                        // Live ping is session telemetry only. It intentionally does
                        // not overwrite the stored batch result, so sorting remains stable.
                        livePingResult = result
                    }

                    livePingBusy.set(false)
                    val completionMessage =
                        "UI live ping completed; success=${result.success}, " +
                            "latency=${result.latencyMs}, error=${result.errorCategory.orEmpty()}"
                    if (manual || !result.success) {
                        Diagnostics.info(completionMessage)
                    } else {
                        Diagnostics.debug(completionMessage)
                    }
                    runOnUiThread {
                        val current = ConnectionRuntime.current()
                        val latestSettings = settingsRepository.load()
                        val currentProfile = repository.find(
                            current.profileId ?: repository.selectedId()
                        )
                        renderConnectedInfo(
                            current,
                            currentProfile,
                            latestSettings
                        )
                        notifyComposeChanged()
                        if (
                            current.state == ConnectionState.CONNECTED &&
                            current.sessionId == sessionId &&
                            latestSettings.livePingEnabled &&
                            uiResumed.get()
                        ) {
                            handler.postDelayed(
                                this,
                                livePingIntervalMs(latestSettings)
                            )
                        }
                    }
                }
            }
        }

    private fun livePingIntervalMs(settings: AppSettings): Long =
        settings.livePingIntervalSeconds.coerceIn(3, 300) * 1_000L

    private fun applyPingResultToProfile(
        profile: ConfigProfile,
        result: PingResult
    ) {
        val method = PingMethod.fromStored(
            result.method,
            PingMethod.TCP_CONNECT
        )
        val status = when {
            result.success -> TestStatus.ALIVE
            result.errorCategory == "cancelled" -> TestStatus.UNTESTED
            result.errorCategory in setOf(
                "core_unavailable",
                "invalid_test_url"
            ) -> TestStatus.ERROR
            else -> TestStatus.DEAD
        }

        when (method) {
            PingMethod.TCP_CONNECT -> {
                profile.tcpLatencyMs = result.latencyMs
                profile.tcpLatencyJitterMs = result.jitterMs
                profile.tcpSuccessRatio = result.successRatio
                profile.tcpTestStatus = status
                profile.tcpLastTestAt = result.timestamp
            }
            PingMethod.XRAY_HTTP -> {
                profile.realLatencyMs = result.latencyMs
                profile.realLatencyJitterMs = result.jitterMs
                profile.realSuccessRatio = result.successRatio
                profile.realTestStatus = status
                profile.realLastTestAt = result.timestamp
            }
        }

        // Retain legacy fields for backup compatibility and older installs.
        profile.latencyMs = result.latencyMs
        profile.latencyJitterMs = result.jitterMs
        profile.latencySuccessRatio = result.successRatio
        profile.latencyMethod = method.name
        profile.lastTestAt = maxOf(
            profile.tcpLastTestAt,
            profile.realLastTestAt,
            result.timestamp
        )
        profile.testStatus = status
    }

    private fun failedPingResult(
        method: PingMethod,
        throwable: Throwable
    ) =
        PingResult(
            method = method.name,
            success = false,
            latencyMs = null,
            jitterMs = null,
            successRatio = 0.0,
            resolvedIp = null,
            errorCategory =
                throwable
                    .javaClass
                    .simpleName
        )

    private fun trackPingTasks(
        tasks: Collection<Future<*>>
    ) {
        synchronized(pingTasks) {
            pingTasks.clear()
            pingTasks.addAll(tasks)
        }
    }

    private fun cancelPingTasks() {
        pingGeneration
            .incrementAndGet()

        val tasks =
            synchronized(pingTasks) {
                pingTasks
                    .toList()
                    .also {
                        pingTasks.clear()
                    }
            }

        pingManager.cancel(tasks)
        pingResultBuffer.clear()
        pingFlushScheduled.set(false)
        composeBatchRunning.set(false)
        composeBatchMethod = null
        composeBatchCompleted.set(0)
        composeBatchTotal.set(0)
        setBatchControlsEnabled(true)
        notifyComposeChanged()
        if (::repository.isInitialized && !activityDestroyed.get()) {
            repairStaleTestingStates()
        }
    }

    private fun dualLatencyTier(profile: ConfigProfile): Int {
        val tcpAlive =
            profile.tcpTestStatus == TestStatus.ALIVE &&
                profile.tcpLatencyMs != null
        val realAlive =
            profile.realTestStatus == TestStatus.ALIVE &&
                profile.realLatencyMs != null
        return when {
            tcpAlive && realAlive -> 0
            realAlive -> 1
            tcpAlive -> 2
            else -> 3
        }
    }

    private fun dualLatencyScore(profile: ConfigProfile): Long {
        val tcp = profile.tcpLatencyMs
            ?.takeIf { profile.tcpTestStatus == TestStatus.ALIVE }
        val real = profile.realLatencyMs
            ?.takeIf { profile.realTestStatus == TestStatus.ALIVE }
        return when {
            tcp != null && real != null ->
                ((tcp * 40L) + (real * 60L)) / 100L
            real != null -> real + SINGLE_METHOD_PENALTY_MS
            tcp != null -> tcp + SINGLE_METHOD_PENALTY_MS
            else -> Long.MAX_VALUE
        }
    }

    private fun profileComparator(
        settings:
            com.trivox.client.data
                .AppSettings,
        connectedId: String?
    ): Comparator<ConfigProfile> {
        val base =
            when (settings.sortMode) {
                ProfileSortMode.SMART ->
                    compareByDescending<
                        ConfigProfile
                        > {
                        it.favorite
                    }
                        .thenBy(::dualLatencyTier)
                        .thenBy(::dualLatencyScore)
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }

                ProfileSortMode
                    .LOWEST_LATENCY ->
                    compareBy<ConfigProfile>(
                        ::dualLatencyTier
                    )
                        .thenBy(::dualLatencyScore)
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }

                ProfileSortMode.NAME ->
                    compareBy {
                        it.name.lowercase(
                            Locale.ROOT
                        )
                    }

                ProfileSortMode
                    .LAST_TESTED ->
                    compareByDescending<
                        ConfigProfile
                        > {
                        it.lastTestAt
                    }
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }

                ProfileSortMode.GROUP ->
                    compareBy<
                        ConfigProfile
                        > {
                        it.group.lowercase(
                            Locale.ROOT
                        )
                    }
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }
            }

        // connectedId is intentionally not used as a ranking key.
        // The row keeps its normal SMART/name/latency/group position.
        @Suppress("UNUSED_VARIABLE")
        val connectedProfileId = connectedId
        return base
    }

    private fun pingMethodLabel(
        method: PingMethod
    ): String =
        when (method) {
            PingMethod.TCP_CONNECT ->
                getString(
                    R.string
                        .ping_method_tcp_short
                )

            PingMethod.XRAY_HTTP ->
                getString(
                    R.string
                        .ping_method_xray_short
                )
        }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val shared =
                intent.getStringExtra(
                    Intent.EXTRA_TEXT
                )
                    ?.takeIf(String::isNotBlank)
                    ?: return

            if (
                shared.length >
                MAX_SHARED_INPUT_CHARS
            ) {
                Diagnostics.warning(
                    "Rejected oversized shared config input"
                )
                toast(
                    getString(
                        R.string.invalid_config
                    )
                )
                return
            }

            importText(shared, activeSubscriptionId)
        }
    }

    private fun runOnUiThreadIfAlive(
        action: () -> Unit
    ) {
        if (activityDestroyed.get()) {
            return
        }

        runOnUiThread {
            if (
                !activityDestroyed.get() &&
                !isFinishing &&
                !isDestroyed
            ) {
                action()
            }
        }
    }

    private fun notifyComposeChanged() {
        val update = {
            if (!activityDestroyed.get()) {
                composeRevisionState.intValue = composeRevisionState.intValue + 1
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            handler.post(update)
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        uiResumed.set(true)
        renderSubscriptionTabs(
            force = true
        )
        refresh()
        UpdateChecker.checkIfDue(this)
        scheduleCommunitySyncIfDue()

        val current = ConnectionRuntime.current()
        if (
            current.state == ConnectionState.CONNECTED &&
            settingsRepository.load().livePingEnabled
        ) {
            handler.removeCallbacks(livePingTick)
            handler.postDelayed(
                livePingTick,
                FIRST_LIVE_PING_DELAY_MS
            )
        }
    }

    override fun onPause() {
        uiResumed.set(false)
        handler.removeCallbacks(livePingTick)
        super.onPause()
    }

    override fun onDestroy() {
        activityDestroyed.set(true)
        ConnectionRuntime
            .removeListener(
                runtimeListener
            )
        handler
            .removeCallbacksAndMessages(
                null
            )
        cancelPingTasks()
        pingManager.close()
        subscriptionUpdater.close()
        worker.shutdownNow()
        latencyWorker.shutdownNow()
        storageWorker.shutdownNow()
        super.onDestroy()
    }


    override fun composeRevision(): Int = composeRevisionState.intValue

    override fun composeBatchState(): ComposeBatchState =
        ComposeBatchState(
            running = composeBatchRunning.get(),
            method = composeBatchMethod,
            completed = composeBatchCompleted.get(),
            total = composeBatchTotal.get()
        )

    override fun composeProfiles(
        query: String,
        subscriptionId: String?,
        favoritesOnly: Boolean
    ): List<ConfigProfile> {
        if (!::repository.isInitialized || !::settingsRepository.isInitialized) return emptyList()
        val current = ConnectionRuntime.current()
        val settings = settingsRepository.load()
        val text = query.trim()
        return repository.all().asSequence()
            .filter { profile ->
                val sourceMatches = subscriptionId == null || profile.subscriptionId == subscriptionId
                val favoriteMatches = !favoritesOnly || profile.favorite
                val searchMatches = text.isBlank() ||
                    profile.name.contains(text, true) ||
                    profile.server.contains(text, true) ||
                    profile.protocol.contains(text, true) ||
                    profile.exitCountry.contains(text, true) ||
                    profile.exitIp.contains(text, true)
                sourceMatches && favoriteMatches && (profile.id == current.profileId || searchMatches)
            }
            .sortedWith(profileComparator(settings, current.profileId))
            .toList()
    }

    override fun composeSubscriptions(): List<SubscriptionSource> =
        SubscriptionRepository(this).all().sortedBy { it.name.lowercase(Locale.getDefault()) }

    override fun composeSubscriptionRefreshing(): Boolean = subscriptionRefreshBusy.get()

    override fun composeSelectedId(): String? =
        if (::repository.isInitialized) repository.selectedId() else null

    override fun composeRuntime(): ConnectionRuntime.Snapshot {
        val current = ConnectionRuntime.current()
        if (
            connectionStartPending.get() &&
            current.state in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            val selected = if (::repository.isInitialized) {
                repository.find(repository.selectedId())
            } else {
                null
            }
            return current.copy(
                state = ConnectionState.PREPARING,
                profileId = selected?.id ?: current.profileId,
                profileName = selected?.name ?: current.profileName,
                error = "",
                mode = if (::settingsRepository.isInitialized) {
                    settingsRepository.load().mode
                } else {
                    current.mode
                }
            )
        }
        return current
    }

    override fun composeSettings(): AppSettings =
        if (::settingsRepository.isInitialized) settingsRepository.load() else AppSettings()

    override fun composeActiveSubscriptionId(): String? = activeSubscriptionId

    override fun composeSelectSubscription(id: String?) {
        activeSubscriptionId = id
        storeActiveSubscription()
        renderSubscriptionTabs(force = true)
        refresh()
    }

    override fun composeSelectProfile(id: String) {
        repository.find(id)?.let { selectProfile(it) }
    }

    override fun composeProfileActions(id: String) {
        repository.find(id)?.let { showActions(it) }
    }

    override fun composeSubscriptionActions(id: String) {
        when (id) {
            "trivox-scope-all" -> showScopeActions(favoritesOnly = false)
            "trivox-scope-favorites" -> showScopeActions(favoritesOnly = true)
            else -> showSubscriptionActions(id)
        }
    }

    private fun showScopeActions(favoritesOnly: Boolean) {
        val scopeName = getString(
            if (favoritesOnly) {
                R.string.v33_scope_favorites_actions
            } else {
                R.string.v33_scope_all_actions
            }
        )
        val profiles = repository.all()
            .filter { !favoritesOnly || it.favorite }
        if (profiles.isEmpty()) {
            toast(getString(R.string.v33_scope_empty))
            return
        }

        val labels = arrayOf(
            getString(R.string.v33_scope_update_all),
            getString(R.string.v33_scope_export),
            getString(R.string.v33_scope_delete_no_tcp),
            getString(R.string.v33_scope_delete_no_real),
            getString(R.string.v33_scope_delete_no_both),
            getString(R.string.v33_scope_delete_all)
        )

        AlertDialog.Builder(this)
            .setTitle(scopeName)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> refreshSubscriptionsFromMain()
                    1 -> exportScopeLinks(scopeName, profiles)
                    2 -> confirmScopeCleanup(
                        scopeName = scopeName,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.tcpTestStatus != TestStatus.ALIVE ||
                            it.tcpLatencyMs == null
                    }
                    3 -> confirmScopeCleanup(
                        scopeName = scopeName,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.realTestStatus != TestStatus.ALIVE ||
                            it.realLatencyMs == null
                    }
                    4 -> confirmScopeCleanup(
                        scopeName = scopeName,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        (
                            it.tcpTestStatus != TestStatus.ALIVE ||
                                it.tcpLatencyMs == null
                            ) &&
                            (
                                it.realTestStatus != TestStatus.ALIVE ||
                                    it.realLatencyMs == null
                                )
                    }
                    5 -> confirmScopeCleanup(
                        scopeName = scopeName,
                        profiles = profiles,
                        title = labels[which]
                    ) { true }
                }
            }
            .show()
    }

    private fun exportScopeLinks(
        scopeName: String,
        profiles: List<ConfigProfile>
    ) {
        val links = profiles
            .asSequence()
            .map { it.raw.trim() }
            .filter(String::isNotBlank)
            .filter { value ->
                runCatching { URI(value).scheme }
                    .getOrNull()
                    ?.lowercase(Locale.ROOT) in SHARE_SCHEMES
            }
            .distinct()
            .toList()

        if (links.isEmpty()) {
            toast(getString(R.string.subscription_export_none))
            return
        }

        val payload = links.joinToString("\n")
        val title = getString(
            R.string.subscription_export_title,
            scopeName
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, title)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, payload)
                },
                title
            )
        )
    }

    private fun confirmScopeCleanup(
        scopeName: String,
        profiles: List<ConfigProfile>,
        title: String,
        predicate: (ConfigProfile) -> Boolean
    ) {
        val targets = profiles.filter(predicate)
        val runtime = ConnectionRuntime.current()
        if (
            runtime.state !in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            ) &&
            targets.any { it.id == runtime.profileId }
        ) {
            toast(getString(R.string.disconnect_subscription_first))
            return
        }
        if (targets.isEmpty()) {
            toast(getString(R.string.subscription_cleanup_none))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                getString(
                    R.string.v33_scope_cleanup_confirm,
                    targets.size,
                    scopeName
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storageWorker.execute {
                    val removed = repository.deleteMany(
                        targets.map(ConfigProfile::id)
                    )
                    runOnUiThread {
                        subscriptionTabsSignature = ""
                        renderSubscriptionTabs(force = true)
                        refresh()
                        toast(
                            getString(
                                R.string.v33_scope_cleanup_done,
                                removed
                            )
                        )
                    }
                }
            }
            .show()
    }

    override fun composeMagicClipboard() {
        runMagicClipboardImport()
    }

    override fun composeMagicClipboardBusy(): Boolean =
        magicClipboardBusy.get()

    override fun composePingProfile(id: String, method: PingMethod) {
        repository.find(id)?.let { testSingleProfile(it, method) }
    }

    override fun composeAdd() = showAddOptions()
    override fun composeRefreshSubscriptions() = refreshSubscriptionsFromMain()
    override fun composeTestAll(method: PingMethod) = testProfiles(method)
    override fun composeToggleConnection() = toggleConnection()
    override fun composePause() = showPauseOptions()
    override fun composeLivePing() = requestLivePingNow()
    override fun composeRealDelay() = requestRealDelayNow()
    override fun composeLeakStatus(): String = composeLeakStatusText
    override fun composeLeakCheckBusy(): Boolean = leakCheckBusy.get()
    override fun composeCheckLeaksAndRepair() = checkLeaksAndRepair()
    override fun composeRefreshExit() = refreshExitInfo(force = true, showError = true)
    override fun composeCopySummary() = copyConnectionSummary()
    override fun composeSelectFastest() = selectFastestProfile()
    override fun composeCopyProxy() = copyLocalProxyEndpoint()
    override fun composeExportBackup() = exportCompleteBackup()
    override fun composeClearDead() = confirmClearDeadProfiles()


    private fun scheduleAutomaticLeakCheck(
        snapshot: ConnectionRuntime.Snapshot
    ) {
        if (!::settingsRepository.isInitialized) return
        if (
            snapshot.state != ConnectionState.CONNECTED ||
            snapshot.mode != ConnectionMode.VPN ||
            snapshot.sessionId <= 0L ||
            !settingsRepository.load().autoLeakProtection
        ) {
            return
        }

        if (
            lastAutoLeakSessionId.getAndSet(snapshot.sessionId) ==
            snapshot.sessionId
        ) {
            return
        }

        val expectedSession = snapshot.sessionId
        handler.postDelayed(
            {
                if (activityDestroyed.get()) return@postDelayed
                val latest = ConnectionRuntime.current()
                if (
                    latest.sessionId != expectedSession ||
                    latest.state != ConnectionState.CONNECTED ||
                    latest.mode != ConnectionMode.VPN ||
                    !settingsRepository.load().autoLeakProtection
                ) {
                    return@postDelayed
                }

                checkLeaksAndRepair(
                    autoTriggered = true,
                    expectedSessionId = expectedSession
                )
            },
            1_500L
        )
    }

    private fun checkLeaksAndRepair(
        autoTriggered: Boolean = false,
        expectedSessionId: Long? = null
    ) {
        val snapshot = ConnectionRuntime.current()
        if (
            snapshot.state != ConnectionState.CONNECTED ||
            snapshot.mode != ConnectionMode.VPN ||
            (
                expectedSessionId != null &&
                    snapshot.sessionId != expectedSessionId
                )
        ) {
            if (!autoTriggered) {
                toast(getString(R.string.v19_leak_connect_first))
            }
            return
        }

        if (!leakCheckBusy.compareAndSet(false, true)) {
            return
        }

        composeLeakStatusText = getString(R.string.v19_leak_checking)
        notifyComposeChanged()

        worker.execute {
            val manager = LeakProtectionManager(this@MainActivity)
            val currentSettings = settingsRepository.load()
            val result = runCatching {
                manager.check(currentSettings)
            }

            runOnUiThreadIfAlive {
                leakCheckBusy.set(false)

                val latestBeforeResult = ConnectionRuntime.current()
                if (
                    expectedSessionId != null &&
                    (
                        latestBeforeResult.sessionId != expectedSessionId ||
                            latestBeforeResult.state != ConnectionState.CONNECTED ||
                            latestBeforeResult.mode != ConnectionMode.VPN
                        )
                ) {
                    notifyComposeChanged()
                    return@runOnUiThreadIfAlive
                }

                result.onSuccess { report ->
                    when {
                        report.hasLeak -> {
                            val hardened = manager.hardenedSettings(
                                currentSettings,
                                report
                            )
                            val changed =
                                hardened.toJson().toString() !=
                                    currentSettings.toJson().toString()

                            if (changed) {
                                settingsRepository.save(hardened)
                                composeLeakStatusText =
                                    getString(R.string.v19_leak_fixed) +
                                        "\n" +
                                        report.compactSummary()

                                val latest = ConnectionRuntime.current()
                                val reconnectProfile = latest.profileId
                                if (
                                    latest.state == ConnectionState.CONNECTED &&
                                    latest.mode == ConnectionMode.VPN &&
                                    !reconnectProfile.isNullOrBlank()
                                ) {
                                    ConnectionSwitchCoordinator.queue(
                                        context = this,
                                        profileId = reconnectProfile,
                                        targetMode = ConnectionMode.VPN,
                                        reason = "automatic_leak_repair"
                                    )
                                }
                            } else {
                                composeLeakStatusText =
                                    getString(R.string.v21_leak_unresolved) +
                                        "\n" +
                                        report.compactSummary()
                            }
                        }

                        report.probeIncomplete -> {
                            composeLeakStatusText =
                                getString(R.string.v21_leak_inconclusive) +
                                    "\n" +
                                    report.compactSummary()
                        }

                        else -> {
                            composeLeakStatusText =
                                getString(R.string.v19_leak_safe) +
                                    "\n" +
                                    report.compactSummary()
                        }
                    }

                    if (autoTriggered) {
                        Diagnostics.info(
                            "Automatic leak guard completed; " +
                                "session=${snapshot.sessionId}, " +
                                "leak=${report.hasLeak}, " +
                                "incomplete=${report.probeIncomplete}"
                        )
                    }
                }.onFailure { error ->
                    composeLeakStatusText = getString(
                        R.string.v19_leak_check_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                    Diagnostics.recordThrowable(
                        if (autoTriggered) {
                            "Automatic leak protection check"
                        } else {
                            "Leak protection check"
                        },
                        error
                    )
                }
                notifyComposeChanged()
            }
        }
    }

    override fun composeSelectedProfile(): ConfigProfile? {
        if (!::repository.isInitialized) return null
        val current = ConnectionRuntime.current()
        return repository.find(current.profileId ?: repository.selectedId())
    }

    override fun composeProfileStats(): ComposeProfileStats =
        composeProfileStatsCache

    override fun composeSaveSettings(value: AppSettings) {
        if (!::settingsRepository.isInitialized) return

        val previous = settingsRepository.load()
        val next = value.copy().normalize()
        val runtime = ConnectionRuntime.current()

        if (
            previous.mode != next.mode &&
            runtime.state !in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            next.mode = previous.mode
            toast(getString(R.string.v15_disconnect_to_change_mode))
        }

        settingsRepository.save(next)
        Diagnostics.debug("Settings auto-saved")

        if (previous.launcherIconStyle != next.launcherIconStyle) {
            LauncherIconManager.apply(this, next.launcherIconStyle)
        }

        if (
            !previous.autoLeakProtection &&
            next.autoLeakProtection
        ) {
            lastAutoLeakSessionId.set(-1L)
            scheduleAutomaticLeakCheck(runtime)
        }

        if (::modeSpinner.isInitialized && previous.mode != next.mode) {
            modeSpinner.setSelection(
                if (next.mode == ConnectionMode.PROXY) 0 else 1
            )
        }

        if (previous.livePingEnabled != next.livePingEnabled) {
            handler.removeCallbacks(livePingTick)
            if (
                next.livePingEnabled &&
                uiResumed.get() &&
                ConnectionRuntime.current().state == ConnectionState.CONNECTED
            ) {
                handler.post(livePingTick)
            }
        }

        if (previous.gridMode != next.gridMode) {
            Diagnostics.info("UI grid mode changed: ${next.gridMode}")
            applyLayout()
        } else {
            refresh()
        }

        if (previous.themeMode != next.themeMode) {
            applyNightModeWithMotion(
                when (next.themeMode) {
                    com.trivox.client.data.ThemeMode.SYSTEM ->
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    com.trivox.client.data.ThemeMode.LIGHT ->
                        AppCompatDelegate.MODE_NIGHT_NO
                    com.trivox.client.data.ThemeMode.DARK ->
                        AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }
    }

    override fun composeLanguageTag(): String =
        AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .substringBefore(',')
            .lowercase(Locale.ROOT)
            .let { tag ->
                when {
                    tag.startsWith("fa") -> "fa"
                    tag.startsWith("en") -> "en"
                    else -> ""
                }
            }

    override fun composeSetLanguage(tag: String) {
        val normalized = when (tag.lowercase(Locale.ROOT)) {
            "fa" -> "fa"
            "en" -> "en"
            else -> ""
        }
        if (composeLanguageTag() == normalized) return

        Diagnostics.info(
            "Application language changed to " +
                normalized.ifBlank { "system-default" }
        )
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(normalized)
        )
    }

    override fun composeCheckForUpdates() {
        composeUpdateStatusText = getString(R.string.update_checking)
        notifyComposeChanged()
        UpdateChecker.check(this, showCurrentResult = true) { result ->
            composeUpdateStatusText = when {
                result.available ->
                    getString(R.string.update_found, result.version)

                result.error.isNotBlank() ->
                    getString(
                        R.string.update_check_failed,
                        result.error
                    )

                else -> getString(R.string.update_current)
            }
            notifyComposeChanged()
        }
    }

    override fun composeUpdateStatus(): String =
        composeUpdateStatusText

    override fun composeLocalProxyStatus(): String {
        if (!::settingsRepository.isInitialized) return ""
        val settings = settingsRepository.load()
        val runtime = ConnectionRuntime.current()
        val profile = if (::repository.isInitialized) {
            repository.find(runtime.profileId)
        } else {
            null
        }
        val listenerExpected =
            runtime.mode == ConnectionMode.PROXY ||
                (
                    settings.localProxyInVpn &&
                        !profile?.protocol.equals("wireguard", ignoreCase = true)
                    )

        return when {
            runtime.state in setOf(
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING
            ) && listenerExpected ->
                getString(
                    R.string.v15_proxy_active,
                    settings.socksPort
                )

            runtime.state == ConnectionState.CONNECTED &&
                runtime.mode == ConnectionMode.VPN ->
                getString(R.string.v15_proxy_disabled_vpn)

            else ->
                getString(
                    R.string.v15_proxy_ready,
                    settings.socksPort
                )
        }
    }

    override fun composeOpenSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun composeOpenRouting() {
        startActivity(Intent(this, AppRoutingActivity::class.java))
    }

    override fun composeOpenDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
    }

    override fun composeOpenSubscriptions(sourceId: String?) {
        openSubscriptionManager(sourceId = sourceId)
    }

    override fun composeToggleGrid() {
        val settings = settingsRepository.load()
        composeSetGrid(!settings.gridMode)
    }

    override fun composeSetGrid(value: Boolean) {
        val settings = settingsRepository.load()
        if (settings.gridMode == value) {
            notifyComposeChanged()
            return
        }
        settings.gridMode = value
        settingsRepository.save(settings)
        Diagnostics.info("UI grid mode changed: $value")
        applyLayout()
    }

    override fun composeSetHideIp(value: Boolean) {
        if (!::settingsRepository.isInitialized) return
        val settings = settingsRepository.load()
        if (settings.hideIpOnMain == value) return
        settings.hideIpOnMain = value
        settingsRepository.save(settings)
        refresh()
    }

    override fun composeSetLivePingEnabled(value: Boolean) {
        if (!::settingsRepository.isInitialized) return
        val settings = settingsRepository.load()
        if (settings.livePingEnabled == value) return
        settings.livePingEnabled = value
        settingsRepository.save(settings)
        handler.removeCallbacks(livePingTick)
        val runtime = ConnectionRuntime.current()
        if (
            value &&
            uiResumed.get() &&
            runtime.state == ConnectionState.CONNECTED
        ) {
            handler.postDelayed(
                livePingTick,
                FIRST_LIVE_PING_DELAY_MS
            )
        }
        refresh()
    }


    private fun chooseSimpleProfile(): ConfigProfile? {
        val communityEnabled = settingsRepository.load().communitySourceEnabled
        fun allowed(profile: ConfigProfile): Boolean =
            profile.enabled &&
                (communityEnabled ||
                    profile.subscriptionId != CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID)

        repository.find(repository.selectedId())
            ?.takeIf(::allowed)
            ?.let { return it }

        val enabled = repository.all().filter(::allowed)
        val tested = enabled
            .filter { dualLatencyTier(it) < 3 }
            .minWithOrNull(
                compareBy<ConfigProfile>(::dualLatencyTier)
                    .thenBy(::dualLatencyScore)
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
        if (tested != null) return tested

        return enabled.firstOrNull {
            it.subscriptionId == CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID
        } ?: enabled.firstOrNull()
    }

    private fun scheduleCommunitySyncIfDue() {
        if (!::settingsRepository.isInitialized || communitySyncBusy.get()) return
        val settings = settingsRepository.load()
        if (!settings.communitySourceEnabled || !settings.communityAutoSync) return
        val manager = CommunityConfigManager(this)
        if (manager.needsSync(settings.communityChannelUsername)) {
            syncCommunityConfigs(silent = true, connectAfter = false)
        }
    }

    private fun syncCommunityConfigs(
        silent: Boolean,
        connectAfter: Boolean,
        requireFresh: Boolean = false
    ) {
        if (!communitySyncBusy.compareAndSet(false, true)) return
        val settings = settingsRepository.load()
        if (!settings.communitySourceEnabled) {
            communitySyncBusy.set(false)
            composeCommunityStatusText = getString(R.string.v26_community_disabled)
            notifyComposeChanged()
            return
        }

        composeCommunityStatusText = getString(R.string.v26_community_syncing)
        notifyComposeChanged()
        worker.execute {
            val result = CommunityConfigManager(this).sync(
                username = settings.communityChannelUsername,
                allowLocalFallback = !requireFresh,
                mode = settings.experienceMode.name.lowercase(Locale.ROOT)
            )
            runOnUiThreadIfAlive {
                communitySyncBusy.set(false)
                composeCommunityStatusText = if (result.success) {
                    getString(
                        R.string.v26_community_synced,
                        result.imported,
                        result.source
                    )
                } else {
                    getString(
                        R.string.v26_community_failed,
                        result.error.ifBlank { getString(R.string.unknown_error) }
                    )
                }
                subscriptionTabsSignature = ""
                renderSubscriptionTabs(force = true)
                refresh()

                if (!silent && !result.success) {
                    toast(composeCommunityStatusText)
                }
                if (connectAfter && result.success) {
                    val profile = chooseSimpleProfile()
                    if (profile != null) {
                        repository.select(profile.id)
                        refresh()
                        toggleConnection()
                    } else {
                        toast(getString(R.string.v26_simple_no_config))
                    }
                }
            }
        }
    }

    override fun composeSimpleToggleConnection() {
        val runtime = ConnectionRuntime.current()
        if (
            connectionStartPending.get() ||
            runtime.state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)
        ) {
            toggleConnection()
            return
        }

        val profile = chooseSimpleProfile()
        if (profile != null) {
            repository.select(profile.id)
            refresh()
            toggleConnection()
            return
        }

        val settings = settingsRepository.load()
        if (settings.communitySourceEnabled) {
            syncCommunityConfigs(silent = false, connectAfter = true)
        } else {
            toast(getString(R.string.v26_simple_no_config))
        }
    }

    override fun composeCommunitySync() {
        syncCommunityConfigs(
            silent = false,
            connectAfter = false,
            requireFresh = true
        )
    }

    override fun composeCommunitySyncBusy(): Boolean = communitySyncBusy.get()

    override fun composeCommunityStatus(): String = composeCommunityStatusText

    override fun composeOpenCommunityChannel() {
        val username = CommunityConfigManager.normalizeUsername(
            settingsRepository.load().communityChannelUsername
        ) ?: CommunityConfigManager.DEFAULT_CHANNEL
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://t.me/$username")
                )
            )
        }.onFailure {
            Diagnostics.recordThrowable("Open community channel", it)
        }
    }

    override fun composeUsageSnapshot(): ConnectionUsageStore.UsageSnapshot =
        ConnectionUsageStore.snapshot(this)

    override fun composeUsageHistory(): List<ConnectionUsageStore.SessionRecord> =
        ConnectionUsageStore.recentSessions(this, 8)

    override fun composeRequestQuickTile() {
        requestQuickSettingsTile()
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(getString(R.string.quick_tile_manual_add))
            return
        }
        val manager = getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            toast(getString(R.string.quick_tile_request_error))
            return
        }
        manager.requestAddTileService(
            ComponentName(this, QuickConnectTileService::class.java),
            getString(R.string.quick_tile_label),
            Icon.createWithResource(this, R.drawable.ic_qs_trivox),
            mainExecutor
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.quick_tile_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.quick_tile_already_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.quick_tile_not_added
                else -> R.string.quick_tile_request_error
            }
            toast(getString(message))
        }
    }

    override fun composeSetMode(mode: ConnectionMode) {
        val current = ConnectionRuntime.current().state
        if (current !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) return
        val settings = settingsRepository.load()
        settings.mode = mode
        settingsRepository.save(settings)
        if (::modeSpinner.isInitialized) {
            modeSpinner.setSelection(if (mode == ConnectionMode.PROXY) 0 else 1)
        }
        refresh()
    }

    override fun composeLivePingLabel(): String {
        val snapshot = ConnectionRuntime.current()
        if (snapshot.state != ConnectionState.CONNECTED) {
            return getString(R.string.live_ping_off)
        }
        val result = livePingResult
        return when {
            livePingBusy.get() && result == null ->
                getString(R.string.live_ping_measuring)

            result?.success == true && result.latencyMs != null ->
                getString(
                    R.string.live_ping_value_method,
                    result.latencyMs,
                    pingMethodLabel(PingMethod.fromStored(result.method))
                )

            result != null ->
                getString(R.string.live_ping_failed)

            !settingsRepository.load().livePingEnabled ->
                getString(R.string.live_ping_auto_disabled)

            else ->
                getString(R.string.live_ping_waiting)
        }
    }

    override fun composeRealDelayLabel(): String {
        val snapshot = ConnectionRuntime.current()
        if (snapshot.state != ConnectionState.CONNECTED) {
            return getString(R.string.real_delay_off)
        }
        val result = realDelayResult
        return when {
            realDelayBusy.get() && result == null ->
                getString(R.string.real_delay_measuring)

            result?.success == true && result.latencyMs != null ->
                getString(R.string.real_delay_value, result.latencyMs)

            result != null ->
                getString(R.string.real_delay_failed)

            else ->
                getString(R.string.real_delay_waiting)
        }
    }

    companion object {
        private const val MENU_VIEW = 100
        private const val MENU_FASTEST = 101
        private const val MENU_COPY_PROXY = 102
        private const val MENU_EXPORT_BACKUP = 103
        private const val MENU_CLEAR_DEAD = 104
        private const val MENU_SETTINGS = 105
        private const val MENU_ROUTING = 106
        private const val MENU_DIAGNOSTICS = 107
        private const val LIVE_PING_BUDGET_MS =
            4_500
        private const val REAL_DELAY_BUDGET_MS =
            10_000
        private const val FIRST_LIVE_PING_DELAY_MS =
            1_500L
        private const val LIVE_PING_URL =
            "https://cp.cloudflare.com/generate_204"
        private const val
            CONNECTION_ACTION_COOLDOWN_MS =
                250L
        private const val
            CONNECTION_START_UI_TIMEOUT_MS =
                6_000L
        private const val
            PROFILE_SWITCH_DELAY_MS = 180L
        private const val PING_RESULT_FLUSH_MS = 300L
        private const val SINGLE_METHOD_PENALTY_MS = 5_000L
        private const val MAIN_UI_PREFS = "main_ui"
        private const val KEY_ACTIVE_SUBSCRIPTION =
            "active_subscription_id"
        private const val KEY_QUICK_TOOLS_EXPANDED =
            "quick_tools_expanded"
        private const val EXIT_CACHE_MS =
            10 * 60 * 1_000L
        private const val MAX_SHARED_INPUT_CHARS =
            4 * 1024 * 1024

        private val SHARE_SCHEMES = setOf(
            "vless",
            "vmess",
            "trojan",
            "ss",
            "socks",
            "socks5",
            "http",
            "https"
        )
    }
}

private class SimpleItemSelectedListener(
    private val callback: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ) = callback(position)

    override fun onNothingSelected(
        parent: android.widget.AdapterView<*>?
    ) = Unit
}
