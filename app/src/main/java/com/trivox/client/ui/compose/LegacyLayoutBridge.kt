package com.trivox.client.ui.compose

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.RecyclerView
import com.trivox.client.R

object LegacyLayoutBridge {
    data class Host(val root: FrameLayout, val legacy: LinearLayout, val compose: ComposeView)

    fun install(activity: Activity, layoutName: String): Host {
        val root = FrameLayout(activity)
        val legacy = build(activity, layoutName).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        root.addView(legacy, FrameLayout.LayoutParams(1, 1))
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        activity.setContentView(root)
        return Host(root, legacy, compose)
    }

    fun build(context: Context, layoutName: String): LinearLayout = when (layoutName) {
        "activity_advanced_manual_config" -> activity_advanced_manual_config(context)
        "activity_app_routing" -> activity_app_routing(context)
        "activity_config_editor" -> activity_config_editor(context)
        "activity_diagnostics" -> activity_diagnostics(context)
        "activity_main" -> activity_main(context)
        "activity_manual_config" -> activity_manual_config(context)
        "activity_proxy_chain" -> activity_proxy_chain(context)
        "activity_settings" -> activity_settings(context)
        "activity_subscriptions" -> activity_subscriptions(context)
        else -> LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    }

    fun activity_advanced_manual_config(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        root.addView(v0)
        val v1 = ScrollView(context)
        val v2 = LinearLayout(context)
        v2.orientation = LinearLayout.VERTICAL
        val v3 = TextView(context)
        (v3 as? TextView)?.setText(R.string.remarks)
        v2.addView(v3)
        val v4 = EditText(context)
        v4.id = R.id.manualNameInput
        v2.addView(v4)
        val v5 = TextView(context)
        (v5 as? TextView)?.setText(R.string.address)
        v2.addView(v5)
        val v6 = EditText(context)
        v6.id = R.id.manualAddressInput
        v2.addView(v6)
        val v7 = TextView(context)
        (v7 as? TextView)?.setText(R.string.port)
        v2.addView(v7)
        val v8 = EditText(context)
        v8.id = R.id.manualPortInput
        v2.addView(v8)
        val v9 = TextView(context)
        v9.id = R.id.privateKeyLabel
        v2.addView(v9)
        val v10 = EditText(context)
        v10.id = R.id.privateKeyInput
        v2.addView(v10)
        val v11 = TextView(context)
        v11.id = R.id.publicKeyLabel
        (v11 as? TextView)?.setText(R.string.wireguard_public_key)
        v2.addView(v11)
        val v12 = EditText(context)
        v12.id = R.id.publicKeyInput
        v2.addView(v12)
        val v13 = TextView(context)
        v13.id = R.id.preSharedKeyLabel
        (v13 as? TextView)?.setText(R.string.wireguard_preshared_key)
        v2.addView(v13)
        val v14 = EditText(context)
        v14.id = R.id.preSharedKeyInput
        v2.addView(v14)
        val v15 = TextView(context)
        v15.id = R.id.localAddressLabel
        (v15 as? TextView)?.setText(R.string.wireguard_local_address)
        v2.addView(v15)
        val v16 = EditText(context)
        v16.id = R.id.localAddressInput
        v2.addView(v16)
        val v17 = TextView(context)
        v17.id = R.id.allowedIpsLabel
        (v17 as? TextView)?.setText(R.string.wireguard_allowed_ips)
        v2.addView(v17)
        val v18 = EditText(context)
        v18.id = R.id.allowedIpsInput
        v2.addView(v18)
        val v19 = TextView(context)
        v19.id = R.id.mtuLabel
        (v19 as? TextView)?.setText(R.string.mtu)
        v2.addView(v19)
        val v20 = EditText(context)
        v20.id = R.id.manualMtuInput
        v2.addView(v20)
        val v21 = TextView(context)
        v21.id = R.id.sniLabel
        (v21 as? TextView)?.setText(R.string.sni)
        v2.addView(v21)
        val v22 = EditText(context)
        v22.id = R.id.manualSniInput
        v2.addView(v22)
        val v23 = CheckBox(context)
        v23.id = R.id.insecureCheck
        (v23 as? TextView)?.setText(R.string.allow_insecure)
        v2.addView(v23)
        v1.addView(v2)
        root.addView(v1)
        val v24 = LinearLayout(context)
        v24.orientation = LinearLayout.HORIZONTAL
        val v25 = Button(context)
        v25.id = R.id.manualCancelButton
        (v25 as? TextView)?.setText(R.string.cancel)
        v24.addView(v25)
        val v26 = Button(context)
        v26.id = R.id.manualSaveButton
        (v26 as? TextView)?.setText(R.string.save)
        v24.addView(v26)
        root.addView(v24)
        return root
    }

    fun activity_app_routing(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        v0.title = context.getString(R.string.app_routing)
        root.addView(v0)
        val v1 = LinearLayout(context)
        v1.orientation = LinearLayout.VERTICAL
        val v2 = TextView(context)
        (v2 as? TextView)?.setText(R.string.routing_mode_label)
        v1.addView(v2)
        val v3 = Spinner(context)
        v3.id = R.id.routingMode
        v1.addView(v3)
        val v4 = CheckBox(context)
        v4.id = R.id.showSystem
        (v4 as? TextView)?.setText(R.string.show_system_apps)
        v1.addView(v4)
        root.addView(v1)
        val v5 = TextView(context)
        (v5 as? TextView)?.setText(R.string.applications)
        root.addView(v5)
        val v6 = FrameLayout(context)
        val v7 = RecyclerView(context)
        v7.id = R.id.appList
        v6.addView(v7)
        val v8 = TextView(context)
        v8.id = R.id.loadingText
        (v8 as? TextView)?.setText(R.string.loading_apps)
        v6.addView(v8)
        root.addView(v6)
        val v9 = Button(context)
        v9.id = R.id.saveButton
        (v9 as? TextView)?.setText(R.string.save)
        root.addView(v9)
        return root
    }

