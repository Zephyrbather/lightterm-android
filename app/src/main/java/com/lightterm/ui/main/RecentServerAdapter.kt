package com.lightterm.ui.main

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lightterm.R
import com.lightterm.databinding.ItemRecentServerBinding
import com.lightterm.domain.model.ServerConfig

class RecentServerAdapter(
    private val onOpenServer: (ServerConfig) -> Unit,
) : ListAdapter<ServerConfig, RecentServerAdapter.RecentServerViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecentServerViewHolder {
        val binding = ItemRecentServerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return RecentServerViewHolder(binding, onOpenServer)
    }

    override fun onBindViewHolder(
        holder: RecentServerViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    class RecentServerViewHolder(
        private val binding: ItemRecentServerBinding,
        private val onOpenServer: (ServerConfig) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(server: ServerConfig) {
            binding.aliasText.text = server.alias
            binding.targetText.text = server.targetLabel()
            binding.metaText.text = binding.root.context.getString(
                R.string.home_recent_server_last_opened,
                DateUtils.getRelativeTimeSpanString(
                    server.lastUsedAtEpochMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ),
            )

            val openAction = { onOpenServer(server) }
            binding.root.setOnClickListener { openAction() }
            binding.connectButton.setOnClickListener { openAction() }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ServerConfig>() {
        override fun areItemsTheSame(
            oldItem: ServerConfig,
            newItem: ServerConfig,
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: ServerConfig,
            newItem: ServerConfig,
        ): Boolean = oldItem == newItem
    }
}
