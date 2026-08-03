package com.trivox.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
    private val items =
        mutableListOf<ConfigProfile>()
    private var selectedId: String? = null
    private var connectedId: String? = null

    init {
        setHasStableIds(true)
    }

    fun submit(
        values: List<ConfigProfile>,
        selected: String?,
        connected: String?
    ) {
        items.clear()
        items.addAll(values)
        selectedId = selected
        connectedId = connected
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long =
        items[position].id.hashCode().toLong()

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
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

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

        fun bind(profile: ConfigProfile) {
            val connected =
                profile.id == connectedId

            itemView.isActivated =
                profile.id == selectedId
            itemView.alpha =
                if (profile.enabled) 1f else 0.52f

            favorite.text =
                if (profile.favorite) "★" else "☆"
            name.text = profile.name
            detail.text =
                itemView.context.getString(
                    R.string.profile_detail,
                    profile.protocol.uppercase(),
                    profile.server,
                    profile.port,
                    profile.group
                )

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

                    if (profile.exitIp.isNotBlank()) {
                        if (isNotBlank()) append(" • ")
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
                    connected ->
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
                    itemView.context.getString(
                        R.string.ping_short
                    )
                }

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