    fun activity_config_editor(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        v0.title = context.getString(R.string.edit_config)
        root.addView(v0)
        val v1 = LinearLayout(context)
        v1.orientation = LinearLayout.HORIZONTAL
        val v2 = TextView(context)
        (v2 as? TextView)?.setText(R.string.protocol)
        v1.addView(v2)
        val v3 = TextView(context)
        v3.id = R.id.protocolValue
        v1.addView(v3)
        val v4 = Button(context)
        v4.id = R.id.formModeButton
        (v4 as? TextView)?.setText(R.string.form_editor)
        v1.addView(v4)
        val v5 = Button(context)
        v5.id = R.id.jsonModeButton
        (v5 as? TextView)?.setText(R.string.raw_json)
        v1.addView(v5)
        root.addView(v1)
        val v6 = ScrollView(context)
        v6.id = R.id.formContainer
        val v7 = LinearLayout(context)
        v7.orientation = LinearLayout.VERTICAL
        val v8 = TextView(context)
        (v8 as? TextView)?.setText(R.string.basic_settings)
        v7.addView(v8)
        val v9 = EditText(context)
        v9.id = R.id.nameInput
        (v9 as? TextView)?.setHint(R.string.remarks)
        v7.addView(v9)
        val v10 = LinearLayout(context)
        v10.orientation = LinearLayout.HORIZONTAL
        val v11 = EditText(context)
        v11.id = R.id.addressInput
        (v11 as? TextView)?.setHint(R.string.address)
        v10.addView(v11)
        val v12 = EditText(context)
        v12.id = R.id.portInput
        (v12 as? TextView)?.setHint(R.string.port)
        v10.addView(v12)
        v7.addView(v10)
        val v13 = EditText(context)
        v13.id = R.id.credentialInput
        (v13 as? TextView)?.setHint(R.string.user_id_or_password)
        v7.addView(v13)
        val v14 = LinearLayout(context)
        v14.orientation = LinearLayout.HORIZONTAL
        val v15 = EditText(context)
        v15.id = R.id.encryptionInput
        (v15 as? TextView)?.setHint(R.string.encryption_method)
        v14.addView(v15)
        val v16 = EditText(context)
        v16.id = R.id.flowInput
        (v16 as? TextView)?.setHint(R.string.flow)
        v14.addView(v16)
        v7.addView(v14)
        val v17 = TextView(context)
        (v17 as? TextView)?.setText(R.string.transport_settings)
        v7.addView(v17)
        val v18 = Spinner(context)
        v18.id = R.id.transportSpinner
        v7.addView(v18)
        val v19 = EditText(context)
        v19.id = R.id.headerInput
        (v19 as? TextView)?.setHint(R.string.header_type)
        v7.addView(v19)
        val v20 = EditText(context)
        v20.id = R.id.hostInput
        (v20 as? TextView)?.setHint(R.string.host)
        v7.addView(v20)
        val v21 = EditText(context)
        v21.id = R.id.pathInput
        (v21 as? TextView)?.setHint(R.string.path)
        v7.addView(v21)
        val v22 = EditText(context)
        v22.id = R.id.serviceNameInput
        (v22 as? TextView)?.setHint(R.string.grpc_service_name)
        v7.addView(v22)
        val v23 = TextView(context)
        (v23 as? TextView)?.setText(R.string.security_settings)
        v7.addView(v23)
        val v24 = Spinner(context)
        v24.id = R.id.securitySpinner
        v7.addView(v24)
        val v25 = EditText(context)
        v25.id = R.id.sniInput
        (v25 as? TextView)?.setHint(R.string.sni)
        v7.addView(v25)
        val v26 = EditText(context)
        v26.id = R.id.fingerprintInput
        (v26 as? TextView)?.setHint(R.string.fingerprint)
        v7.addView(v26)
        val v27 = EditText(context)
        v27.id = R.id.publicKeyInput
        (v27 as? TextView)?.setHint(R.string.reality_public_key)
        v7.addView(v27)
        val v28 = EditText(context)
        v28.id = R.id.shortIdInput
        (v28 as? TextView)?.setHint(R.string.reality_short_id)
        v7.addView(v28)
        v6.addView(v7)
        root.addView(v6)
        val v29 = LinearLayout(context)
        v29.id = R.id.jsonContainer
        v29.orientation = LinearLayout.VERTICAL
        v29.visibility = View.GONE
        val v30 = TextView(context)
        (v30 as? TextView)?.setText(R.string.raw_json_description)
        v29.addView(v30)
        val v31 = EditText(context)
        v31.id = R.id.rawJsonInput
        v29.addView(v31)
        root.addView(v29)
        val v32 = LinearLayout(context)
        v32.orientation = LinearLayout.HORIZONTAL
        val v33 = Button(context)
        v33.id = R.id.editorCancelButton
        (v33 as? TextView)?.setText(R.string.cancel)
        v32.addView(v33)
        val v34 = Button(context)
        v34.id = R.id.editorSaveButton
        (v34 as? TextView)?.setText(R.string.save)
        v32.addView(v34)
        root.addView(v32)
        return root
    }

