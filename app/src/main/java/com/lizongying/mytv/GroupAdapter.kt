package com.lizongying.mytv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for the group (category) list on the left.
 */
class GroupAdapter(
    private val onGroupClick: (Int) -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

    private var groups: List<String> = emptyList()
    private var selectedPosition = 0

    fun submitList(list: List<String>) {
        groups = list
        notifyDataSetChanged()
    }

    fun setSelected(position: Int) {
        if (position == selectedPosition) return
        val old = selectedPosition
        selectedPosition = position
        if (old in 0 until itemCount) notifyItemChanged(old)
        if (position in 0 until itemCount) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]
        holder.bind(group, position == selectedPosition)

        holder.itemView.setOnClickListener {
            onGroupClick(position)
        }

        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.setBackgroundColor(
                    ContextCompat.getColor(view.context, R.color.focus)
                )
                // Trigger selection when focused
                onGroupClick(position)
            } else if (position == selectedPosition) {
                view.setBackgroundColor(0x30FFFFFF)
            } else {
                view.setBackgroundColor(0x10FFFFFF)
            }
        }
    }

    override fun getItemCount() = groups.size

    class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.group_name)

        fun bind(name: String, isSelected: Boolean) {
            textView.text = name
            itemView.setBackgroundColor(
                if (itemView.hasFocus()) ContextCompat.getColor(itemView.context, R.color.focus)
                else if (isSelected) 0x30FFFFFF else 0x10FFFFFF
            )
        }
    }
}
