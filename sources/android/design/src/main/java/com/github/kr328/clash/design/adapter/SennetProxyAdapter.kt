package com.github.kr328.clash.design.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.google.android.material.card.MaterialCardView

class SennetProxyAdapter(
    private val onSelect: (String) -> Unit,
    private val onToggleFavorite: (String) -> Unit,
) : ListAdapter<SennetProxyAdapter.Item, SennetProxyAdapter.Holder>(DiffCallback) {
    data class Item(
        val name: String,
        val title: String,
        val subtitle: String,
        val delayText: String,
        val delayColor: Int,
        val flag: String,
        val favorite: Boolean,
        val selected: Boolean,
    )

    class Holder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_sennet_proxy, parent, false)
    ) {
        val rootCard: MaterialCardView = itemView.findViewById(R.id.root_card)
        val flagText: TextView = itemView.findViewById(R.id.flag_text)
        val titleText: TextView = itemView.findViewById(R.id.title_text)
        val subtitleText: TextView = itemView.findViewById(R.id.subtitle_text)
        val delayText: TextView = itemView.findViewById(R.id.delay_text)
        val favoriteButton: TextView = itemView.findViewById(R.id.favorite_button)
    }

    init {
        setHasStableIds(true)
    }

    fun submitItems(value: List<Item>) {
        submitList(value)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).name.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(parent)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)

        holder.flagText.text = item.flag
        holder.titleText.text = item.title
        holder.subtitleText.text = item.subtitle
        holder.delayText.text = item.delayText
        holder.delayText.setTextColor(item.delayColor)
        holder.favoriteButton.text = if (item.favorite) "\u2605" else "\u2606"
        holder.favoriteButton.setTextColor(
            if (item.favorite) Color.parseColor("#FFC94A")
            else ContextCompat.getColor(holder.itemView.context, R.color.sennet_text_secondary)
        )

        holder.rootCard.setCardBackgroundColor(
            if (item.selected) ContextCompat.getColor(holder.itemView.context, R.color.sennet_surface_selected)
            else ContextCompat.getColor(holder.itemView.context, R.color.sennet_surface_card)
        )
        holder.rootCard.strokeColor =
            if (item.selected) ContextCompat.getColor(holder.itemView.context, R.color.sennet_border_focus)
            else ContextCompat.getColor(holder.itemView.context, R.color.sennet_border_soft)
        holder.rootCard.strokeWidth = if (item.selected) 2 else 1
        val density = holder.itemView.resources.displayMetrics.density
        holder.rootCard.cardElevation = density * if (item.selected) 8f else 4f

        holder.rootCard.setOnClickListener { onSelect(item.name) }
        holder.favoriteButton.setOnClickListener { onToggleFavorite(item.name) }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean {
            return oldItem == newItem
        }
    }
}