    fun activity_diagnostics(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        v0.title = context.getString(R.string.diagnostics)
        root.addView(v0)
        val v1 = TextView(context)
        (v1 as? TextView)?.setText(R.string.sanitized_report)
        root.addView(v1)
        val v2 = ScrollView(context)
        val v3 = TextView(context)
        v3.id = R.id.reportText
        v2.addView(v3)
        root.addView(v2)
        val v4 = LinearLayout(context)
        v4.orientation = LinearLayout.HORIZONTAL
        val v5 = Button(context)
        v5.id = R.id.refreshDiagnosticsButton
        (v5 as? TextView)?.setText(R.string.refresh_diagnostics)
        v4.addView(v5)
        val v6 = Button(context)
        v6.id = R.id.copyDiagnosticsButton
        (v6 as? TextView)?.setText(R.string.copy_diagnostics)
        v4.addView(v6)
        root.addView(v4)
        val v7 = LinearLayout(context)
        v7.orientation = LinearLayout.HORIZONTAL
        val v8 = Button(context)
        v8.id = R.id.clearDiagnosticsButton
        (v8 as? TextView)?.setText(R.string.clear_diagnostics)
        v7.addView(v8)
        val v9 = Button(context)
        v9.id = R.id.exportButton
        (v9 as? TextView)?.setText(R.string.export_diagnostics)
        v7.addView(v9)
        root.addView(v7)
        return root
    }

    fun activity_main(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        v0.title = context.getString(R.string.app_name)
        root.addView(v0)
        val v1 = LinearLayout(context)
        v1.orientation = LinearLayout.VERTICAL
        val v2 = LinearLayout(context)
        v2.orientation = LinearLayout.HORIZONTAL
        val v3 = TextView(context)
        v3.id = R.id.stateText
        (v3 as? TextView)?.setText(R.string.state_disconnected)
        v2.addView(v3)
        val v4 = TextView(context)
        v4.id = R.id.durationText
        (v4 as? TextView)?.text = "00:00"
        v2.addView(v4)
        val v5 = AppCompatImageButton(context)
        v5.id = R.id.quickToolsToggle
        v2.addView(v5)
        v1.addView(v2)
        val v6 = TextView(context)
        v6.id = R.id.selectedText
        (v6 as? TextView)?.maxLines = 1
        v1.addView(v6)
        val v7 = LinearLayout(context)
        v7.id = R.id.quickToolsPanel
        v7.orientation = LinearLayout.VERTICAL
        v7.visibility = View.GONE
        val v8 = LinearLayout(context)
        v8.orientation = LinearLayout.HORIZONTAL
        val v9 = Button(context)
        v9.id = R.id.livePingText
        (v9 as? TextView)?.setText(R.string.live_ping_off)
        v8.addView(v9)
        val v10 = Button(context)
        v10.id = R.id.realDelayText
        (v10 as? TextView)?.setText(R.string.real_delay_off)
        v8.addView(v10)
        v7.addView(v8)
        val v11 = TextView(context)
        v11.id = R.id.exitInfoText
        (v11 as? TextView)?.setText(R.string.exit_info_off)
        (v11 as? TextView)?.maxLines = 1
        v7.addView(v11)
        val v12 = LinearLayout(context)
        v12.orientation = LinearLayout.HORIZONTAL
        val v13 = Button(context)
        v13.id = R.id.refreshExitButton
        (v13 as? TextView)?.text = "↻"
        v13.isEnabled = false
        v12.addView(v13)
        val v14 = Button(context)
        v14.id = R.id.copySummaryButton
        (v14 as? TextView)?.text = "⧉"
        v14.isEnabled = false
        v12.addView(v14)
        v7.addView(v12)
        v1.addView(v7)
        root.addView(v1)
        val v15 = LinearLayout(context)
        v15.orientation = LinearLayout.HORIZONTAL
        val v16 = Spinner(context)
        v16.id = R.id.modeSpinner
        v15.addView(v16)
        val v17 = EditText(context)
        v17.id = R.id.searchInput
        (v17 as? TextView)?.setHint(R.string.search)
        (v17 as? TextView)?.maxLines = 1
        v15.addView(v17)
        root.addView(v15)
        val v18 = LinearLayout(context)
        v18.orientation = LinearLayout.HORIZONTAL
        val v19 = HorizontalScrollView(context)
        val v20 = LinearLayout(context)
        v20.id = R.id.subscriptionTabs
        v20.orientation = LinearLayout.HORIZONTAL
        v19.addView(v20)
        v18.addView(v19)
        val v21 = Button(context)
        v21.id = R.id.refreshSubscriptionsButton
        (v21 as? TextView)?.text = "↻"
        v18.addView(v21)
        root.addView(v18)
        val v22 = FrameLayout(context)
        val v23 = RecyclerView(context)
        v23.id = R.id.profileList
        v22.addView(v23)
        val v24 = TextView(context)
        v24.id = R.id.emptyText
        (v24 as? TextView)?.setText(R.string.no_profiles)
        v24.visibility = View.GONE
        v22.addView(v24)
        root.addView(v22)
        val v25 = LinearLayout(context)
        v25.orientation = LinearLayout.HORIZONTAL
        val v26 = Button(context)
        v26.id = R.id.addButton
        (v26 as? TextView)?.setText(R.string.add_short)
        v25.addView(v26)
        val v27 = Button(context)
        v27.id = R.id.testButton
        (v27 as? TextView)?.setText(R.string.tcp_ping_icon)
        v25.addView(v27)
        val v28 = Button(context)
        v28.id = R.id.realDelayAllButton
        (v28 as? TextView)?.setText(R.string.real_delay_all_icon)
        v25.addView(v28)
        root.addView(v25)
        val v29 = LinearLayout(context)
        v29.orientation = LinearLayout.HORIZONTAL
        val v30 = Button(context)
        v30.id = R.id.connectButton
        (v30 as? TextView)?.setText(R.string.connect)
        v29.addView(v30)
        val v31 = Button(context)
        v31.id = R.id.pauseButton
        (v31 as? TextView)?.text = "⏸"
        v31.visibility = View.GONE
        v29.addView(v31)
        root.addView(v29)
        return root
    }

