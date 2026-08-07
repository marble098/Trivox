package com.trivox.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.trivox.client.R
import com.trivox.client.ui.compose.LegacyLayoutBridge
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.TestStatus

class ProfileAdapter(
    private val onClick: (ConfigProfile) -> Unit,
    private val onLongClick: (ConfigProfile) -> Unit,
    private val onAction: (ConfigProfile) -> Unit,
    private val onTcpPing: (ConfigProfile) -> Unit,
    private val onRealPing: (ConfigProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    private data class Row(
        val profile: ConfigProfile,
        val selected: Boolean,
        val connected: Boolean,
        val hideIp: Boolean,
        val gridMode: Boolean
    )

    private val differ = AsyncListDiffer(
        this,
        object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.profile.id == newItem.profile.id

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    )

    init {
        setHasStableIds(true)
    }

    fun submit(
        values: List<ConfigProfile>,
        selected: String?,
        connected: String?,
        hideIp: Boolean,
        gridMode: Boolean
    ) {
        differ.submitList(
            values.map { profile ->
                Row(
                    profile = profile,
                    selected = profile.id == selected,
                    connected = profile.id == connected,
                    hideIp = hideIp,
                    gridMode = gridMode
                )
            }
        )
    }

    override fun getItemId(position: Int): Long =
        differ.currentList[position].profile.id.hashCode().toLong()

    override fun getItemViewType(position: Int): Int =
        if (differ.currentList[position].gridMode) VIEW_GRID else VIEW_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        if (viewType == VIEW_GRID) {
            LegacyLayoutBridge.row_profile_grid(parent.context)
        } else {
            LegacyLayoutBridge.row_profile(parent.context)
        }
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = differ.currentList[position]
        holder.bind(
            profile = row.profile,
            selected = row.selected,
            connected = row.connected,
            hideIp = row.hideIp
        )
    }

    override fun getItemCount(): Int = differ.currentList.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.nameText)
        private val detail = view.findViewById<TextView>(R.id.detailText)
        private val location = view.findViewById<TextView>(R.id.locationText)
        private val status = view.findViewById<TextView>(R.id.statusText)
        private val tcpLatency = view.findViewById<TextView>(R.id.tcpLatencyText)
        private val realLatency = view.findViewById<TextView>(R.id.realLatencyText)
        private val ping = view.findViewById<TextView>(R.id.pingActionText)
        private val action = view.findViewById<TextView>(R.id.actionText)

        fun bind(
            profile: ConfigProfile,
            selected: Boolean,
            connected: Boolean,
            hideIp: Boolean
        ) {
            itemView.setBackgroundResource(
                if (profile.favorite) R.drawable.row_background_favorite
                else R.drawable.row_background
            )
            itemView.isActivated = selected
            itemView.alpha = if (profile.enabled) 1f else 0.52f

            name.text = profile.name
            detail.text = if (hideIp) {
                itemView.context.getString(
                    R.string.profile_detail_private,
                    profile.protocol.uppercase(),
                    profile.group
                )
            } else {
                itemView.context.getString(
                    R.string.profile_detail,
                    profile.protocol.uppercase(),
                    profile.server,
                    profile.port,
                    profile.group
                )
            }

            val locationValue = buildString {
                profile.exitFlag.takeIf(String::isNotBlank)?.let {
                    append(it)
                    append(' ')
                }
                profile.exitCountry.takeIf(String::isNotBlank)?.let(::append)
                if (!hideIp && profile.exitIp.isNotBlank()) {
                    if (isNotBlank()) append(" • ")
                    append(profile.exitIp)
                }
            }
            location.text = locationValue
            location.visibility = when {
                locationValue.isNotBlank() -> View.VISIBLE
                rowIsGrid() -> View.INVISIBLE
                else -> View.GONE
            }

            val statusValue = when {
                connected -> itemView.context.getString(R.string.state_connected)
                profile.testStatus == TestStatus.TESTING ->
                    itemView.context.getString(R.string.status_testing)
                else -> ""
            }
            status.text = statusValue
            status.visibility = when {
                statusValue.isNotBlank() -> View.VISIBLE
                rowIsGrid() -> View.INVISIBLE
                else -> View.GONE
            }

            val wireGuard = profile.protocol.equals(
                "wireguard",
                ignoreCase = true
            )
            /*
             * A raw TCP socket to a WireGuard endpoint cannot prove a UDP
             * handshake. Never keep displaying an old TCP number as a usable WG
             * result; tapping that metric transparently runs the real Xray test.
             */
            tcpLatency.text = if (wireGuard) {
                itemView.context.getString(R.string.tcp_result_empty)
            } else {
                metricText(
                    value = profile.tcpLatencyMs,
                    status = profile.tcpTestStatus,
                    valueRes = R.string.tcp_result_value,
                    failedRes = R.string.tcp_result_failed,
                    emptyRes = R.string.tcp_result_empty
                )
            }
            realLatency.text = metricText(
                value = profile.realLatencyMs,
                status = profile.realTestStatus,
                valueRes = R.string.real_result_value,
                failedRes = R.string.real_result_failed,
                emptyRes = R.string.real_result_empty
            )

            ping.text = if (profile.testStatus == TestStatus.TESTING) "…" else "⚡"
            action.text = "⋮"
            action.contentDescription =
                itemView.context.getString(R.string.config_actions)
            ping.contentDescription =
                itemView.context.getString(R.string.ping_now)

            itemView.setOnClickListener { onClick(profile) }
            itemView.setOnLongClickListener {
                onLongClick(profile)
                true
            }
            tcpLatency.setOnClickListener {
                if (wireGuard) onRealPing(profile) else onTcpPing(profile)
            }
            realLatency.setOnClickListener { onRealPing(profile) }
            ping.setOnClickListener {
                if (wireGuard) onRealPing(profile) else onTcpPing(profile)
            }
            action.setOnClickListener { onAction(profile) }
        }

        private fun metricText(
            value: Long?,
            status: TestStatus,
            valueRes: Int,
            failedRes: Int,
            emptyRes: Int
        ): String = when {
            value != null && status == TestStatus.ALIVE ->
                itemView.context.getString(valueRes, value)

            status == TestStatus.DEAD || status == TestStatus.ERROR ->
                itemView.context.getString(failedRes)

            else -> itemView.context.getString(emptyRes)
        }

        private fun rowIsGrid(): Boolean =
            itemViewType == VIEW_GRID
    }

    companion object {
        private const val VIEW_LIST = 0
        private const val VIEW_GRID = 1
    }
}
