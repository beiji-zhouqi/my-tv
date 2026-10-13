package com.lizongying.mytv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.models.TVViewModel

/**
 * Adapter for the channel list in the middle.
 */
class ChannelAdapter(
    private val onChannelClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private var channels: List<TVViewModel> = emptyList()
    private var selectedPosition = 0

    fun submitList(list: List<TVViewModel>) {
        channels = list
        notifyDataSetChanged()
    }

    fun setSelected(position: Int) {
        if (position == selectedPosition) return
        val old = selectedPosition
        selectedPosition = position
        if (old in 0 until itemCount) notifyItemChanged(old)
        if (position in 0 until itemCount) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        holder.bind(channel, position == selectedPosition)
        holder.itemView.setOnClickListener { onChannelClick(position) }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                holder.itemView.setBackgroundColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.focus)
                )
            } else if (position == selectedPosition) {
                holder.itemView.setBackgroundColor(0x20FFFFFF)
            } else {
                holder.itemView.setBackgroundColor(0x08FFFFFF)
            }
        }
    }

    override fun getItemCount() = channels.size

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.channel_name)

        fun bind(tvViewModel: TVViewModel, isSelected: Boolean) {
            textView.text = tvViewModel.getTV().title
            itemView.setBackgroundColor(
                if (itemView.hasFocus()) ContextCompat.getColor(itemView.context, R.color.focus)
                else if (isSelected) 0x20FFFFFF else 0x08FFFFFF
            )
        }
    }
}