    fun activity_manual_config(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        v0.title = context.getString(R.string.add_manual_config)
        root.addView(v0)
        val v1 = ScrollView(context)
        val v2 = LinearLayout(context)
        v2.orientation = LinearLayout.VERTICAL
        val v3 = Button(context)
        v3.id = R.id.addProxyChain
        (v3 as? TextView)?.setText(R.string.add_proxy_chain)
        v2.addView(v3)
        val v4 = Button(context)
        v4.id = R.id.addVmess
        (v4 as? TextView)?.setText(R.string.add_vmess)
        v2.addView(v4)
        val v5 = Button(context)
        v5.id = R.id.addVless
        (v5 as? TextView)?.setText(R.string.add_vless)
        v2.addView(v5)
        val v6 = Button(context)
        v6.id = R.id.addShadowsocks
        (v6 as? TextView)?.setText(R.string.add_shadowsocks)
        v2.addView(v6)
        val v7 = Button(context)
        v7.id = R.id.addSocks
        (v7 as? TextView)?.setText(R.string.add_socks)
        v2.addView(v7)
        val v8 = Button(context)
        v8.id = R.id.addHttp
        (v8 as? TextView)?.setText(R.string.add_http)
        v2.addView(v8)
        val v9 = Button(context)
        v9.id = R.id.addTrojan
        (v9 as? TextView)?.setText(R.string.add_trojan)
        v2.addView(v9)
        val v10 = Button(context)
        v10.id = R.id.addWireguard
        (v10 as? TextView)?.setText(R.string.add_wireguard)
        v2.addView(v10)
        val v11 = Button(context)
        v11.id = R.id.addHysteria2
        (v11 as? TextView)?.setText(R.string.add_hysteria2)
        v2.addView(v11)
        v1.addView(v2)
        root.addView(v1)
        return root
    }

    fun activity_proxy_chain(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        v0.title = context.getString(R.string.add_proxy_chain)
        root.addView(v0)
        val v1 = LinearLayout(context)
        v1.orientation = LinearLayout.VERTICAL
        val v2 = TextView(context)
        (v2 as? TextView)?.setText(R.string.chain_name)
        v1.addView(v2)
        val v3 = EditText(context)
        v3.id = R.id.chainNameInput
        v1.addView(v3)
        val v4 = TextView(context)
        (v4 as? TextView)?.setText(R.string.chain_exit)
        v1.addView(v4)
        val v5 = Spinner(context)
        v5.id = R.id.chainExitSpinner
        v1.addView(v5)
        val v6 = TextView(context)
        (v6 as? TextView)?.setText(R.string.chain_bridge)
        v1.addView(v6)
        val v7 = Spinner(context)
        v7.id = R.id.chainBridgeSpinner
        v1.addView(v7)
        val v8 = TextView(context)
        (v8 as? TextView)?.setText(R.string.chain_explanation)
        v1.addView(v8)
        root.addView(v1)
        val v9 = View(context)
        root.addView(v9)
        val v10 = LinearLayout(context)
        v10.orientation = LinearLayout.HORIZONTAL
        val v11 = Button(context)
        v11.id = R.id.chainCancelButton
        (v11 as? TextView)?.setText(R.string.cancel)
        v10.addView(v11)
        val v12 = Button(context)
        v12.id = R.id.chainSaveButton
        (v12 as? TextView)?.setText(R.string.save)
        v10.addView(v12)
        root.addView(v10)
        return root
    }

