package com.trivox.client.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.trivox.client.R
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.SubscriptionKind
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.network.SubscriptionManager
import com.trivox.client.network.SubscriptionProfileLoader
import com.trivox.client.network.isSubscriptionCancellation
import com.trivox.client.util.SecretStore
import com.trivox.client.util.Diagnostics
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class SubscriptionManagementActivity : ThemedActivity() {
    private lateinit var sourceRepository:
        SubscriptionRepository
    private lateinit var configRepository:
        ConfigRepository
    private lateinit var listContainer:
        LinearLayout
    private lateinit var emptyText:
        TextView
    private lateinit var progressText:
        TextView
    private lateinit var headerSummary:
        TextView
    private lateinit var addButton:
        Button
    private lateinit var updateAllButton:
        Button

    private val worker =
        Executors.newSingleThreadExecutor()
    private val operationBusy =
        AtomicBoolean(false)
    private val destroyed =
        AtomicBoolean(false)

    @Volatile
    private var activeTask:
        Future<*>? = null

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )
        setContentView(
            R.layout
                .activity_subscriptions
        )

        sourceRepository =
            SubscriptionRepository(this)
        configRepository =
            ConfigRepository(this)

        findViewById<Toolbar>(
            R.id.toolbar
        ).setNavigationOnClickListener {
            finish()
        }

        listContainer =
            findViewById(
                R.id.subscriptionList
            )
        emptyText =
            findViewById(
                R.id.subscriptionEmpty
            )
        progressText =
            findViewById(
                R.id.subscriptionProgress
            )
        headerSummary =
            findViewById(
                R.id.subscriptionHeaderSummary
            )
        addButton =
            findViewById(
                R.id.addSubscriptionButton
            )
        updateAllButton =
            findViewById(
                R.id.updateAllSubscriptionsButton
            )

        addButton.setOnClickListener {
            showAddSourceOptions()
        }
        updateAllButton
            .setOnClickListener {
                updateAllEnabled()
            }

        render()
        handleLaunchIntent()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun handleLaunchIntent() {
        val sourceId =
            intent.getStringExtra(
                EXTRA_SOURCE_ID
            )
        val kindValue =
            intent.getStringExtra(
                EXTRA_ADD_KIND
            )

        intent.removeExtra(EXTRA_SOURCE_ID)
        intent.removeExtra(EXTRA_ADD_KIND)

        if (!sourceId.isNullOrBlank()) {
            SubscriptionRepository(this)
                .find(sourceId)
                ?.let { source ->
                    if (
                        source.kind ==
                        SubscriptionKind.NORDVPN
                    ) {
                        showNordVpnEditor(source)
                    } else {
                        showEditor(source)
                    }
                }
            return
        }

        if (kindValue == null) {
            return
        }

        when (
            SubscriptionKind.fromStored(
                kindValue
            )
        ) {
            SubscriptionKind.NORDVPN ->
                showNordVpnEditor(null)
            SubscriptionKind.URL ->
                showEditor(null)
        }
    }

    private fun render() {
        val sources =
            sourceRepository
                .all()
                .sortedBy {
                    it.name.lowercase()
                }

        listContainer
            .removeAllViews()

        emptyText.visibility =
            if (sources.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val enabledCount =
            sources.count {
                it.enabled
            }
        val profileCount =
            sources.sumOf {
                source ->
                configRepository
                    .countForSubscription(
                        source.id
                    )
            }

        headerSummary.text =
            getString(
                R.string
                    .subscription_header_summary,
                sources.size,
                enabledCount,
                profileCount
            )

        sources.forEach { source ->
            val row =
                layoutInflater.inflate(
                    R.layout
                        .row_subscription,
                    listContainer,
                    false
                )

            val name =
                row.findViewById<TextView>(
                    R.id.subscriptionName
                )
            val kindBadge =
                row.findViewById<TextView>(
                    R.id.subscriptionKindBadge
                )
            val statusBadge =
                row.findViewById<TextView>(
                    R.id.subscriptionStatusBadge
                )
            val url =
                row.findViewById<TextView>(
                    R.id.subscriptionUrl
                )
            val summary =
                row.findViewById<TextView>(
                    R.id.subscriptionSummary
                )
            val error =
                row.findViewById<TextView>(
                    R.id.subscriptionError
                )
            val update =
                row.findViewById<Button>(
                    R.id.subscriptionUpdateButton
                )
            val edit =
                row.findViewById<Button>(
                    R.id.subscriptionEditButton
                )
            val toggle =
                row.findViewById<Button>(
                    R.id.subscriptionEnableButton
                )
            val delete =
                row.findViewById<Button>(
                    R.id.subscriptionDeleteButton
                )

            val count =
                configRepository
                    .countForSubscription(
                        source.id
                    )

            name.text = source.name
            kindBadge.text =
                getString(
                    if (
                        source.kind ==
                        SubscriptionKind.NORDVPN
                    ) {
                        R.string
                            .nordvpn_subscription_short
                    } else {
                        R.string
                            .url_subscription_short
                    }
                )
            statusBadge.setText(
                if (source.enabled) {
                    R.string
                        .subscription_enabled
                } else {
                    R.string
                        .subscription_disabled
                }
            )
            statusBadge.setTextColor(
                androidx.core.content.ContextCompat
                    .getColor(
                        this,
                        if (source.enabled) {
                            R.color.green
                        } else {
                            R.color.muted
                        }
                    )
            )
            url.text =
                if (
                    source.kind ==
                    SubscriptionKind
                        .NORDVPN
                ) {
                    getString(
                        R.string
                            .nordvpn_source_label
                    )
                } else {
                    source.url
                }
            summary.text =
                getString(
                    R.string
                        .subscription_row_summary,
                    count,
                    if (source.enabled) {
                        getString(
                            R.string
                                .subscription_enabled
                        )
                    } else {
                        getString(
                            R.string
                                .subscription_disabled
                        )
                    },
                    formattedLastUpdate(
                        source
                            .lastSuccessAt
                    )
                )

            if (
                source.lastError
                    .isBlank()
            ) {
                error.visibility =
                    View.GONE
            } else {
                val partialSuccess =
                    source.lastSuccessAt >
                        0L &&
                        count > 0

                error.visibility =
                    View.VISIBLE
                error.text =
                    getString(
                        if (partialSuccess) {
                            R.string
                                .subscription_warning_format
                        } else {
                            R.string
                                .subscription_error_format
                        },
                        source.lastError
                    )
                error.setTextColor(
                    androidx.core.content
                        .ContextCompat
                        .getColor(
                            this,
                            if (partialSuccess) {
                                R.color.blue
                            } else {
                                R.color.red
                            }
                        )
                )
            }

            update.isEnabled =
                !operationBusy.get()
            edit.isEnabled =
                !operationBusy.get()
            toggle.isEnabled =
                !operationBusy.get()
            delete.isEnabled =
                !operationBusy.get()

            toggle.setText(
                if (source.enabled) {
                    R.string.disable
                } else {
                    R.string.enable
                }
            )

            update.setOnClickListener {
                updateSources(
                    listOf(source)
                )
            }
            edit.setOnClickListener {
                if (
                    source.kind ==
                    SubscriptionKind
                        .NORDVPN
                ) {
                    showNordVpnEditor(
                        source
                    )
                } else {
                    showEditor(
                        source
                    )
                }
            }
            toggle.setOnClickListener {
                sourceRepository
                    .update(source.id) {
                        it.enabled =
                            !it.enabled
                    }
                render()
            }
            delete.setOnClickListener {
                confirmDelete(source)
            }

            listContainer.addView(row)
        }

        addButton.isEnabled =
            !operationBusy.get()
        updateAllButton.isEnabled =
            !operationBusy.get() &&
                sources.any {
                    it.enabled
                }
    }

    private fun showAddSourceOptions() {
        val options =
            arrayOf(
                getString(
                    R.string
                        .url_subscription
                ),
                getString(
                    R.string
                        .nordvpn_subscription
                )
            )

        AlertDialog.Builder(this)
            .setTitle(
                R.string
                    .add_subscription_type
            )
            .setItems(
                options
            ) {
                    _,
                    position ->
                if (position == 0) {
                    showEditor(null)
                } else {
                    showNordVpnEditor(
                        null
                    )
                }
            }
            .show()
    }

    private fun showNordVpnEditor(
        source: SubscriptionSource?
    ) {
        if (operationBusy.get()) {
            return
        }

        val padding =
            (
                20 *
                    resources
                        .displayMetrics
                        .density
                ).toInt()
        val container =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    padding,
                    padding / 2,
                    padding,
                    0
                )
            }
        val nameInput =
            EditText(this).apply {
                hint =
                    getString(
                        R.string
                            .subscription_name
                    )
                setText(
                    source?.name
                        ?: getString(
                            R.string
                                .nordvpn_subscription
                        )
                )
            }
        val tokenInput =
            EditText(this).apply {
                hint =
                    if (source == null) {
                        getString(
                            R.string
                                .nordvpn_token
                        )
                    } else {
                        getString(
                            R.string
                                .nordvpn_token_keep
                        )
                    }
                inputType =
                    InputType
                        .TYPE_CLASS_TEXT or
                        InputType
                            .TYPE_TEXT_VARIATION_PASSWORD
            }
        val enabled =
            CheckBox(this).apply {
                text =
                    getString(
                        R.string
                            .subscription_enabled
                    )
                isChecked =
                    source?.enabled
                        ?: true
            }

        container.addView(
            nameInput
        )
        container.addView(
            tokenInput
        )
        container.addView(
            enabled
        )

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    R.string
                        .nordvpn_subscription
                )
                .setView(
                    container
                )
                .setNegativeButton(
                    R.string.cancel,
                    null
                )
                .setPositiveButton(
                    R.string.save,
                    null
                )
                .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog
                    .BUTTON_POSITIVE
            ).setOnClickListener {
                val name =
                    nameInput.text
                        .toString()
                        .trim()
                val token =
                    tokenInput.text
                        .toString()
                        .trim()

                if (
                    name.isBlank() ||
                    (
                        source == null &&
                        token.isBlank()
                        )
                ) {
                    toast(
                        getString(
                            R.string
                                .invalid_nordvpn_token
                        )
                    )
                    return@setOnClickListener
                }

                val id =
                    source?.id
                        ?: UUID
                            .randomUUID()
                            .toString()
                val secretAlias =
                    source
                        ?.secretAlias
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "nordvpn_$id"

                if (token.isNotBlank()) {
                    runCatching {
                        SecretStore.put(
                            this,
                            secretAlias,
                            token
                        )
                    }.onFailure {
                        toast(
                            getString(
                                R.string
                                    .secure_store_failed
                            )
                        )
                        return@setOnClickListener
                    }
                }

                val saved =
                    SubscriptionSource(
                        id = id,
                        name = name,
                        url =
                            "nordvpn://" +
                                "all-countries",
                        enabled =
                            enabled
                                .isChecked,
                        lastSuccessAt =
                            source
                                ?.lastSuccessAt
                                ?: 0L,
                        lastError =
                            source
                                ?.lastError
                                .orEmpty(),
                        kind =
                            SubscriptionKind
                                .NORDVPN,
                        secretAlias =
                            secretAlias
                    )

                if (
                    source != null &&
                    source.name != name
                ) {
                    configRepository
                        .renameSubscription(
                            source.id,
                            name
                        )
                }

                sourceRepository
                    .save(saved)
                dialog.dismiss()
                render()

                if (
                    source == null ||
                    token.isNotBlank()
                ) {
                    updateSources(
                        listOf(saved)
                    )
                }
            }
        }

        dialog.show()
    }

    private fun showEditor(
        source: SubscriptionSource?
    ) {
        if (operationBusy.get()) {
            return
        }

        val view =
            layoutInflater.inflate(
                R.layout
                    .dialog_subscription,
                null
            )
        val nameInput =
            view.findViewById<EditText>(
                R.id.subscriptionNameInput
            )
        val urlInput =
            view.findViewById<EditText>(
                R.id.subscriptionUrlInput
            )
        val enabled =
            view.findViewById<CheckBox>(
                R.id.subscriptionEnabledCheck
            )

        nameInput.setText(
            source?.name.orEmpty()
        )
        urlInput.setText(
            source?.url.orEmpty()
        )
        enabled.isChecked =
            source?.enabled ?: true

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    if (source == null) {
                        R.string
                            .add_subscription
                    } else {
                        R.string
                            .edit_subscription
                    }
                )
                .setView(view)
                .setNegativeButton(
                    R.string.cancel,
                    null
                )
                .setPositiveButton(
                    R.string.save,
                    null
                )
                .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog
                    .BUTTON_POSITIVE
            ).setOnClickListener {
                val name =
                    nameInput.text
                        .toString()
                        .trim()
                val url =
                    urlInput.text
                        .toString()
                        .trim()

                if (
                    name.isBlank() ||
                    !validSubscriptionUrl(
                        url
                    )
                ) {
                    toast(
                        getString(
                            R.string
                                .invalid_subscription
                        )
                    )
                    return@setOnClickListener
                }

                val saved =
                    if (source == null) {
                        SubscriptionSource(
                            name = name,
                            url = url,
                            enabled =
                                enabled
                                    .isChecked
                        )
                    } else {
                        source.copy(
                            name = name,
                            url = url,
                            enabled =
                                enabled
                                    .isChecked
                        )
                    }

                if (
                    source != null &&
                    source.name != name
                ) {
                    configRepository
                        .renameSubscription(
                            source.id,
                            name
                        )
                }

                sourceRepository
                    .save(saved)
                dialog.dismiss()
                render()

                if (source == null) {
                    updateSources(
                        listOf(saved)
                    )
                }
            }
        }

        dialog.show()
    }

    private fun updateAllEnabled() {
        val enabled =
            sourceRepository
                .all()
                .filter {
                    it.enabled
                }

        if (enabled.isEmpty()) {
            toast(
                getString(
                    R.string
                        .no_enabled_subscriptions
                )
            )
            return
        }

        updateSources(enabled)
    }

    private fun updateSources(
        sources:
            List<SubscriptionSource>
    ) {
        if (
            !operationBusy.compareAndSet(
                false,
                true
            )
        ) {
            toast(
                getString(
                    R.string
                        .subscription_update_busy
                )
            )
            return
        }

        progressText.visibility =
            View.VISIBLE
        progressText.setText(
            R.string.subscription_updating
        )
        render()

        activeTask =
            worker.submit {
                var updatedProfiles = 0
                var failures = 0
                var partial = 0
                var cancelled = false

                sources.forEachIndexed {
                        index,
                        source ->
                    if (
                        Thread.currentThread()
                            .isInterrupted
                    ) {
                        cancelled = true
                        return@forEachIndexed
                    }

                    runOnUiThreadIfAlive {
                        progressText.text =
                            getString(
                                R.string
                                    .subscription_progress_format,
                                index + 1,
                                sources.size,
                                source.name
                            )
                    }

                    try {
                        val result =
                            updateOne(source)

                        updatedProfiles +=
                            result.first

                        if (result.second) {
                            partial += 1
                        }
                    } catch (
                        error: Throwable
                    ) {
                        if (
                            error
                                .isSubscriptionCancellation()
                        ) {
                            cancelled = true
                            return@forEachIndexed
                        }

                        failures += 1
                        Diagnostics
                            .recordThrowable(
                                "Subscription update",
                                error
                            )
                    }
                }

                operationBusy.set(false)
                activeTask = null

                runOnUiThreadIfAlive {
                    progressText.visibility =
                        View.GONE
                    render()

                    if (!cancelled) {
                        toast(
                            getString(
                                R.string
                                    .subscription_update_result_detailed,
                                updatedProfiles,
                                partial,
                                failures
                            )
                        )
                    }
                }
            }
    }

    private fun updateOne(
        requested:
            SubscriptionSource
    ): Pair<Int, Boolean> {
        val latest =
            sourceRepository
                .find(requested.id)
                ?: return 0 to false

        try {
            val result =
                SubscriptionProfileLoader
                    .load(
                        applicationContext,
                        latest
                    )
            val profiles =
                result.profiles.map {
                    it.copy(
                        subscriptionId =
                            latest.id,
                        group =
                            latest.name
                    )
                }

            if (result.complete) {
                configRepository
                    .replaceSubscription(
                        latest.id,
                        profiles
                    )
            } else {
                configRepository
                    .mergeSubscription(
                        latest.id,
                        profiles
                    )
            }

            if (
                latest.kind ==
                SubscriptionKind.URL
            ) {
                latest.url =
                    result.finalUrl
            }

            latest.lastSuccessAt =
                System.currentTimeMillis()
            latest.lastError =
                result.warnings
                    .take(3)
                    .joinToString(" • ")
            sourceRepository.save(latest)

            return profiles.size to
                !result.complete
        } catch (
            error: Throwable
        ) {
            if (
                error
                    .isSubscriptionCancellation()
            ) {
                throw error
            }

            latest.lastError =
                (
                    error.message
                        ?: error
                            .javaClass
                            .simpleName
                    )
                    .replace(
                        Regex("\\s+"),
                        " "
                    )
                    .take(280)
            sourceRepository.save(latest)
            throw error
        }
    }

    private fun runOnUiThreadIfAlive(
        action: () -> Unit
    ) {
        if (destroyed.get()) {
            return
        }

        runOnUiThread {
            if (
                !destroyed.get() &&
                !isFinishing &&
                !isDestroyed
            ) {
                action()
            }
        }
    }

    private fun confirmDelete(
        source: SubscriptionSource
    ) {
        val connected =
            ConnectionRuntime.current()
        val connectedProfile =
            configRepository.find(
                connected.profileId
            )

        if (
            connected.state !in
            setOf(
                ConnectionState
                    .DISCONNECTED,
                ConnectionState.ERROR
            ) &&
            connectedProfile
                ?.subscriptionId ==
            source.id
        ) {
            toast(
                getString(
                    R.string
                        .disconnect_subscription_first
                )
            )
            return
        }

        val count =
            configRepository
                .countForSubscription(
                    source.id
                )

        AlertDialog.Builder(this)
            .setTitle(
                R.string
                    .delete_subscription
            )
            .setMessage(
                getString(
                    R.string
                        .delete_subscription_confirm,
                    source.name,
                    count
                )
            )
            .setNegativeButton(
                R.string.cancel,
                null
            )
            .setPositiveButton(
                R.string.delete
            ) { _, _ ->
                configRepository
                    .deleteSubscription(
                        source.id
                    )
                sourceRepository
                    .delete(
                        source.id
                    )
                SecretStore.delete(
                    this,
                    source.secretAlias
                )
                render()
            }
            .show()
    }

    private fun formattedLastUpdate(
        timestamp: Long
    ): String =
        if (timestamp <= 0L) {
            getString(
                R.string
                    .subscription_never_updated
            )
        } else {
            DateFormat
                .getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT
                )
                .format(Date(timestamp))
        }

    private fun validSubscriptionUrl(
        value: String
    ): Boolean =
        runCatching {
            SubscriptionManager
                .normalizeUrl(value)
        }.isSuccess

    private fun toast(
        message: String
    ) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        const val EXTRA_SOURCE_ID =
            "subscription_source_id"
        const val EXTRA_ADD_KIND =
            "subscription_add_kind"
    }

    override fun onDestroy() {
        destroyed.set(true)
        activeTask?.cancel(true)
        activeTask = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
