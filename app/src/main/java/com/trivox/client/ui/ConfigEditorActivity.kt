package com.trivox.client.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.trivox.client.R
import com.trivox.client.config.ConfigEditorCodec
import com.trivox.client.config.ConfigEditorFields
import com.trivox.client.config.ManualConfigFactory
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository

class ConfigEditorActivity : ThemedActivity() {
    private lateinit var repository: ConfigRepository
    private lateinit var profile: ConfigProfile
    private var creating = false

    private lateinit var formContainer: View
    private lateinit var jsonContainer: View
    private lateinit var rawJsonInput: EditText

    private lateinit var nameInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var credentialInput: EditText
    private lateinit var encryptionInput: EditText
    private lateinit var flowInput: EditText
    private lateinit var transportSpinner: Spinner
    private lateinit var headerInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var securitySpinner: Spinner
    private lateinit var sniInput: EditText
    private lateinit var fingerprintInput: EditText
    private lateinit var publicKeyInput: EditText
    private lateinit var shortIdInput: EditText
    private lateinit var serviceNameInput: EditText

    private var rawMode = false
    private var editorProtocol = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        repository = ConfigRepository(this)
        val newProtocol =
            intent.getStringExtra(
                EXTRA_NEW_PROTOCOL
            )
        creating =
            !newProtocol.isNullOrBlank()
        profile =
            if (creating) {
                ManualConfigFactory
                    .draft(newProtocol!!)
            } else {
                val profileId =
                    intent.getStringExtra(
                        EXTRA_PROFILE_ID
                    )
                repository.find(profileId)
                    ?: run {
                        Toast.makeText(
                            this,
                            R.string.profile_not_found,
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                        return
                    }
            }

        bindViews()
        setupToolbar()
        setupSpinners()
        loadFields(ConfigEditorCodec.read(profile))
        showFormMode()

        findViewById<Button>(R.id.formModeButton).setOnClickListener {
            switchToForm()
        }
        findViewById<Button>(R.id.jsonModeButton).setOnClickListener {
            switchToJson()
        }
        findViewById<Button>(R.id.editorCancelButton).setOnClickListener {
            finish()
        }
        findViewById<Button>(R.id.editorSaveButton).setOnClickListener {
            saveProfile()
        }
    }

    private fun bindViews() {
        formContainer = findViewById(R.id.formContainer)
        jsonContainer = findViewById(R.id.jsonContainer)
        rawJsonInput = findViewById(R.id.rawJsonInput)
        nameInput = findViewById(R.id.nameInput)
        addressInput = findViewById(R.id.addressInput)
        portInput = findViewById(R.id.portInput)
        credentialInput = findViewById(R.id.credentialInput)
        encryptionInput = findViewById(R.id.encryptionInput)
        flowInput = findViewById(R.id.flowInput)
        transportSpinner = findViewById(R.id.transportSpinner)
        headerInput = findViewById(R.id.headerInput)
        hostInput = findViewById(R.id.hostInput)
        pathInput = findViewById(R.id.pathInput)
        securitySpinner = findViewById(R.id.securitySpinner)
        sniInput = findViewById(R.id.sniInput)
        fingerprintInput = findViewById(R.id.fingerprintInput)
        publicKeyInput = findViewById(R.id.publicKeyInput)
        shortIdInput = findViewById(R.id.shortIdInput)
        serviceNameInput = findViewById(R.id.serviceNameInput)
    }

    private fun setupToolbar() {
        findViewById<Toolbar>(R.id.toolbar).apply {
            title = getString(
                if (creating) {
                    R.string.add_protocol_title
                } else {
                    R.string.edit_protocol_title
                },
                profile.protocol.uppercase()
            )
            setNavigationOnClickListener { finish() }
        }
        editorProtocol = profile.protocol
        findViewById<TextView>(R.id.protocolValue).text =
            editorProtocol.uppercase()
    }

    private fun setupSpinners() {
        transportSpinner.adapter = compactAdapter(TRANSPORTS)
        securitySpinner.adapter = compactAdapter(SECURITIES)
    }