    fun activity_settings(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        v0.title = context.getString(R.string.settings)
        root.addView(v0)
        val v1 = ScrollView(context)
        val v2 = LinearLayout(context)
        v2.orientation = LinearLayout.VERTICAL
        val v3 = TextView(context)
        (v3 as? TextView)?.setText(R.string.interface_section)
        v2.addView(v3)
        val v4 = LinearLayout(context)
        v4.orientation = LinearLayout.VERTICAL
        val v5 = TextView(context)
        (v5 as? TextView)?.setText(R.string.language)
        v4.addView(v5)
        val v6 = Spinner(context)
        v6.id = R.id.languageSpinner
        v4.addView(v6)
        val v7 = SwitchCompat(context)
        v7.id = R.id.darkModeSwitch
        (v7 as? TextView)?.setText(R.string.dark_theme)
        v4.addView(v7)
        val v8 = CheckBox(context)
        v8.id = R.id.hideIpOnMainCheck
        (v8 as? TextView)?.setText(R.string.hide_ip_on_main)
        v4.addView(v8)
        v2.addView(v4)
        val v9 = TextView(context)
        (v9 as? TextView)?.setText(R.string.profile_sorting_section)
        v2.addView(v9)
        val v10 = LinearLayout(context)
        v10.orientation = LinearLayout.VERTICAL
        val v11 = TextView(context)
        (v11 as? TextView)?.setText(R.string.sort_mode)
        v10.addView(v11)
        val v12 = Spinner(context)
        v12.id = R.id.sortModeSpinner
        v10.addView(v12)
        val v13 = Button(context)
        v13.id = R.id.manageSubscriptionsButton
        (v13 as? TextView)?.setText(R.string.manage_subscriptions)
        v10.addView(v13)
        v2.addView(v10)
        val v14 = TextView(context)
        (v14 as? TextView)?.setText(R.string.ping_section)
        v2.addView(v14)
        val v15 = LinearLayout(context)
        v15.orientation = LinearLayout.VERTICAL
        val v16 = TextView(context)
        (v16 as? TextView)?.setText(R.string.live_ping_method)
        v15.addView(v16)
        val v17 = Spinner(context)
        v17.id = R.id.pingMethodSpinner
        v15.addView(v17)
        val v18 = TextView(context)
        v18.id = R.id.pingMethodSummary
        v15.addView(v18)
        val v19 = SwitchCompat(context)
        v19.id = R.id.livePingEnabledSwitch
        (v19 as? TextView)?.setText(R.string.live_ping_enabled)
        v15.addView(v19)
        val v20 = TextView(context)
        (v20 as? TextView)?.setText(R.string.live_ping_interval)
        v15.addView(v20)
        val v21 = EditText(context)
        v21.id = R.id.livePingIntervalInput
        v15.addView(v21)
        val v22 = TextView(context)
        (v22 as? TextView)?.setText(R.string.ping_attempts)
        v15.addView(v22)
        val v23 = Spinner(context)
        v23.id = R.id.pingAttemptsSpinner
        v15.addView(v23)
        val v24 = TextView(context)
        (v24 as? TextView)?.setText(R.string.ping_test_url)
        v15.addView(v24)
        val v25 = EditText(context)
        v25.id = R.id.testUrlInput
        v15.addView(v25)
        val v26 = TextView(context)
        (v26 as? TextView)?.setText(R.string.real_delay_profile)
        v15.addView(v26)
        val v27 = Spinner(context)
        v27.id = R.id.realDelayProfileSpinner
        v15.addView(v27)
        val v28 = TextView(context)
        (v28 as? TextView)?.setText(R.string.real_delay_group_size)
        v15.addView(v28)
        val v29 = EditText(context)
        v29.id = R.id.realDelayGroupSizeInput
        v15.addView(v29)
        val v30 = TextView(context)
        (v30 as? TextView)?.setText(R.string.real_delay_workers)
        v15.addView(v30)
        val v31 = EditText(context)
        v31.id = R.id.realDelayWorkersInput
        v15.addView(v31)
        val v32 = TextView(context)
        (v32 as? TextView)?.setText(R.string.real_delay_timeout)
        v15.addView(v32)
        val v33 = EditText(context)
        v33.id = R.id.realDelayTimeoutInput
        v15.addView(v33)
        val v34 = TextView(context)
        (v34 as? TextView)?.setText(R.string.real_delay_start_grace)
        v15.addView(v34)
        val v35 = EditText(context)
        v35.id = R.id.realDelayStartGraceInput
        v15.addView(v35)
        val v36 = TextView(context)
        (v36 as? TextView)?.setText(R.string.real_delay_target_count)
        v15.addView(v36)
        val v37 = EditText(context)
        v37.id = R.id.realDelayTargetCountInput
        v15.addView(v37)
        val v38 = TextView(context)
        (v38 as? TextView)?.setText(R.string.real_delay_required_proofs)
        v15.addView(v38)
        val v39 = EditText(context)
        v39.id = R.id.realDelayRequiredProofsInput
        v15.addView(v39)
        v2.addView(v15)
        val v40 = TextView(context)
        (v40 as? TextView)?.setText(R.string.network_tuning_section)
        v2.addView(v40)
        val v41 = LinearLayout(context)
        v41.orientation = LinearLayout.VERTICAL
        val v42 = TextView(context)
        (v42 as? TextView)?.setText(R.string.network_tuning_summary)
        v41.addView(v42)
        val v43 = SwitchCompat(context)
        v43.id = R.id.networkTuningEnabledSwitch
        (v43 as? TextView)?.setText(R.string.network_tuning_enabled)
        v41.addView(v43)
        val v44 = SwitchCompat(context)
        v44.id = R.id.adaptiveHandshakeSwitch
        (v44 as? TextView)?.setText(R.string.adaptive_handshake)
        v41.addView(v44)
        val v45 = SwitchCompat(context)
        v45.id = R.id.tcpFastOpenSwitch
        (v45 as? TextView)?.setText(R.string.tcp_fast_open)
        v41.addView(v45)
        val v46 = TextView(context)
        (v46 as? TextView)?.setText(R.string.tcp_keepalive_idle)
        v41.addView(v46)
        val v47 = EditText(context)
        v47.id = R.id.tcpKeepAliveIdleInput
        v41.addView(v47)
        val v48 = TextView(context)
        (v48 as? TextView)?.setText(R.string.tcp_keepalive_interval)
        v41.addView(v48)
        val v49 = EditText(context)
        v49.id = R.id.tcpKeepAliveIntervalInput
        v41.addView(v49)
        val v50 = TextView(context)
        (v50 as? TextView)?.setText(R.string.tcp_user_timeout)
        v41.addView(v50)
        val v51 = EditText(context)
        v51.id = R.id.tcpUserTimeoutInput
        v41.addView(v51)
        val v52 = TextView(context)
        (v52 as? TextView)?.setText(R.string.network_buffer_size_kb)
        v41.addView(v52)
        val v53 = EditText(context)
        v53.id = R.id.networkBufferSizeInput
        v41.addView(v53)
        v2.addView(v41)
        val v54 = TextView(context)
        (v54 as? TextView)?.setText(R.string.local_proxy_section)
        v2.addView(v54)
        val v55 = LinearLayout(context)
        v55.orientation = LinearLayout.VERTICAL
        val v56 = TextView(context)
        (v56 as? TextView)?.setText(R.string.mixed_proxy_hint)
        v55.addView(v56)
        val v57 = EditText(context)
        v57.id = R.id.socksPort
        v55.addView(v57)
        val v58 = CheckBox(context)
        v58.id = R.id.localProxyInVpnCheck
        (v58 as? TextView)?.setText(R.string.local_proxy_in_vpn)
        v55.addView(v58)
        val v59 = Button(context)
        v59.id = R.id.telegramProxyButton
        (v59 as? TextView)?.setText(R.string.open_telegram_proxy)
        v55.addView(v59)
        v2.addView(v55)
        val v60 = TextView(context)
        (v60 as? TextView)?.setText(R.string.vpn_section)
        v2.addView(v60)
        val v61 = LinearLayout(context)
        v61.orientation = LinearLayout.VERTICAL
        val v62 = TextView(context)
        (v62 as? TextView)?.setText(R.string.mtu)
        v61.addView(v62)
        val v63 = EditText(context)
        v63.id = R.id.mtuInput
        v61.addView(v63)
        val v64 = CheckBox(context)
        v64.id = R.id.ipv6Check
        (v64 as? TextView)?.setText(R.string.ipv6)
        v61.addView(v64)
        val v65 = CheckBox(context)
        v65.id = R.id.blockingCheck
        (v65 as? TextView)?.setText(R.string.blocking_mode)
        v61.addView(v65)
        v2.addView(v61)
        val v66 = TextView(context)
        (v66 as? TextView)?.setText(R.string.wireguard_section_v9)
        v2.addView(v66)
        val v67 = LinearLayout(context)
        v67.orientation = LinearLayout.VERTICAL
        val v68 = TextView(context)
        (v68 as? TextView)?.setText(R.string.wireguard_summary_v9)
        v67.addView(v68)
        val v69 = TextView(context)
        (v69 as? TextView)?.setText(R.string.wireguard_mtu_v9)
        v67.addView(v69)
        val v70 = EditText(context)
        v70.id = R.id.wireGuardMtuInput
        v67.addView(v70)
        val v71 = TextView(context)
        (v71 as? TextView)?.setText(R.string.wireguard_workers_v9)
        v67.addView(v71)
        val v72 = Spinner(context)
        v72.id = R.id.wireGuardWorkersSpinner
        v67.addView(v72)
        val v73 = TextView(context)
        (v73 as? TextView)?.setText(R.string.wireguard_keepalive_v9)
        v67.addView(v73)
        val v74 = EditText(context)
        v74.id = R.id.wireGuardKeepAliveInput
        v67.addView(v74)
        val v75 = TextView(context)
        (v75 as? TextView)?.setText(R.string.wireguard_handshake_timeout_v9)
        v67.addView(v75)
        val v76 = EditText(context)
        v76.id = R.id.wireGuardHandshakeTimeoutInput
        v67.addView(v76)
        val v77 = TextView(context)
        (v77 as? TextView)?.setText(R.string.wireguard_domain_strategy_v9)
        v67.addView(v77)
        val v78 = Spinner(context)
        v78.id = R.id.wireGuardDomainStrategySpinner
        v67.addView(v78)
        v2.addView(v67)
        val v79 = TextView(context)
        (v79 as? TextView)?.setText(R.string.dns_section)
        v2.addView(v79)
        val v80 = LinearLayout(context)
        v80.orientation = LinearLayout.VERTICAL
        val v81 = Spinner(context)
        v81.id = R.id.dnsMode
        v80.addView(v81)
        val v82 = EditText(context)
        v82.id = R.id.customDns
        (v82 as? TextView)?.setHint(R.string.custom_dns_hint)
        v80.addView(v82)
        v2.addView(v80)
        val v83 = TextView(context)
        (v83 as? TextView)?.setText(R.string.reconnect_section)
        v2.addView(v83)
        val v84 = LinearLayout(context)
        v84.orientation = LinearLayout.VERTICAL
        val v85 = CheckBox(context)
        v85.id = R.id.reconnectNetwork
        (v85 as? TextView)?.setText(R.string.reconnect_network)
        v84.addView(v85)
        val v86 = CheckBox(context)
        v86.id = R.id.reconnectBoot
        (v86 as? TextView)?.setText(R.string.reconnect_boot)
        v84.addView(v86)
        v2.addView(v84)
        val v87 = TextView(context)
        (v87 as? TextView)?.setText(R.string.update_section)
        v2.addView(v87)
        val v88 = LinearLayout(context)
        v88.orientation = LinearLayout.VERTICAL
        val v89 = CheckBox(context)
        v89.id = R.id.autoUpdateCheck
        (v89 as? TextView)?.setText(R.string.auto_update_check)
        v88.addView(v89)
        val v90 = Button(context)
        v90.id = R.id.checkUpdateButton
        (v90 as? TextView)?.setText(R.string.check_update_now)
        v88.addView(v90)
        val v91 = TextView(context)
        v91.id = R.id.updateStatus
        v88.addView(v91)
        v2.addView(v88)
        val v92 = TextView(context)
        (v92 as? TextView)?.setText(R.string.about_section)
        v2.addView(v92)
        val v93 = LinearLayout(context)
        v93.orientation = LinearLayout.VERTICAL
        val v94 = TextView(context)
        v94.id = R.id.aboutCreators
        v93.addView(v94)
        val v95 = TextView(context)
        v95.id = R.id.aboutAppVersion
        v93.addView(v95)
        val v96 = TextView(context)
        v96.id = R.id.aboutCoreVersion
        v93.addView(v96)
        val v97 = TextView(context)
        v97.id = R.id.aboutSigning
        (v97 as? TextView)?.maxLines = 2
        v93.addView(v97)
        val v98 = TextView(context)
        v98.id = R.id.aboutPackage
        v93.addView(v98)
        v2.addView(v93)
        v1.addView(v2)
        root.addView(v1)
        val v99 = Button(context)
        v99.id = R.id.saveButton
        (v99 as? TextView)?.setText(R.string.save)
        root.addView(v99)
        return root
    }

