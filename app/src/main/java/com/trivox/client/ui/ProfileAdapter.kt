package com.trivox.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.trivox.client.R
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.PingMethod
import com.trivox.client.data.TestStatus

class ProfileAdapter(
    private val onClick:
        (ConfigProfile) -> Unit,
    private val onLongClick:
        (ConfigProfile) -> Unit,
    private val onAction:
        (ConfigProfile) -> Unit,
    private val onPing:
        (ConfigProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    private data class Row(
        val profile: ConfigProfile,
        val selected: Boolean,
        val connected: Boolean,
        val hideIp: Boolean
    )

    private val differ =
        AsyncListDiffer(
            this,
            object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(
                    oldItem: Row,
                    newItem: Row
                ): Boolean =
                    oldItem.profile.id ==
                        newItem.profile.id

                override fun areContentsTheSame(
                    oldItem: Row,
                    newItem: Row
                ): Boolean =
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
        hideIp: Boolean
    ) {
        differ.submitList(
            values.map { profile ->
                Row(
                    profile = profile,
                    selected =
                        profile.id == selected,
                    connected =
                        profile.id == connected,
                    hideIp = hideIp
                )
            }
        )
    }

    override fun getItemId(
        position: Int
    ): Long =
        differ.currentList[
            position
        ].profile.id.hashCode().toLong()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ) = Holder(
        LayoutInflater
            .from(parent.context)
            .inflate(
                R.layout.row_profile,
                parent,
                false
            )
    )

    override fun onBindViewHolder(
        holder: Holder,
        position: Int
    ) {
        holder.bind(
            differ.currentList[position]
        )
    }

    override fun getItemCount(): Int =
        differ.currentList.size

    inner class Holder(view: View) :
        RecyclerView.ViewHolder(view) {
        private val favorite =
            view.findViewById<TextView>(
                R.id.favoriteText
            )
        private val name =
            view.findViewById<TextView>(
                R.id.nameText
            )
        private val detail =
            view.findViewById<TextView>(
                R.id.detailText
            )
        private val location =
            view.findViewById<TextView>(
                R.id.locationText
            )
        private val status =
            view.findViewById<TextView>(
                R.id.statusText
            )
        private val latency =
            view.findViewById<TextView>(
                R.id.latencyText
            )
        private val ping =
            view.findViewById<TextView>(
                R.id.pingActionText
            )
        private val action =
            view.findViewById<TextView>(
                R.id.actionText
            )

        fun bind(row: Row) {
            val profile = row.profile

            itemView.isActivated = row.selected
            itemView.alpha =
                if (profile.enabled) 1f else 0.52f

            favorite.text =
                if (profile.favorite) "★" else "☆"
            name.text = profile.name
            detail.text =
                if (row.hideIp) {
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

            val locationValue =
                buildString {
                    profile.exitFlag
                        .takeIf(String::isNotBlank)
                        ?.let {
                            append(it)
                            append(' ')
                        }
                    profile.exitCountry
                        .takeIf(String::isNotBlank)
                        ?.let(::append)

                    if (
                        !row.hideIp &&
                        profile.exitIp.isNotBlank()
                    ) {
                        if (isNotBlank()) {
                            append(" • ")
                        }
                        append(profile.exitIp)
                    }
                }

            location.text = locationValue
            location.visibility =
                if (locationValue.isBlank()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

            val statusValue =
                when {
                    row.connected ->
                        itemView.context.getString(
                            R.string.state_connected
                        )

                    profile.testStatus ==
                        TestStatus.TESTING ->
                        itemView.context.getString(
                            R.string.status_testing
                        )

                    else -> ""
                }

            status.text = statusValue
            status.visibility =
                if (statusValue.isBlank()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

            latency.text =
                profile.latencyMs
                    ?.let { value ->
                        itemView.context.getString(
                            R.string.latency_method_format,
                            pingMethodLabel(
                                profile.latencyMethod
                            ),
                            value
                        )
                    }
                    ?: "—"

            ping.text =
                if (
                    profile.testStatus ==
                    TestStatus.TESTING
                ) {
                    "…"
                } else {
                    "⚡"
                }
            action.text = "⋮"

            action.contentDescription =
                itemView.context.getString(
                    R.string.config_actions
                )
            ping.contentDescription =
                itemView.context.getString(
                    R.string.ping_now
                )

            itemView.setOnClickListener {
                onClick(profile)
            }
            itemView.setOnLongClickListener {
                onLongClick(profile)
                true
            }
            latency.setOnClickListener {
                onPing(profile)
            }
            ping.setOnClickListener {
                onPing(profile)
            }
            action.setOnClickListener {
                onAction(profile)
            }
        }

        private fun pingMethodLabel(
            stored: String
        ): String =
            when (
                PingMethod.fromStored(
                    stored,
                    PingMethod.TCP_CONNECT
                )
            ) {
                PingMethod.TCP_CONNECT ->
                    itemView.context.getString(
                        R.string.ping_method_tcp_short
                    )

                PingMethod.XRAY_HTTP ->
                    itemView.context.getString(
                        R.string.ping_method_xray_short
                    )
            }
    }
}
