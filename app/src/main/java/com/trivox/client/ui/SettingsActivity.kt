package com.trivox.client.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.trivox.client.R
import com.trivox.client.config.Validators
import com.trivox.client.data.DnsMode
import com.trivox.client.data.SettingsRepository

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_settings)
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val repository = SettingsRepository(this); val settings = repository.load()
        val socks = findViewById<EditText>(R.id.socksPort); val http = findViewById<EditText>(R.id.httpPort)
        val mtu = findViewById<EditText>(R.id.mtuInput); val ipv6 = findViewById<CheckBox>(R.id.ipv6Check)
        val dns = findViewById<Spinner>(R.id.dnsMode); val custom = findViewById<EditText>(R.id.customDns)
        val network = findViewById<CheckBox>(R.id.reconnectNetwork); val boot = findViewById<CheckBox>(R.id.reconnectBoot)
        val blocking = findViewById<CheckBox>(R.id.blockingCheck)
        socks.setText(settings.socksPort.toString()); http.setText(settings.httpPort.toString()); mtu.setText(settings.mtu.toString())
        ipv6.isChecked = settings.ipv6; custom.setText(settings.customDns.joinToString("\n")); network.isChecked = settings.reconnectOnNetworkChange
        boot.isChecked = settings.reconnectOnBoot; blocking.isChecked = settings.blocking
        val modes = DnsMode.entries
        dns.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf(
            getString(R.string.dns_imported), getString(R.string.dns_default), getString(R.string.dns_custom),
            getString(R.string.dns_system), getString(R.string.dns_direct), getString(R.string.dns_proxy)))
        dns.setSelection(modes.indexOf(settings.dnsMode))
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val socksPort = socks.text.toString().toIntOrNull(); val httpPort = http.text.toString().toIntOrNull(); val mtuValue = mtu.text.toString().toIntOrNull()
            val dnsValues = custom.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (socksPort == null || httpPort == null || !Validators.validPort(socksPort) || !Validators.validPort(httpPort) || mtuValue !in 576..9000) {
                Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            if (modes[dns.selectedItemPosition] == DnsMode.CUSTOM && (dnsValues.isEmpty() || !dnsValues.all(Validators::validateDns))) {
                Toast.makeText(this, R.string.invalid_dns, Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            settings.socksPort = socksPort; settings.httpPort = httpPort; settings.mtu = mtuValue!!; settings.ipv6 = ipv6.isChecked
            settings.dnsMode = modes[dns.selectedItemPosition]; settings.customDns = dnsValues
            settings.reconnectOnNetworkChange = network.isChecked; settings.reconnectOnBoot = boot.isChecked; settings.blocking = blocking.isChecked
            repository.save(settings); Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show(); finish()
        }
    }
}