    fun activity_subscriptions(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = Toolbar(context)
        v0.id = R.id.toolbar
        v0.title = context.getString(R.string.manage_subscriptions)
        root.addView(v0)
        val v1 = LinearLayout(context)
        v1.orientation = LinearLayout.VERTICAL
        val v2 = TextView(context)
        v2.id = R.id.subscriptionHeaderSummary
        (v2 as? TextView)?.setText(R.string.subscription_header_empty)
        v1.addView(v2)
        val v3 = TextView(context)
        (v3 as? TextView)?.setText(R.string.subscription_header_hint)
        v1.addView(v3)
        root.addView(v1)
        val v4 = TextView(context)
        v4.id = R.id.subscriptionProgress
        (v4 as? TextView)?.setText(R.string.subscription_updating)
        v4.visibility = View.GONE
        root.addView(v4)
        val v5 = FrameLayout(context)
        val v6 = ScrollView(context)
        val v7 = LinearLayout(context)
        v7.id = R.id.subscriptionList
        v7.orientation = LinearLayout.VERTICAL
        v6.addView(v7)
        v5.addView(v6)
        val v8 = TextView(context)
        v8.id = R.id.subscriptionEmpty
        (v8 as? TextView)?.setText(R.string.no_subscriptions)
        v8.visibility = View.GONE
        v5.addView(v8)
        root.addView(v5)
        val v9 = LinearLayout(context)
        v9.orientation = LinearLayout.HORIZONTAL
        val v10 = Button(context)
        v10.id = R.id.addSubscriptionButton
        (v10 as? TextView)?.setText(R.string.add_subscription)
        v9.addView(v10)
        val v11 = Button(context)
        v11.id = R.id.updateAllSubscriptionsButton
        (v11 as? TextView)?.setText(R.string.update_all_subscriptions)
        v9.addView(v11)
        root.addView(v9)
        return root
    }