    private fun compactAdapter(values: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item, values).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun loadFields(fields: ConfigEditorFields) {
        editorProtocol = fields.protocol
        findViewById<TextView>(R.id.protocolValue).text =
            editorProtocol.uppercase()
        nameInput.setText(fields.name)
        addressInput.setText(fields.address)
        portInput.setText(fields.port.toString())
        credentialInput.setText(fields.credential)
        encryptionInput.setText(fields.encryption)
        flowInput.setText(fields.flow)
        headerInput.setText(fields.headerType)
        hostInput.setText(fields.host)
        pathInput.setText(fields.path)
        sniInput.setText(fields.sni)
        fingerprintInput.setText(fields.fingerprint)
        publicKeyInput.setText(fields.publicKey)
        shortIdInput.setText(fields.shortId)
        serviceNameInput.setText(fields.serviceName)
        select(transportSpinner, TRANSPORTS, fields.transport)
        select(securitySpinner, SECURITIES, fields.security)
    }

    private fun collectFields(): ConfigEditorFields = ConfigEditorFields(
        name = nameInput.text.toString(),
        protocol = editorProtocol,
        address = addressInput.text.toString(),
        port = portInput.text.toString().toIntOrNull() ?: 0,
        credential = credentialInput.text.toString(),
        encryption = encryptionInput.text.toString(),
        flow = flowInput.text.toString(),
        transport = transportSpinner.selectedItem?.toString().orEmpty(),
        headerType = headerInput.text.toString(),
        host = hostInput.text.toString(),
        path = pathInput.text.toString(),
        security = securitySpinner.selectedItem?.toString().orEmpty(),
        sni = sniInput.text.toString(),
        fingerprint = fingerprintInput.text.toString(),
        publicKey = publicKeyInput.text.toString(),
        shortId = shortIdInput.text.toString(),
        serviceName = serviceNameInput.text.toString()
    )

    private fun switchToJson() {
        runCatching {
            ConfigEditorCodec.jsonFromFields(collectFields())
        }.onSuccess {
            rawJsonInput.setText(it)
            showJsonMode()
        }.onFailure(::showError)
    }

    private fun switchToForm() {
        if (!rawMode) return

        runCatching {
            ConfigEditorCodec.fieldsFromJson(
                profile,
                rawJsonInput.text.toString()
            )
        }.onSuccess {
            loadFields(it)
            showFormMode()
        }.onFailure(::showError)
    }

    private fun showFormMode() {
        rawMode = false
        formContainer.visibility = View.VISIBLE
        jsonContainer.visibility = View.GONE
        findViewById<Button>(R.id.formModeButton).isEnabled = false
        findViewById<Button>(R.id.jsonModeButton).isEnabled = true
    }

    private fun showJsonMode() {
        rawMode = true
        formContainer.visibility = View.GONE
        jsonContainer.visibility = View.VISIBLE
        findViewById<Button>(R.id.formModeButton).isEnabled = true
        findViewById<Button>(R.id.jsonModeButton).isEnabled = false
    }

    private fun saveProfile() {
        val replacement = runCatching {
            if (rawMode) {
                ConfigEditorCodec.updateFromJson(
                    profile,
                    rawJsonInput.text.toString()
                )
            } else {
                ConfigEditorCodec.updateFromFields(
                    profile,
                    collectFields()
                )
            }
        }.getOrElse {
            showError(it)
            return
        }

        repository.save(replacement)
        if (creating) {
            repository.select(
                replacement.id
            )
        }
        setResult(RESULT_OK)
        Toast.makeText(
            this,
            if (creating) {
                R.string.config_added
            } else {
                R.string.config_updated
            },
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun showError(error: Throwable) {
        Toast.makeText(
            this,
            error.message ?: getString(R.string.invalid_config),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun select(
        spinner: Spinner,
        values: Array<String>,
        value: String
    ) {
        val index = values.indexOfFirst { it.equals(value, ignoreCase = true) }
        spinner.setSelection(if (index >= 0) index else 0)
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_NEW_PROTOCOL = "new_protocol"

        private val TRANSPORTS = arrayOf(
            "tcp",
            "ws",
            "grpc",
            "xhttp",
            "httpupgrade",
            "http",
            "kcp",
            "quic"
        )

        private val SECURITIES = arrayOf(
            "none",
            "tls",
            "reality"
        )
    }
}
