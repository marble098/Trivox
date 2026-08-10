package com.trivox.client.ui

// TRIVOX_P2_REAL_ONLY_PROFILE_ADAPTER

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.trivox.client.R
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.TestStatus
import com.trivox.client.ui.compose.LegacyLayoutBridge
import org.json.JSONObject
import java.util.Locale

class ProfileAdapter(
    private val onClick: (ConfigProfile) -> Unit,
    private val onLongClick: (ConfigProfile) -> Unit,
    private val onAction: (ConfigProfile) -> Unit,
    private val onRealPing: (ConfigProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    private data class Row(
        val profile: ConfigProfile,
        val selected: Boolean,
        val connected: Boolean,
        val hideIp: Boolean,
        val gridMode: Boolean
    )

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.profile.id == newItem.profile.id
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    })

    init { setHasStableIds(true) }

    fun submit(values: List<ConfigProfile>, selected: String?, connected: String?, hideIp: Boolean, gridMode: Boolean) {
        differ.submitList(values.map { Row(it, it.id == selected, it.id == connected, hideIp, gridMode) })
    }

    override fun getItemId(position: Int) = differ.currentList[position].profile.id.hashCode().toLong()
    override fun getItemViewType(position: Int) = if (differ.currentList[position].gridMode) VIEW_GRID else VIEW_LIST
    override fun getItemCount() = differ.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        if (viewType == VIEW_GRID) LegacyLayoutBridge.row_profile_grid(parent.context)
        else LegacyLayoutBridge.row_profile(parent.context)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = differ.currentList[position]
        holder.bind(row.profile, row.selected, row.connected, row.hideIp)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.nameText)
        private val detail = view.findViewById<TextView>(R.id.detailText)
        private val location = view.findViewById<TextView>(R.id.locationText)
        private val status = view.findViewById<TextView>(R.id.statusText)
        private val tcpLatency = view.findViewById<TextView>(R.id.tcpLatencyText)
        private val realLatency = view.findViewById<TextView>(R.id.realLatencyText)
        private val ping = view.findViewById<TextView>(R.id.pingActionText)
        private val action = view.findViewById<TextView>(R.id.actionText)

        fun bind(profile: ConfigProfile, selected: Boolean, connected: Boolean, hideIp: Boolean) {
            itemView.setBackgroundResource(if (profile.favorite) R.drawable.row_background_favorite else R.drawable.row_background)
            itemView.isActivated = selected
            itemView.alpha = if (profile.enabled) 1f else 0.52f

            name.text = profile.name
            name.textSize = if (rowIsGrid()) 11.5f else 12.5f
            name.maxLines = 2
            name.ellipsize = null

            detail.text = buildString {
                append(displayMeta(profile))
                if (!hideIp) append(" • ${profile.server}:${profile.port}")
            }
            detail.textSize = 9.5f
            detail.maxLines = 2
            detail.ellipsize = null

            val locationValue = buildString {
                profile.exitFlag.takeIf(String::isNotBlank)?.let { append(it); append(' ') }
                profile.exitCountry.takeIf(String::isNotBlank)?.let(::append)
                if (!hideIp && profile.exitIp.isNotBlank()) {
                    if (isNotBlank()) append(" • ")
                    append(profile.exitIp)
                }
            }
            location.text = locationValue
            location.textSize = 9f
            location.visibility = when {
                locationValue.isNotBlank() -> View.VISIBLE
                rowIsGrid() -> View.INVISIBLE
                else -> View.GONE
            }

            val statusValue = when {
                connected -> itemView.context.getString(R.string.state_connected)
                profile.realTestStatus == TestStatus.TESTING -> itemView.context.getString(R.string.status_testing)
                else -> ""
            }
            status.text = statusValue
            status.textSize = 9f
            status.visibility = when {
                statusValue.isNotBlank() -> View.VISIBLE
                rowIsGrid() -> View.INVISIBLE
                else -> View.GONE
            }

            tcpLatency.visibility = View.GONE
            realLatency.text = when {
                profile.realLatencyMs != null && profile.realTestStatus == TestStatus.ALIVE ->
                    itemView.context.getString(R.string.real_result_value, profile.realLatencyMs)
                profile.realTestStatus in setOf(TestStatus.DEAD, TestStatus.ERROR) ->
                    itemView.context.getString(R.string.real_result_failed)
                else -> itemView.context.getString(R.string.real_result_empty)
            }
            realLatency.textSize = 10f

            ping.text = if (profile.realTestStatus == TestStatus.TESTING) "↻" else "⚡"
            action.text = "⋮"
            ping.textSize = 14f
            action.textSize = 16f
            action.contentDescription = itemView.context.getString(R.string.config_actions)
            ping.contentDescription = itemView.context.getString(R.string.ping_now)

            itemView.setOnClickListener { onClick(profile) }
            itemView.setOnLongClickListener { onLongClick(profile); true }
            realLatency.setOnClickListener { onRealPing(profile) }
            ping.setOnClickListener { onRealPing(profile) }
            action.setOnClickListener { onAction(profile) }
        }

        private fun displayMeta(profile: ConfigProfile): String {
            val stream = runCatching { JSONObject(profile.outboundJson).optJSONObject("streamSettings") }.getOrNull()
            val protocol = when (profile.protocol.lowercase(Locale.ROOT)) {
                "hysteria", "hysteria2", "hy2" -> "HYSTERIA2"
                "wireguard", "wg" -> "WIREGUARD"
                else -> profile.protocol.uppercase(Locale.ROOT)
            }
            val rawTransport = stream?.optString("network").orEmpty()
                .ifBlank { stream?.optString("method").orEmpty() }.lowercase(Locale.ROOT)
            val transport = when {
                protocol == "WIREGUARD" -> "UDP"
                protocol == "HYSTERIA2" -> "HYSTERIA2"
                rawTransport in setOf("", "none", "raw", "tcp") -> "RAW"
                rawTransport in setOf("ws", "websocket") -> "WS"
                rawTransport == "grpc" -> "gRPC"
                rawTransport in setOf("xhttp", "splithttp") -> "XHTTP"
                rawTransport in setOf("httpupgrade", "http-upgrade") -> "HTTPUpgrade"
                rawTransport in setOf("kcp", "mkcp") -> "mKCP"
                else -> rawTransport.uppercase(Locale.ROOT)
            }
            val rawSecurity = stream?.optString("security").orEmpty().lowercase(Locale.ROOT)
            val security = when {
                protocol == "WIREGUARD" -> "WG"
                protocol == "HYSTERIA2" -> "TLS"
                rawSecurity.isBlank() || rawSecurity == "none" -> "NONE"
                rawSecurity == "tls" -> "TLS"
                rawSecurity == "reality" -> "REALITY"
                else -> rawSecurity.uppercase(Locale.ROOT)
            }
            return "$protocol • $security • $transport"
        }

        private fun rowIsGrid() = itemViewType == VIEW_GRID
    }

    companion object {
        private const val VIEW_LIST = 0
        private const val VIEW_GRID = 1
    }
}