    fun row_app(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = LinearLayout(context)
        v0.orientation = LinearLayout.HORIZONTAL
        val v1 = ImageView(context)
        v1.id = R.id.appIcon
        v0.addView(v1)
        val v2 = LinearLayout(context)
        v2.orientation = LinearLayout.VERTICAL
        val v3 = TextView(context)
        v3.id = R.id.appLabel
        (v3 as? TextView)?.maxLines = 1
        v2.addView(v3)
        val v4 = TextView(context)
        v4.id = R.id.packageName
        (v4 as? TextView)?.maxLines = 1
        v2.addView(v4)
        v0.addView(v2)
        val v5 = CheckBox(context)
        v5.id = R.id.selected
        v0.addView(v5)
        root.addView(v0)
        val v6 = View(context)
        root.addView(v6)
        return root
    }

    fun row_profile(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.HORIZONTAL
        val v0 = LinearLayout(context)
        v0.orientation = LinearLayout.VERTICAL
        val v1 = TextView(context)
        v1.id = R.id.nameText
        (v1 as? TextView)?.maxLines = 1
        v0.addView(v1)
        val v2 = TextView(context)
        v2.id = R.id.detailText
        (v2 as? TextView)?.maxLines = 1
        v0.addView(v2)
        val v3 = TextView(context)
        v3.id = R.id.locationText
        v3.visibility = View.GONE
        (v3 as? TextView)?.maxLines = 1
        v0.addView(v3)
        val v4 = TextView(context)
        v4.id = R.id.statusText
        v4.visibility = View.GONE
        (v4 as? TextView)?.maxLines = 1
        v0.addView(v4)
        root.addView(v0)
        val v5 = View(context)
        root.addView(v5)
        val v6 = LinearLayout(context)
        v6.orientation = LinearLayout.VERTICAL
        val v7 = TextView(context)
        v7.id = R.id.tcpLatencyText
        (v7 as? TextView)?.maxLines = 1
        v6.addView(v7)
        val v8 = View(context)
        v6.addView(v8)
        val v9 = TextView(context)
        v9.id = R.id.realLatencyText
        (v9 as? TextView)?.maxLines = 1
        v6.addView(v9)
        root.addView(v6)
        val v10 = TextView(context)
        v10.id = R.id.pingActionText
        (v10 as? TextView)?.setText(R.string.ping_short)
        root.addView(v10)
        val v11 = TextView(context)
        v11.id = R.id.actionText
        (v11 as? TextView)?.text = "⋮"
        root.addView(v11)
        return root
    }

