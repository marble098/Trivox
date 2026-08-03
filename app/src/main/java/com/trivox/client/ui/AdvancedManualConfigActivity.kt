package com.trivox.client.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.trivox.client.R
import com.trivox.client.config.ManualConfigFactory
import com.trivox.client.data.ConfigRepository

class AdvancedManualConfigActivity : ThemedActivity() {
    private lateinit var protocol:String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_manual_config)
        protocol=intent.getStringExtra(EXTRA_PROTOCOL) ?: PROTOCOL_WIREGUARD
        findViewById<Toolbar>(R.id.toolbar).apply { title=getString(R.string.add_protocol_title,protocolLabel()); setNavigationOnClickListener{finish()} }
        configureVisibility()
        findViewById<Button>(R.id.manualCancelButton).setOnClickListener{finish()}
        findViewById<Button>(R.id.manualSaveButton).setOnClickListener{save()}
    }
    private fun configureVisibility(){
        val wire=protocol==PROTOCOL_WIREGUARD
        findViewById<TextView>(R.id.privateKeyLabel).setText(if(wire)R.string.wireguard_private_key else R.string.hysteria_auth)
        listOf(R.id.publicKeyLabel,R.id.publicKeyInput,R.id.preSharedKeyLabel,R.id.preSharedKeyInput,R.id.localAddressLabel,R.id.localAddressInput,R.id.allowedIpsLabel,R.id.allowedIpsInput,R.id.mtuLabel,R.id.manualMtuInput).forEach{findViewById<View>(it).visibility=if(wire)View.VISIBLE else View.GONE}
        listOf(R.id.sniLabel,R.id.manualSniInput,R.id.insecureCheck).forEach{findViewById<View>(it).visibility=if(wire)View.GONE else View.VISIBLE}
        findViewById<EditText>(R.id.manualPortInput).setText(if(wire)"2408" else "443")
        findViewById<EditText>(R.id.localAddressInput).setText("10.0.0.2/32")
        findViewById<EditText>(R.id.allowedIpsInput).setText("0.0.0.0/0, ::/0")
        findViewById<EditText>(R.id.manualMtuInput).setText("1420")
    }
    private fun save(){
        val name=text(R.id.manualNameInput); val address=text(R.id.manualAddressInput); val port=text(R.id.manualPortInput).toIntOrNull()?:0
        val result=runCatching{
            if(protocol==PROTOCOL_WIREGUARD){
                val endpoint = when {
                    address.startsWith("[") && address.contains("]:") -> address
                    address.count { it == ':' } > 1 -> "[$address]:$port"
                    else -> "$address:$port"
                }
                ManualConfigFactory.wireGuard(name,endpoint,text(R.id.privateKeyInput),text(R.id.publicKeyInput),text(R.id.localAddressInput),text(R.id.allowedIpsInput),text(R.id.preSharedKeyInput),text(R.id.manualMtuInput).toIntOrNull()?:1420)
            }else ManualConfigFactory.hysteria2(name,address,port,text(R.id.privateKeyInput),text(R.id.manualSniInput),findViewById<CheckBox>(R.id.insecureCheck).isChecked)
        }
        result.onSuccess{ConfigRepository(this).save(it);ConfigRepository(this).select(it.id);Toast.makeText(this,R.string.config_added,Toast.LENGTH_SHORT).show();finish()}
            .onFailure{Toast.makeText(this,it.message?:getString(R.string.invalid_config),Toast.LENGTH_LONG).show()}
    }
    private fun text(id:Int)=findViewById<EditText>(id).text.toString().trim()
    private fun protocolLabel()=if(protocol==PROTOCOL_WIREGUARD)"WireGuard" else "Hysteria2"
    companion object { const val EXTRA_PROTOCOL="protocol"; const val PROTOCOL_WIREGUARD="wireguard"; const val PROTOCOL_HYSTERIA2="hysteria2" }
}
