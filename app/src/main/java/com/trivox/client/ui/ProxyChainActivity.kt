package com.trivox.client.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.trivox.client.R
import com.trivox.client.config.ManualConfigFactory
import com.trivox.client.config.ProxyChainCodec
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import org.json.JSONObject

class ProxyChainActivity : ThemedActivity() {
    private lateinit var repository: ConfigRepository
    private lateinit var profiles: List<ConfigProfile>
    private var existing: ConfigProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_chain)

        repository = ConfigRepository(this)
        existing = repository.find(
            intent.getStringExtra(EXTRA_PROFILE_ID)
        )?.takeIf { it.protocol == "chain" }

        findViewById<Toolbar>(R.id.toolbar).apply {
            title = getString(
                if (existing == null) {
                    R.string.add_proxy_chain
                } else {
                    R.string.edit_config
                }
            )
            setNavigationOnClickListener { finish() }
        }

        profiles = repository.all().filter {
            it.enabled &&
                it.protocol != "chain" &&
                it.id != existing?.id
        }

        val labels = profiles.map {
            "${it.name} • ${it.protocol.uppercase()} • ${it.server}:${it.port}"
        }
        val spinnerAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            labels
        ).also {
            it.setDropDownViewResource(
                R.layout.spinner_dropdown_item
            )
        }

        findViewById<Spinner>(R.id.chainExitSpinner).adapter =
            spinnerAdapter
        findViewById<Spinner>(R.id.chainBridgeSpinner).adapter =
            spinnerAdapter

        loadExistingSelection()

        findViewById<Button>(R.id.chainCancelButton)
            .setOnClickListener { finish() }
        findViewById<Button>(R.id.chainSaveButton)
            .setOnClickListener { save() }
    }

    private fun loadExistingSelection() {
        val profile = existing ?: return
        findViewById<EditText>(R.id.chainNameInput)
            .setText(profile.name)

        val decoded = ProxyChainCodec.decode(profile.outboundJson)
            ?: return
        val exitIndex = findMatchingProfile(
            decoded.exit,
            decoded.exitName
        )
        val bridgeIndex = findMatchingProfile(
            decoded.bridge,
            decoded.bridgeName
        )

        if (exitIndex >= 0) {
            findViewById<Spinner>(R.id.chainExitSpinner)
                .setSelection(exitIndex)
        }
        if (bridgeIndex >= 0) {
            findViewById<Spinner>(R.id.chainBridgeSpinner)
                .setSelection(bridgeIndex)
        }
    }

    private fun findMatchingProfile(
        outbound: JSONObject,
        name: String
    ): Int {
        val normalized = outbound.toString()
        val exact = profiles.indexOfFirst {
            runCatching {
                JSONObject(it.outboundJson).toString() == normalized
            }.getOrDefault(false)
        }
        return if (exact >= 0) {
            exact
        } else {
            profiles.indexOfFirst { it.name == name }
        }
    }

    private fun save() {
        if (profiles.size < 2) {
            Toast.makeText(
                this,
                R.string.chain_requires_two,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val exitIndex = findViewById<Spinner>(
            R.id.chainExitSpinner
        ).selectedItemPosition
        val bridgeIndex = findViewById<Spinner>(
            R.id.chainBridgeSpinner
        ).selectedItemPosition

        if (
            exitIndex !in profiles.indices ||
            bridgeIndex !in profiles.indices ||
            exitIndex == bridgeIndex
        ) {
            Toast.makeText(
                this,
                R.string.chain_select_different,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val exit = profiles[exitIndex]
        val bridge = profiles[bridgeIndex]
        val name = findViewById<EditText>(
            R.id.chainNameInput
        ).text.toString()
        val created = ManualConfigFactory.chain(
            name,
            exit,
            bridge
        )
        val previous = existing
        val profile = if (previous == null) {
            created
        } else {
            created.copy(
                id = previous.id,
                enabled = previous.enabled,
                favorite = previous.favorite,
                latencyMs = previous.latencyMs,
                latencyJitterMs = previous.latencyJitterMs,
                latencySuccessRatio = previous.latencySuccessRatio,
                latencyMethod = previous.latencyMethod,
                testStatus = previous.testStatus,
                lastTestAt = previous.lastTestAt,
                lastSessionMs = previous.lastSessionMs,
                cumulativeSessionMs = previous.cumulativeSessionMs,
                exitIp = previous.exitIp,
                exitCountry = previous.exitCountry,
                exitCountryCode = previous.exitCountryCode,
                exitFlag = previous.exitFlag,
                exitIsp = previous.exitIsp,
                lastExitCheckAt = previous.lastExitCheckAt
            )
        }

        repository.save(profile)
        repository.select(profile.id)
        Toast.makeText(
            this,
            R.string.chain_added,
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