    fun row_profile_grid(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = LinearLayout(context)
        v0.orientation = LinearLayout.HORIZONTAL
        val v1 = TextView(context)
        v1.id = R.id.nameText
        (v1 as? TextView)?.maxLines = 1
        v0.addView(v1)
        val v2 = TextView(context)
        v2.id = R.id.actionText
        (v2 as? TextView)?.text = "⋮"
        v0.addView(v2)
        root.addView(v0)
        val v3 = TextView(context)
        v3.id = R.id.detailText
        (v3 as? TextView)?.maxLines = 2
        root.addView(v3)
        val v4 = TextView(context)
        v4.id = R.id.locationText
        v4.visibility = View.GONE
        (v4 as? TextView)?.maxLines = 1
        root.addView(v4)
        val v5 = TextView(context)
        v5.id = R.id.statusText
        v5.visibility = View.GONE
        (v5 as? TextView)?.maxLines = 1
        root.addView(v5)
        val v6 = View(context)
        root.addView(v6)
        val v7 = LinearLayout(context)
        v7.orientation = LinearLayout.HORIZONTAL
        val v8 = TextView(context)
        v8.id = R.id.tcpLatencyText
        (v8 as? TextView)?.maxLines = 1
        v7.addView(v8)
        val v9 = View(context)
        v7.addView(v9)
        val v10 = TextView(context)
        v10.id = R.id.realLatencyText
        (v10 as? TextView)?.maxLines = 1
        v7.addView(v10)
        val v11 = TextView(context)
        v11.id = R.id.pingActionText
        (v11 as? TextView)?.setText(R.string.ping_short)
        v7.addView(v11)
        root.addView(v7)
        return root
    }

    fun row_subscription(context: Context): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.orientation = LinearLayout.VERTICAL
        val v0 = LinearLayout(context)
        v0.orientation = LinearLayout.HORIZONTAL
        val v1 = TextView(context)
        v1.id = R.id.subscriptionKindBadge
        v0.addView(v1)
        val v2 = TextView(context)
        v2.id = R.id.subscriptionName
        (v2 as? TextView)?.maxLines = 1
        v0.addView(v2)
        val v3 = TextView(context)
        v3.id = R.id.subscriptionStatusBadge
        v0.addView(v3)
        root.addView(v0)
        val v4 = TextView(context)
        v4.id = R.id.subscriptionUrl
        (v4 as? TextView)?.maxLines = 1
        root.addView(v4)
        val v5 = TextView(context)
        v5.id = R.id.subscriptionSummary
        root.addView(v5)
        val v6 = TextView(context)
        v6.id = R.id.subscriptionError
        v6.visibility = View.GONE
        root.addView(v6)
        val v7 = LinearLayout(context)
        v7.orientation = LinearLayout.HORIZONTAL
        val v8 = Button(context)
        v8.id = R.id.subscriptionUpdateButton
        (v8 as? TextView)?.setText(R.string.update)
        v7.addView(v8)
        val v9 = Button(context)
        v9.id = R.id.subscriptionEditButton
        (v9 as? TextView)?.setText(R.string.edit_subscription)
        v7.addView(v9)
        val v10 = Button(context)
        v10.id = R.id.subscriptionEnableButton
        (v10 as? TextView)?.setText(R.string.disable)
        v7.addView(v10)
        root.addView(v7)
        val v11 = Button(context)
        v11.id = R.id.subscriptionDeleteButton
        (v11 as? TextView)?.setText(R.string.delete_subscription)
        root.addView(v11)
        return root
    }

    fun dialog_edit_config(context: Context): LinearLayout {
        val root = dialogRoot(context)
        root.addView(EditText(context).apply { id = R.id.editConfigInput; setHint(R.string.import_hint); minLines = 5 }, normalParams())
        return root
    }

    fun dialog_import(context: Context): LinearLayout {
        val root = dialogRoot(context)
        root.addView(EditText(context).apply { id = R.id.importInput; setHint(R.string.import_hint); minLines = 6; maxLines = 12 }, normalParams())
        root.addView(Button(context).apply { id = R.id.clipboardButton; setText(R.string.import_clipboard) }, normalParams())
        root.addView(Button(context).apply { id = R.id.fileButton; setText(R.string.import_file) }, normalParams())
        return root
    }

    fun dialog_subscription(context: Context): LinearLayout {
        val root = dialogRoot(context)
        root.addView(EditText(context).apply { id = R.id.subscriptionNameInput; setHint(R.string.subscription_name_hint) }, normalParams())
        root.addView(EditText(context).apply { id = R.id.subscriptionUrlInput; setHint(R.string.subscription_url_hint) }, normalParams())
        root.addView(CheckBox(context).apply { id = R.id.subscriptionEnabledCheck; setText(R.string.subscription_enabled) }, normalParams())
        return root
    }

    private fun dialogRoot(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, 16)
        setPadding(p, dp(context, 10), p, dp(context, 4))
    }

    private fun normalParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
