package com.trivox.client.ui

import android.Manifest
import android.app.AlertDialog
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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
import com.trivox.client.BuildConfig
import com.trivox.client.R
import com.trivox.client.config.ConfigParser
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManager
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.data.TestStatus
import com.trivox.client.network.ConnectionInfoManager
import com.trivox.client.network.PingManager
import com.trivox.client.network.SubscriptionManager
import com.trivox.client.service.ConnectionService
import com.trivox.client.service.NotificationSupport
import com.trivox.client.service.TrivoxVpnService
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var repository: ConfigRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var adapter: ProfileAdapter
    private lateinit var pingManager: PingManager

    private lateinit var list: RecyclerView
    private lateinit var stateText: TextView
    private lateinit var durationText: TextView
    private lateinit var selectedText: TextView
    private lateinit var livePingText: TextView
    private lateinit var exitInfoText: TextView
    private lateinit var emptyText: TextView
    private lateinit var connectButton: Button
    private lateinit var modeSpinner: Spinner
    private lateinit var refreshExitButton: Button
    private lateinit var copySummaryButton: Button

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val livePingBusy = AtomicBoolean(false)

    private var filter = ""
    private var importDialogInput: EditText? = null
    private var pendingVpnProfile: String? = null
    private var lastInfoProfileId: String? = null

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
        pingManager = PingManager(CoreManager(this).adapter)

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
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.subtitle = getString(
            R.string.version_short,
            BuildConfig.VERSION_NAME,
            BuildConfig.BUILD_NUMBER
        )
        toolbar.menu.add(0, MENU_VIEW, 0, R.string.grid).setShowAsAction(2)
        toolbar.menu.add(0, MENU_FASTEST, 1, R.string.select_fastest)
        toolbar.menu.add(0, MENU_SETTINGS, 2, R.string.settings)
        toolbar.menu.add(0, MENU_ROUTING, 3, R.string.app_routing)
        toolbar.menu.add(0, MENU_DIAGNOSTICS, 4, R.string.diagnostics)

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
            .setOnClickListener { showImportDialog() }
        findViewById<Button>(R.id.testButton)
            .setOnClickListener { testProfiles() }
        connectButton.setOnClickListener { toggleConnection() }
        refreshExitButton.setOnClickListener {
            refreshExitInfo(force = true, showError = true)
        }
        copySummaryButton.setOnClickListener { copyConnectionSummary() }
    }

    private fun compactAdapter(values: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item, values).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun refresh() {
        val selectedId = repository.selectedId()
        val current = ConnectionRuntime.current()
        val values = repository.all()
            .asSequence()
            .filter { profile ->
                filter.isBlank() ||
                    profile.name.contains(filter, true) ||
                    profile.server.contains(filter, true) ||
                    profile.protocol.contains(filter, true) ||
                    profile.exitCountry.contains(filter, true) ||
                    profile.exitIp.contains(filter, true)
            }
            .sortedWith(
                compareByDescending<ConfigProfile> { it.favorite }
                    .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                    .thenBy { it.name.lowercase() }
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

    private fun renderConnectedInfo(snapshot: ConnectionRuntime.Snapshot) {
        val profile = repository.find(snapshot.profileId)
        val connected = snapshot.state == ConnectionState.CONNECTED && profile != null

        livePingText.text =
            if (connected) {
                profile?.latencyMs?.let {
                    getString(R.string.live_ping_value, it)
                } ?: getString(R.string.live_ping_waiting)
            } else {
                getString(R.string.live_ping_off)
            }

        exitInfoText.text =
            if (connected && profile != null) {
                profileExitLine(profile).ifBlank {
                    getString(R.string.exit_info_waiting)
                }
            } else {
                getString(R.string.exit_info_off)
            }

        refreshExitButton.isEnabled = connected
        copySummaryButton.isEnabled =
            repository.find(snapshot.profileId ?: repository.selectedId()) != null
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
        startActivity(
            Intent(this, ConfigEditorActivity::class.java)
                .putExtra(ConfigEditorActivity.EXTRA_PROFILE_ID, profile.id)
        )
    }

    private fun testSingleProfile(profile: ConfigProfile) {
        if (profile.testStatus == TestStatus.TESTING) return

        profile.testStatus = TestStatus.TESTING
        repository.save(profile)
        refresh()

        worker.execute {
            val result = pingManager.tcp(profile, attempts = 1, timeoutMs = 4_000)
            val latest = ConfigRepository(this).find(profile.id) ?: return@execute
            latest.latencyMs = result.latencyMs
            latest.testStatus =
                if (result.success) TestStatus.ALIVE else TestStatus.DEAD
            latest.lastTestAt = result.timestamp
            ConfigRepository(this).save(latest)
            runOnUiThread(::refresh)
        }
    }

    private fun testProfiles() {
        val profiles = repository.all().filter { it.enabled }
        if (profiles.isEmpty()) return

        profiles.forEach {
            it.testStatus = TestStatus.TESTING
            repository.save(it)
        }
        refresh()

        pingManager.batchTcp(
            profiles,
            settingsRepository.load().testAttempts
        ) { profile, result ->
            val latest = ConfigRepository(this).find(profile.id) ?: return@batchTcp
            latest.latencyMs = result.latencyMs
            latest.testStatus =
                if (result.success) TestStatus.ALIVE else TestStatus.DEAD
            latest.lastTestAt = result.timestamp
            ConfigRepository(this).save(latest)
            runOnUiThread(::refresh)
        }
    }

    private fun selectFastestProfile() {
        val fastest = repository.all()
            .filter {
                it.enabled &&
                    it.testStatus == TestStatus.ALIVE &&
                    it.latencyMs != null
            }
            .minByOrNull { it.latencyMs ?: Long.MAX_VALUE }

        if (fastest == null) {
            toast(getString(R.string.no_ping_results))
            return
        }

        repository.select(fastest.id)
        refresh()
        toast(
            getString(
                R.string.fastest_selected,
                fastest.name,
                fastest.latencyMs ?: 0
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
                appendLine(getString(R.string.live_ping_value, it))
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
                appendLine(getString(R.string.live_ping_value, it))
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
        if (
            ConnectionRuntime.current().state !in setOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR
            )
        ) {
            when (settingsRepository.load().mode) {
                ConnectionMode.PROXY -> {
                    startService(
                        Intent(
                            this,
                            ConnectionService::class.java
                        ).setAction(
                            ConnectionService.ACTION_STOP
                        )
                    )
                }

                ConnectionMode.VPN -> {
                    startService(
                        Intent(
                            this,
                            TrivoxVpnService::class.java
                        ).setAction(
                            TrivoxVpnService.ACTION_STOP
                        )
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

        if (snapshot.error.isNotBlank()) {
            selectedText.text = snapshot.error
        }

        updateDuration(snapshot)
        handler.removeCallbacks(durationTick)
        handler.removeCallbacks(livePingTick)

        if (snapshot.state == ConnectionState.CONNECTED) {
            handler.postDelayed(durationTick, 1_000)
            handler.post(livePingTick)

            if (lastInfoProfileId != snapshot.profileId) {
                lastInfoProfileId = snapshot.profileId
                refreshExitInfo(force = false, showError = false)
            }
        } else {
            lastInfoProfileId = null
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

    private val livePingTick = object : Runnable {
        override fun run() {
            val snapshot = ConnectionRuntime.current()
            if (snapshot.state != ConnectionState.CONNECTED) return

            val profile = repository.find(snapshot.profileId)
            if (profile == null) return

            if (!livePingBusy.compareAndSet(false, true)) {
                handler.postDelayed(this, LIVE_PING_INTERVAL_MS)
                return
            }

            worker.execute {
                try {
                    val result = pingManager.tcp(
                        profile,
                        attempts = 1,
                        timeoutMs = 2_500
                    )
                    val latest = ConfigRepository(this@MainActivity)
                        .find(profile.id)
                    if (latest != null) {
                        latest.latencyMs = result.latencyMs
                        latest.testStatus =
                            if (result.success) TestStatus.ALIVE else TestStatus.DEAD
                        latest.lastTestAt = result.timestamp
                        ConfigRepository(this@MainActivity).save(latest)
                    }
                } finally {
                    livePingBusy.set(false)
                    runOnUiThread {
                        refresh()
                        if (
                            ConnectionRuntime.current().state ==
                            ConnectionState.CONNECTED
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
        refresh()
    }

    override fun onDestroy() {
        ConnectionRuntime.removeListener(runtimeListener)
        handler.removeCallbacksAndMessages(null)
        pingManager.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val MENU_VIEW = 100
        private const val MENU_FASTEST = 101
        private const val MENU_SETTINGS = 102
        private const val MENU_ROUTING = 103
        private const val MENU_DIAGNOSTICS = 104
        private const val LIVE_PING_INTERVAL_MS = 3_000L
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
