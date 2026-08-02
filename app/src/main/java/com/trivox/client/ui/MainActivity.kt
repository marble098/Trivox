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
import com.trivox.client.network.PingManager
import com.trivox.client.network.SubscriptionManager
import com.trivox.client.service.ConnectionService
import com.trivox.client.service.NotificationSupport
import com.trivox.client.service.TrivoxVpnService
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var repository: ConfigRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var adapter: ProfileAdapter
    private lateinit var list: RecyclerView
    private lateinit var stateText: TextView
    private lateinit var durationText: TextView
    private lateinit var selectedText: TextView
    private lateinit var emptyText: TextView
    private lateinit var connectButton: Button
    private lateinit var modeSpinner: Spinner

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var filter = ""
    private var importDialogInput: EditText? = null
    private var pendingVpnProfile: String? = null

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
        emptyText = findViewById(R.id.emptyText)
        connectButton = findViewById(R.id.connectButton)
        modeSpinner = findViewById(R.id.modeSpinner)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)

        toolbar.menu.add(0, MENU_VIEW, 0, R.string.grid).setShowAsAction(2)
        toolbar.menu.add(0, MENU_SETTINGS, 1, R.string.settings)
        toolbar.menu.add(0, MENU_ROUTING, 2, R.string.app_routing)
        toolbar.menu.add(0, MENU_DIAGNOSTICS, 3, R.string.diagnostics)

        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_VIEW -> {
                    val settings = settingsRepository.load()
                    settings.gridMode = !settings.gridMode
                    settingsRepository.save(settings)
                    applyLayout()
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
            onAction = ::showActions
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
        val modes = arrayOf(
            getString(R.string.proxy_mode),
            getString(R.string.vpn_mode)
        )

        modeSpinner.adapter = compactAdapter(modes)
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
    }

    private fun compactAdapter(values: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item, values).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun refresh() {
        val allProfiles = repository.all()
        val selectedId = repository.selectedId()
        val current = ConnectionRuntime.current()

        val values =
            allProfiles
                .asSequence()
                .filter { profile ->
                    filter.isBlank() ||
                        profile.name.contains(filter, true) ||
                        profile.server.contains(filter, true) ||
                        profile.protocol.contains(filter, true)
                }
                .sortedWith(
                    compareByDescending<ConfigProfile> { it.favorite }
                        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                        .thenBy { it.name.lowercase() }
                )
                .toList()

        adapter.submit(values, selectedId, current.profileId)
        emptyText.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE

        selectedText.text =
            allProfiles
                .firstOrNull { it.id == selectedId }
                ?.let { "${it.name} • ${it.server}:${it.port}" }
                .orEmpty()
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

        val dialog =
            AlertDialog.Builder(this)
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

        val subscription =
            runCatching { URI(single) }
                .getOrNull()
                ?.let { uri ->
                    uri.scheme == "https" &&
                        uri.userInfo == null &&
                        !single.contains('\n')
                } == true

        worker.execute {
            runCatching {
                if (subscription) {
                    val source =
                        SubscriptionSource(
                            name = URI(single).host ?: "Subscription",
                            url = single
                        )

                    val result = SubscriptionManager().fetch(single)
                    val profiles =
                        result.profiles.map {
                            it.copy(
                                subscriptionId = source.id,
                                group = source.name
                            )
                        }

                    ConfigRepository(this)
                        .replaceSubscription(source.id, profiles)

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
        val labels =
            arrayOf(
                getString(R.string.edit_config),
                getString(R.string.copy_link),
                getString(R.string.share_link),
                getString(R.string.copy_json),
                getString(R.string.share_json),
                getString(R.string.rename),
                getString(R.string.duplicate),
                getString(
                    if (profile.favorite) {
                        R.string.not_favorite
                    } else {
                        R.string.favorite
                    }
                ),
                getString(
                    if (profile.enabled) {
                        R.string.disabled
                    } else {
                        R.string.enabled
                    }
                ),
                getString(R.string.delete)
            )

        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> editProfile(profile)
                    1 -> copyProfileLink(profile)
                    2 -> shareProfileLink(profile)
                    3 -> copyProfileJson(profile)
                    4 -> shareProfileJson(profile)
                    5 -> rename(profile)
                    6 -> duplicateProfile(profile)
                    7 -> {
                        profile.favorite = !profile.favorite
                        repository.save(profile)
                        refresh()
                    }

                    8 -> {
                        profile.enabled = !profile.enabled
                        repository.save(profile)
                        refresh()
                    }

                    9 -> confirmDelete(profile)
                }
            }
            .show()
    }

    private fun editProfile(profile: ConfigProfile) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_config, null)
        val input = view.findViewById<EditText>(R.id.editConfigInput)
        input.setText(profile.raw)

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(R.string.edit_config)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val edited = input.text.toString().trim()

                    val parsed =
                        runCatching {
                            ConfigParser.parseText(edited)
                                .singleOrNull()
                                ?: throw IllegalArgumentException(
                                    getString(R.string.single_config_required)
                                )
                        }.getOrElse { error ->
                            input.error =
                                error.message ?: getString(R.string.invalid_config)
                            return@setOnClickListener
                        }

                    val replacement =
                        parsed.copy(
                            id = profile.id,
                            enabled = profile.enabled,
                            favorite = profile.favorite,
                            group = profile.group,
                            subscriptionId = null,
                            latencyMs = null,
                            testStatus = TestStatus.UNTESTED,
                            lastTestAt = 0,
                            lastSessionMs = profile.lastSessionMs,
                            cumulativeSessionMs = profile.cumulativeSessionMs
                        )

                    repository.save(replacement)
                    dialog.dismiss()
                    toast(getString(R.string.config_updated))
                    refresh()
                }
        }

        dialog.show()
    }

    private fun copyProfileLink(profile: ConfigProfile) {
        val link = shareLink(profile)

        if (link == null) {
            toast(getString(R.string.link_unavailable))
            return
        }

        copyToClipboard(
            label = getString(R.string.config_link),
            text = link
        )
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
                copyToClipboard(
                    label = getString(R.string.full_json),
                    text = it
                )
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

        clipboard.setPrimaryClip(
            ClipData.newPlainText(label, text)
        )

        toast(getString(R.string.copied))
    }

    private fun shareText(name: String, text: String) {
        val intent =
            Intent(Intent.ACTION_SEND)
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
                subscriptionId = null
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
        val input =
            EditText(this).apply {
                setText(profile.name)
                selectAll()
                textDirection = View.TEXT_DIRECTION_LOCALE
            }

        AlertDialog.Builder(this)
            .setTitle(R.string.new_name)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                input.text
                    .toString()
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let {
                        profile.name = it
                        repository.save(profile)
                        refresh()
                    }
            }
            .show()
    }

    private fun testProfiles() {
        val profiles = repository.all().filter { it.enabled }
        if (profiles.isEmpty()) return

        profiles.forEach {
            it.testStatus = TestStatus.TESTING
            repository.save(it)
        }
        refresh()

        val ping = PingManager(CoreManager(this).adapter)

        ping.batchTcp(
            profiles,
            settingsRepository.load().testAttempts
        ) { profile, result ->
            profile.latencyMs = result.latencyMs
            profile.testStatus =
                if (result.success) TestStatus.ALIVE else TestStatus.DEAD
            profile.lastTestAt = result.timestamp
            repository.save(profile)
            runOnUiThread(::refresh)
        }

        worker.execute {
            while (
                repository.all().any {
                    it.testStatus == TestStatus.TESTING
                }
            ) {
                Thread.sleep(100)
            }
            ping.close()
        }
    }

    private fun toggleConnection() {
        if (
            ConnectionRuntime.current().state !in
                setOf(
                    ConnectionState.DISCONNECTED,
                    ConnectionState.ERROR
                )
        ) {
            startService(
                Intent(this, ConnectionService::class.java)
                    .setAction(ConnectionService.ACTION_STOP)
            )
            startService(
                Intent(this, TrivoxVpnService::class.java)
                    .setAction(TrivoxVpnService.ACTION_STOP)
            )
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
            notificationPermission.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
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
                snapshot.state in
                    setOf(
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
        refresh()

        handler.removeCallbacks(durationTick)
        if (snapshot.state == ConnectionState.CONNECTED) {
            handler.postDelayed(durationTick, 1000)
        }
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

    private val durationTick =
        object : Runnable {
            override fun run() {
                val current = ConnectionRuntime.current()
                updateDuration(current)

                if (current.state == ConnectionState.CONNECTED) {
                    handler.postDelayed(this, 1000)
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
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val MENU_VIEW = 100
        private const val MENU_SETTINGS = 101
        private const val MENU_ROUTING = 102
        private const val MENU_DIAGNOSTICS = 103

        private val SHARE_SCHEMES =
            setOf(
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
