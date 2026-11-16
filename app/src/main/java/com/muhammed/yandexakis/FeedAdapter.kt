package com.muhammed.yandexakis

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView

class FeedAdapter(
    private val items: List<VideoItem>,
    private val onBound: (VH, Int) -> Unit,
    private val onRecycled: (VH) -> Unit,
    private val onOpen: (VideoItem) -> Unit,
    private val onTap: () -> Unit
) : RecyclerView.Adapter<FeedAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val playerView: PlayerView = v.findViewById(R.id.player)
        val title: TextView = v.findViewById(R.id.title)
        val source: TextView = v.findViewById(R.id.source)
        val index: TextView = v.findViewById(R.id.index)
        val open: Button = v.findViewById(R.id.open)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title.ifBlank { "(başlıksız video)" }
        holder.source.text = item.host ?: ""
        holder.index.text = "${position + 1} / ${items.size}"
        holder.open.setOnClickListener { onOpen(item) }
        holder.itemView.setOnClickListener { onTap() }
        holder.playerView.setOnClickListener { onTap() }
        onBound(holder, position)
    }

    override fun onViewRecycled(holder: VH) {
        onRecycled(holder)
    }
}
