package com.trivox.client.ui

import android.os.Bundle
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
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.network.SubscriptionManager
import com.trivox.client.util.Diagnostics
import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
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
    private lateinit var addButton:
        Button
    private lateinit var updateAllButton:
        Button

    private val worker =
        Executors.newSingleThreadExecutor()
    private val operationBusy =
        AtomicBoolean(false)

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
        addButton =
            findViewById(
                R.id.addSubscriptionButton
            )
        updateAllButton =
            findViewById(
                R.id.updateAllSubscriptionsButton
            )

        addButton.setOnClickListener {
            showEditor(null)
        }
        updateAllButton
            .setOnClickListener {
                updateAllEnabled()
            }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
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
            url.text = source.url
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
                error.visibility =
                    View.VISIBLE
                error.text =
                    getString(
                        R.string
                            .subscription_error_format,
                        source.lastError
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
                showEditor(source)
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
            R.string
                .subscription_updating
        )
        render()

        worker.execute {
            var updatedProfiles = 0
            var failures = 0

            sources.forEach { source ->
                runCatching {
                    updatedProfiles +=
                        updateOne(source)
                }.onFailure {
                    failures += 1
                    Diagnostics
                        .recordThrowable(
                            "Subscription update",
                            it
                        )
                }
            }

            operationBusy.set(false)

            runOnUiThread {
                progressText.visibility =
                    View.GONE
                render()

                toast(
                    getString(
                        R.string
                            .subscription_update_result,
                        updatedProfiles,
                        failures
                    )
                )
            }
        }
    }

    private fun updateOne(
        requested:
            SubscriptionSource
    ): Int {
        val latest =
            sourceRepository
                .find(requested.id)
                ?: return 0

        return try {
            val result =
                SubscriptionManager()
                    .fetch(
                        latest.url
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

            configRepository
                .replaceSubscription(
                    latest.id,
                    profiles
                )

            latest.lastSuccessAt =
                System.currentTimeMillis()
            latest.lastError = ""
            sourceRepository.save(latest)

            profiles.size
        } catch (error: Throwable) {
            latest.lastError =
                error.message
                    ?: error
                        .javaClass
                        .simpleName
            sourceRepository.save(latest)
            throw error
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
            URI(value)
        }.getOrNull()
            ?.let { uri ->
                uri.scheme
                    ?.equals(
                        "https",
                        ignoreCase = true
                    ) == true &&
                    !uri.host
                        .isNullOrBlank() &&
                    uri.userInfo == null
            } == true

    private fun toast(
        message: String
    ) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
