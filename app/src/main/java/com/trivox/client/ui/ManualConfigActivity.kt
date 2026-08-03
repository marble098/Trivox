package com.trivox.client.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.widget.Toolbar
import com.trivox.client.R

class ManualConfigActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_config)
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        bind(R.id.addProxyChain) { startActivity(Intent(this, ProxyChainActivity::class.java)) }
        bindCommon(R.id.addVmess, "vmess")
        bindCommon(R.id.addVless, "vless")
        bindCommon(R.id.addShadowsocks, "shadowsocks")
        bindCommon(R.id.addSocks, "socks")
        bindCommon(R.id.addHttp, "http")
        bindCommon(R.id.addTrojan, "trojan")
        bindAdvanced(R.id.addWireguard, AdvancedManualConfigActivity.PROTOCOL_WIREGUARD)
        bindAdvanced(R.id.addHysteria2, AdvancedManualConfigActivity.PROTOCOL_HYSTERIA2)
    }
    private fun bind(id:Int, action:()->Unit){ findViewById<Button>(id).setOnClickListener{action()} }
    private fun bindCommon(id:Int, protocol:String)=bind(id){ startActivity(Intent(this,ConfigEditorActivity::class.java).putExtra(ConfigEditorActivity.EXTRA_NEW_PROTOCOL,protocol)) }
    private fun bindAdvanced(id:Int, protocol:String)=bind(id){ startActivity(Intent(this,AdvancedManualConfigActivity::class.java).putExtra(AdvancedManualConfigActivity.EXTRA_PROTOCOL,protocol)) }
}
