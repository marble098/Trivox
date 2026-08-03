package com.trivox.client.ui

import android.Manifest
import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trivox.client.R
import com.trivox.client.config.ConfigParser
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManager
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import com.trivox.client.data.ProfileSortMode
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.data.TestStatus
import com.trivox.client.network.ConnectionInfoManager
import com.trivox.client.network.PingManager
import com.trivox.client.network.SubscriptionManager
import com.trivox.client.network.SubscriptionRefreshCoordinator
import com.trivox.client.update.UpdateChecker
import com.trivox.client.service.ConnectionService
import com.trivox.client.service.NotificationSupport
import com.trivox.client.service.TrivoxVpnService
import com.trivox.client.util.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ThemedActivity() {
    private lateinit var repository: ConfigRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var adapter: ProfileAdapter
    private lateinit var pingManager: PingManager

    private lateinit var list: RecyclerView
    private lateinit var stateText: TextView
    private lateinit var durationText: TextView
    private lateinit var selectedText: TextView
    private lateinit var livePingText: Button
    private lateinit var exitInfoText: TextView
    private lateinit var emptyText: TextView
    private lateinit var connectButton: Button
    private lateinit var modeSpinner: Spinner
    private lateinit var refreshExitButton: Button
    private lateinit var copySummaryButton: Button
    private lateinit var subscriptionTabs: LinearLayout
    private lateinit var refreshSubscriptionsButton: Button
    private lateinit var subscriptionUpdater: SubscriptionRefreshCoordinator

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val livePingBusy =
        AtomicBoolean(false)
    private val connectionActionBusy =
        AtomicBoolean(false)
    private val pingGeneration =
        AtomicLong(0)
    private val pingTasks =
        mutableListOf<Future<*>>()
    private val subscriptionRefreshBusy =
        AtomicBoolean(false)

    @Volatile
    private var livePingResult:
        PingResult? = null

    private var filter = ""
    private var importDialogInput: EditText? = null
    private var pendingVpnProfile: String? = null
    private var lastInfoProfileId: String? = null
    private var activeSubscriptionId: String? = null
    private var subscriptionTabsSignature = ""

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                contentResolver.openInputStream(it)
                    ?.bufferedReader()
                    ?.use { reader -> importText(reader.readText()) }
            }
        }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val id = pendingVpnProfile
            pendingVpnProfile = null

            if (id != null && VpnService.prepare(this) == null) {
                startVpn(id)
            } else if (id != null) {
                toast(getString(R.string.permission_required))
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val runtimeListener: (ConnectionRuntime.Snapshot) -> Unit = {
        runOnUiThread { renderState(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val localePrefs = getSharedPreferences("locale", MODE_PRIVATE)
        if (
            !localePrefs.getBoolean("initialized", false) &&
            AppCompatDelegate.getApplicationLocales().isEmpty
        ) {
            localePrefs.edit().putBoolean("initialized", true).apply()
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags("fa")
            )
        }

        setContentView(R.layout.activity_main)

        repository = ConfigRepository(this)
        settingsRepository = SettingsRepository(this)
        pingManager =
            PingManager(
                CoreManager(this).adapter
            )
        subscriptionUpdater =
            SubscriptionRefreshCoordinator(this)

        bindViews()
        setupToolbar()
        setupList()
        setupControls()
        handleShareIntent(intent)

        ConnectionRuntime.addListener(runtimeListener)
        renderState(ConnectionRuntime.current())
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
        exitInfoText = findViewById(R.id.exitInfoText)
        emptyText = findViewById(R.id.emptyText)
        connectButton = findViewById(R.id.connectButton)
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
        toolbar.menu.add(0, MENU_VIEW, 0, R.string.grid).setShowAsAction(2)
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
            onClick = {
                repository.select(it.id)
                refresh()
            },
            onLongClick = ::showActions,
            onAction = ::showActions,
            onPing = ::testSingleProfile
        )

        list.adapter = adapter
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

        livePingText
            .setOnClickListener {
                requestLivePingNow()
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
        findViewById<Button>(R.id.testButton)
            .setOnClickListener { testProfiles() }
        connectButton.setOnClickListener { toggleConnection() }
        refreshExitButton.setOnClickListener {
            refreshExitInfo(force = true, showError = true)
        }
        copySummaryButton.setOnClickListener { copyConnectionSummary() }
    }


    private fun showAddOptions() {
        val labels = arrayOf(
            getString(R.string.import_existing),
            getString(R.string.add_manual_config),
            getString(R.string.add_proxy_chain)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_options)
            .setItems(labels) { _, position ->
                when (position) {
                    0 -> showImportDialog()
                    1 -> startActivity(
                        Intent(this, ManualConfigActivity::class.java)
                    )
                    2 -> startActivity(
                        Intent(this, ProxyChainActivity::class.java)
                    )
                }
            }
            .show()
    }

    private fun refreshSubscriptionsFromMain() {
        if (!subscriptionRefreshBusy.compareAndSet(false, true)) {
            toast(getString(R.string.subscription_update_busy))
            return
        }

        refreshSubscriptionsButton.isEnabled = false
        refreshSubscriptionsButton.text = "…"
        val accepted = subscriptionUpdater.refreshEnabled { summary ->
            runOnUiThread {
                subscriptionRefreshBusy.set(false)
                refreshSubscriptionsButton.isEnabled = true
                refreshSubscriptionsButton.text = "↻"
                subscriptionTabsSignature = ""
                renderSubscriptionTabs(force = true)
                refresh()
                toast(
                    getString(
                        R.string.subscription_update_done,
                        summary.updated,
                        summary.profiles,
                        summary.failed
                    )
                )
            }
        }

        if (!accepted) {
            subscriptionRefreshBusy.set(false)
            refreshSubscriptionsButton.isEnabled = true
            refreshSubscriptionsButton.text = "↻"
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

        livePingResult = null
        livePingText.setText(
            R.string
                .live_ping_measuring
        )
        handler.removeCallbacks(
            livePingTick
        )
        handler.post(
            livePingTick
        )
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

    private fun dp(value: Int): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density
            ).toInt()

    private fun compactAdapter(values: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item, values).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun refresh() {
        val selectedId = repository.selectedId()
        val current = ConnectionRuntime.current()
        renderSubscriptionTabs()
        val values = repository.all()
            .asSequence()
            .filter { profile ->
                val sourceMatches =
                    activeSubscriptionId ==
                        null ||
                        profile
                            .subscriptionId ==
                        activeSubscriptionId

                val searchMatches =
                    filter.isBlank() ||
                        profile.name
                            .contains(
                                filter,
                                true
                            ) ||
                        profile.server
                            .contains(
                                filter,
                                true
                            ) ||
                        profile.protocol
                            .contains(
                                filter,
                                true
                            ) ||
                        profile.exitCountry
                            .contains(
                                filter,
                                true
                            ) ||
                        profile.exitIp
                            .contains(
                                filter,
                                true
                            )

                profile.id ==
                    current.profileId ||
                    (
                        sourceMatches &&
                            searchMatches
                        )
            }
            .sortedWith(
                profileComparator(
                    settings =
                        settingsRepository
                            .load(),
                    connectedId =
                        current.profileId
                )
            )
            .toList()

        adapter.submit(values, selectedId, current.profileId)
        emptyText.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE

        if (current.error.isBlank()) {
            selectedText.text = repository.find(
                current.profileId ?: selectedId
            )?.let {
                "${it.name} • ${it.server}:${it.port}"
            }.orEmpty()
        }

        renderConnectedInfo(current)
    }

    private fun renderConnectedInfo(
        snapshot: ConnectionRuntime.Snapshot
    ) {
        val connectedProfile =
            if (
                snapshot.state ==
                ConnectionState.CONNECTED
            ) {
                repository.find(
                    snapshot.profileId
                )
            } else {
                null
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
            } else {
                getString(
                    R.string.live_ping_waiting
                )
            }

        livePingText.isEnabled =
            connectedProfile != null &&
                !livePingBusy.get()

        exitInfoText.text =
            connectedProfile
                ?.let { profile ->
                    profileExitLine(profile)
                        .ifBlank {
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
            repository.find(
                snapshot.profileId
                    ?: repository.selectedId()
            ) != null
    }

    private fun showImportDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_import, null)
        val input = view.findViewById<EditText>(R.id.importInput)
        importDialogInput = input

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
                filePicker.launch(
                    arrayOf(
                        "text/plain",
                        "application/json",
                        "application/octet-stream"
                    )
                )
            }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val text = input.text.toString()
                    if (text.isNotBlank()) {
                        dialog.dismiss()
                        importText(text)
                    }
                }
        }
        dialog.setOnDismissListener { importDialogInput = null }
        dialog.show()
    }

    private fun importText(text: String) {
        val single = text.trim()
        val subscription = runCatching { URI(single) }
            .getOrNull()
            ?.let { uri ->
                uri.scheme == "https" &&
                    uri.userInfo == null &&
                    !single.contains('\n')
            } == true

        worker.execute {
            runCatching {
                if (subscription) {
                    val source = SubscriptionSource(
                        name = URI(single).host ?: "Subscription",
                        url = single
                    )
                    val result = SubscriptionManager().fetch(single)
                    val profiles = result.profiles.map {
                        it.copy(
                            subscriptionId = source.id,
                            group = source.name
                        )
                    }
                    ConfigRepository(this).replaceSubscription(
                        source.id,
                        profiles
                    )
                    source.lastSuccessAt = System.currentTimeMillis()
                    source.url = result.finalUrl
                    SubscriptionRepository(this).save(source)
                    profiles.size
                } else {
                    val profiles = ConfigParser.parseText(text)
                    ConfigRepository(this).saveAll(profiles)
                    profiles.size
                }
            }.onSuccess { count ->
                runOnUiThread {
                    toast(getString(R.string.import_success, count))
                    renderSubscriptionTabs(
                        force = true
                    )
                    refresh()
                }
            }.onFailure { error ->
                runOnUiThread {
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
                    9 -> {
                        profile.favorite = !profile.favorite
                        repository.save(profile)
                        refresh()
                    }
                    10 -> {
                        profile.enabled = !profile.enabled
                        repository.save(profile)
                        refresh()
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

    private fun testSingleProfile(
        profile: ConfigProfile
    ) {
        if (
            profile.testStatus ==
            TestStatus.TESTING
        ) {
            return
        }

        val settings =
            settingsRepository.load()

        if (
            settings.pingMethod ==
            PingMethod.XRAY_HTTP &&
            ConnectionRuntime
                .current()
                .state !in
                setOf(
                    ConnectionState
                        .DISCONNECTED,
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

        cancelPingTasks()
        val generation =
            pingGeneration
                .incrementAndGet()

        repository.update(
            profile.id
        ) {
            it.testStatus =
                TestStatus.TESTING
        }
        refresh()

        val task =
            worker.submit {
                val result =
                    runCatching {
                        pingManager.measure(
                            profile =
                                profile,
                            settings =
                                settings,
                            workDir =
                                cacheDir
                        )
                    }.getOrElse {
                        Diagnostics
                            .recordThrowable(
                                "Single ping test",
                                it
                            )
                        failedPingResult(
                            settings
                                .pingMethod,
                            it
                        )
                    }

                if (
                    pingGeneration.get() ==
                    generation
                ) {
                    applyPingResult(
                        profile.id,
                        result
                    )
                    runOnUiThread(
                        ::refresh
                    )
                }
            }

        trackPingTasks(
            listOf(task)
        )
    }

    private fun testProfiles() {
        val profiles =
            repository.all()
                .filter {
                    it.enabled
                }

        if (profiles.isEmpty()) {
            return
        }

        val settings =
            settingsRepository.load()
        val runtime =
            ConnectionRuntime.current()

        if (
            settings.pingMethod ==
            PingMethod.XRAY_HTTP &&
            runtime.state !in
                setOf(
                    ConnectionState
                        .DISCONNECTED,
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

        cancelPingTasks()
        val generation =
            pingGeneration
                .incrementAndGet()

        profiles.forEach {
            repository.update(
                it.id
            ) {
                value ->
                value.testStatus =
                    TestStatus.TESTING
            }
        }
        refresh()

        val tasks =
            pingManager.batch(
                profiles =
                    profiles,
                settings =
                    settings,
                workDir =
                    cacheDir
            ) {
                    profile,
                    result ->
                if (
                    pingGeneration.get() ==
                    generation
                ) {
                    applyPingResult(
                        profile.id,
                        result
                    )
                    runOnUiThread(
                        ::refresh
                    )
                }
            }

        trackPingTasks(tasks)
    }

    private fun selectFastestProfile() {
        val selectedMethod =
            settingsRepository
                .load()
                .pingMethod
                .name
        val fastest =
            repository.all()
                .filter {
                    it.enabled &&
                        it.testStatus ==
                        TestStatus.ALIVE &&
                        it.latencyMs !=
                        null &&
                        it.latencyMethod ==
                        selectedMethod
                }
                .minByOrNull {
                    it.latencyMs
                        ?: Long.MAX_VALUE
                }

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
                fastest.latencyMs
                    ?: 0
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

    private fun profileExitLine(profile: ConfigProfile): String = buildString {
        profile.exitFlag.takeIf(String::isNotBlank)?.let {
            append(it)
            append(' ')
        }
        profile.exitCountry.takeIf(String::isNotBlank)?.let { append(it) }
        if (profile.exitIp.isNotBlank()) {
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
        runCatching { fullJson(profile) }
            .onSuccess {
                copyToClipboard(getString(R.string.full_json), it)
            }
            .onFailure {
                toast(
                    getString(
                        R.string.json_generation_failed,
                        it.message ?: getString(R.string.unknown_error)
                    )
                )
            }
    }

    private fun shareProfileJson(profile: ConfigProfile) {
        runCatching { fullJson(profile) }
            .onSuccess { shareText(profile.name, it) }
            .onFailure {
                toast(
                    getString(
                        R.string.json_generation_failed,
                        it.message ?: getString(R.string.unknown_error)
                    )
                )
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
        return if (raw.startsWith("{")) {
            JSONObject(raw).toString(2)
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
        val port = settingsRepository.load().socksPort
        val value = buildString {
            appendLine("socks5://127.0.0.1:$port")
            append("http://127.0.0.1:$port")
        }
        copyToClipboard(getString(R.string.local_mixed_proxy), value)
    }

    private fun exportCompleteBackup() {
        val profiles = JSONArray().apply {
            repository.all().forEach { put(it.toJson()) }
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
            .put(
                "subscriptions",
                subscriptions
            )
            .toString(2)
        shareText(getString(R.string.export_backup), backup)
    }

    private fun confirmClearDeadProfiles() {
        val dead = repository.all().filter {
            it.testStatus == TestStatus.DEAD ||
                it.testStatus == TestStatus.ERROR
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
                val selected = repository.selectedId()
                dead.forEach { repository.delete(it.id) }
                if (dead.any { it.id == selected }) {
                    repository.select(null)
                }
                refresh()
            }
            .show()
    }

    private fun duplicateProfile(profile: ConfigProfile) {
        repository.save(
            profile.copy(
                id = UUID.randomUUID().toString(),
                name = getString(R.string.copy_suffix, profile.name),
                subscriptionId = null,
                exitIp = "",
                exitCountry = "",
                exitCountryCode = "",
                exitFlag = "",
                exitIsp = "",
                lastExitCheckAt = 0
            )
        )
        refresh()
    }

    private fun confirmDelete(profile: ConfigProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_config_title)
            .setMessage(getString(R.string.delete_config_message, profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.delete(profile.id)
                if (repository.selectedId() == profile.id) {
                    repository.select(null)
                }
                refresh()
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
                    ?.let {
                        profile.name = it
                        repository.save(profile)
                        refresh()
                    }
            }
            .show()
    }

    private fun toggleConnection() {
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
            when (runtime.mode ?: settingsRepository.load().mode) {
                ConnectionMode.PROXY -> {
                    startService(
                        Intent(this, ConnectionService::class.java)
                            .setAction(ConnectionService.ACTION_STOP)
                    )
                }
                ConnectionMode.VPN -> {
                    startService(
                        Intent(this, TrivoxVpnService::class.java)
                            .setAction(TrivoxVpnService.ACTION_STOP)
                    )
                }
            }
            return
        }

        val profile = repository.find(repository.selectedId())
        if (profile == null || !profile.enabled) {
            toast(getString(R.string.select_profile))
            return
        }
        if (!CoreManager(this).adapter.isAvailable()) {
            toast(getString(R.string.core_missing))
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (settingsRepository.load().mode == ConnectionMode.PROXY) {
            startProxy(profile.id)
        } else {
            requestVpn(profile.id)
        }
    }

    private fun startProxy(id: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ConnectionService::class.java)
                .setAction(ConnectionService.ACTION_START)
                .putExtra(ConnectionService.EXTRA_PROFILE_ID, id)
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
        ContextCompat.startForegroundService(
            this,
            Intent(this, TrivoxVpnService::class.java)
                .setAction(TrivoxVpnService.ACTION_START)
                .putExtra(TrivoxVpnService.EXTRA_PROFILE_ID, id)
        )
    }

    private fun renderState(snapshot: ConnectionRuntime.Snapshot) {
        stateText.setText(
            when (snapshot.state) {
                ConnectionState.DISCONNECTED -> R.string.state_disconnected
                ConnectionState.PREPARING -> R.string.state_preparing
                ConnectionState.CONNECTING -> R.string.state_connecting
                ConnectionState.CONNECTED -> R.string.state_connected
                ConnectionState.RECONNECTING -> R.string.state_reconnecting
                ConnectionState.STOPPING -> R.string.state_stopping
                ConnectionState.ERROR -> R.string.state_error
            }
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
            snapshot.state !in setOf(
                ConnectionState.PREPARING,
                ConnectionState.CONNECTING,
                ConnectionState.STOPPING
            )

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
                lastInfoProfileId =
                    snapshot.profileId
                refreshExitInfo(
                    force = false,
                    showError = false
                )
            }

            handler.postDelayed(
                durationTick,
                1_000
            )
            handler.post(
                livePingTick
            )
        } else {
            lastInfoProfileId = null
            livePingResult = null
            livePingBusy.set(false)
        }

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
                val snapshot =
                    ConnectionRuntime
                        .current()

                if (
                    snapshot.state !=
                    ConnectionState.CONNECTED
                ) {
                    return
                }

                val profile =
                    repository.find(
                        snapshot.profileId
                    ) ?: return
                val sessionId =
                    snapshot.sessionId

                if (
                    !livePingBusy
                        .compareAndSet(
                            false,
                            true
                        )
                ) {
                    handler.postDelayed(
                        this,
                        LIVE_PING_INTERVAL_MS
                    )
                    return
                }

                worker.execute {
                    val settings =
                        settingsRepository
                            .load()
                    val result =
                        runCatching {
                            when (
                                settings.pingMethod
                            ) {
                                PingMethod
                                    .TCP_CONNECT ->
                                    pingManager.tcp(
                                        profile =
                                            profile,
                                        attempts = 2,
                                        timeoutMs =
                                            2_500
                                    )

                                PingMethod
                                    .XRAY_HTTP ->
                                    pingManager
                                        .httpViaLocalProxy(
                                            settings = settings,
                                            url = LIVE_PING_URL,
                                            attempts = 3,
                                            timeoutMs = 7_000
                                        )
                            }
                        }.getOrElse {
                            Diagnostics
                                .recordThrowable(
                                    "Live ping",
                                    it
                                )
                            failedPingResult(
                                settings
                                    .pingMethod,
                                it
                            )
                        }

                    val latest =
                        ConnectionRuntime
                            .current()

                    if (
                        latest.state ==
                        ConnectionState
                            .CONNECTED &&
                        latest.sessionId ==
                        sessionId &&
                        latest.profileId ==
                        profile.id
                    ) {
                        livePingResult =
                            result

                        if (
                            result.success &&
                            result.latencyMs !=
                            null
                        ) {
                            repository.update(
                                profile.id
                            ) {
                                value ->
                                value.latencyMs =
                                    result
                                        .latencyMs
                                value
                                    .latencyJitterMs =
                                    result
                                        .jitterMs
                                value
                                    .latencySuccessRatio =
                                    result
                                        .successRatio
                                value
                                    .latencyMethod =
                                    result.method
                                value.testStatus =
                                    TestStatus.ALIVE
                                value.lastTestAt =
                                    result.timestamp
                            }
                        }
                    }

                    livePingBusy.set(false)

                    runOnUiThread {
                        refresh()

                        val current =
                            ConnectionRuntime
                                .current()

                        if (
                            current.state ==
                            ConnectionState
                                .CONNECTED &&
                            current.sessionId ==
                            sessionId
                        ) {
                            handler.postDelayed(
                                this,
                                LIVE_PING_INTERVAL_MS
                            )
                        }
                    }
                }
            }
        }

    private fun applyPingResult(
        profileId: String,
        result: PingResult
    ) {
        repository.update(
            profileId
        ) {
            profile ->
            profile.latencyMs =
                result.latencyMs
            profile.latencyJitterMs =
                result.jitterMs
            profile.latencySuccessRatio =
                result.successRatio
            profile.latencyMethod =
                result.method
            profile.lastTestAt =
                result.timestamp
            profile.testStatus =
                when {
                    result.success ->
                        TestStatus.ALIVE

                    result.errorCategory ==
                        "cancelled" ->
                        TestStatus.UNTESTED

                    result.errorCategory in
                        setOf(
                            "core_unavailable",
                            "invalid_test_url"
                        ) ->
                        TestStatus.ERROR

                    else ->
                        TestStatus.DEAD
                }
        }
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
    }

    private fun profileComparator(
        settings:
            com.trivox.client.data
                .AppSettings,
        connectedId: String?
    ): Comparator<ConfigProfile> {
        val selectedMethod =
            settings.pingMethod.name

        val base =
            when (settings.sortMode) {
                ProfileSortMode.SMART ->
                    compareByDescending<
                        ConfigProfile
                        > {
                        it.favorite
                    }
                        .thenBy {
                            if (
                                it.latencyMethod ==
                                selectedMethod
                            ) {
                                0
                            } else {
                                1
                            }
                        }
                        .thenBy {
                            it.latencyMs
                                ?: Long.MAX_VALUE
                        }
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }

                ProfileSortMode
                    .LOWEST_LATENCY ->
                    compareBy<
                        ConfigProfile
                        > {
                        if (
                            it.latencyMethod ==
                            selectedMethod
                        ) {
                            0
                        } else {
                            1
                        }
                    }
                        .thenBy {
                            it.latencyMs
                                ?: Long.MAX_VALUE
                        }
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

        return Comparator {
                left,
                right ->
            when {
                left.id ==
                    connectedId &&
                    right.id !=
                    connectedId ->
                    -1

                right.id ==
                    connectedId &&
                    left.id !=
                    connectedId ->
                    1

                else ->
                    base.compare(
                        left,
                        right
                    )
            }
        }
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
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.takeIf(String::isNotBlank)
                ?.let(::importText)
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        renderSubscriptionTabs(
            force = true
        )
        refresh()
        UpdateChecker.checkIfDue(this)
    }

    override fun onDestroy() {
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
        super.onDestroy()
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
        private const val LIVE_PING_INTERVAL_MS = 8_000L
        private const val LIVE_PING_URL =
            "http://www.google.com/gen_204"
        private const val CONNECTION_ACTION_COOLDOWN_MS = 1_500L
        private const val MAIN_UI_PREFS = "main_ui"
        private const val KEY_ACTIVE_SUBSCRIPTION =
            "active_subscription_id"
        private const val EXIT_CACHE_MS = 10 * 60 * 1_000L

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
