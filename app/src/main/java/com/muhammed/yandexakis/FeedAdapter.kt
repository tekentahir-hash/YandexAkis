package com.muhammed.yandexakis

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class FeedAdapter(
    private val items: List<VideoItem>,
    private val onBound: (VH, Int) -> Unit,
    private val onRecycled: (VH) -> Unit,
    private val onOpen: (VideoItem) -> Unit,
    private val onTap: (VH) -> Unit,
    private val onGifFailed: (Int) -> Unit
) : RecyclerView.Adapter<FeedAdapter.VH>() {

    /** true: hassas içerik bulanık açılır; false: her şey doğrudan gösterilir */
    var blurSensitive = true

    /** Gizli sekmede GIF'ler diske kaydedilmez */
    var incognito = true

    /** Kullanıcının "Göster" / "Bulanıklaştır" ile tek tek yaptığı seçimler */
    private val choice = HashMap<String, Boolean>() // dosya -> bulanık mı

    fun resetChoices() = choice.clear()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val playerView: PlayerView = v.findViewById(R.id.player)
        val gif: ImageView = v.findViewById(R.id.gif)
        val badge: TextView = v.findViewById(R.id.badge)
        val title: TextView = v.findViewById(R.id.title)
        val source: TextView = v.findViewById(R.id.source)
        val index: TextView = v.findViewById(R.id.index)
        val open: Button = v.findViewById(R.id.open)
        val cover: View = v.findViewById(R.id.blurCover)
        val reveal: Button = v.findViewById(R.id.reveal)
        val toggle: Button = v.findViewById(R.id.blurToggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    private fun isBlurred(item: VideoItem) =
        choice[item.preview] ?: (item.sensitive && blurSensitive)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title.ifBlank { if (item.isGif) "(başlıksız GIF)" else "(başlıksız video)" }
        holder.source.text = item.host ?: ""
        holder.index.text = "${position + 1} / ${items.size}"
        holder.badge.text = if (item.isGif) "GIF" else "VİDEO"
        holder.open.text = if (item.isGif) "Kaynağı aç" else "Tam videoyu aç"
        holder.open.setOnClickListener { onOpen(item) }
        holder.itemView.setOnClickListener { onTap(holder) }
        holder.playerView.setOnClickListener { onTap(holder) }
        holder.gif.setOnClickListener { onTap(holder) }

        if (item.isGif) {
            holder.playerView.visibility = View.INVISIBLE
            holder.gif.visibility = View.VISIBLE
            // GIF sonsuz döngüde oynar
            Glide.with(holder.gif)
                .load(item.preview)
                .diskCacheStrategy(if (incognito) DiskCacheStrategy.NONE else DiskCacheStrategy.AUTOMATIC)
                .skipMemoryCache(false)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
                    ): Boolean {
                        val p = holder.bindingAdapterPosition
                        if (p != RecyclerView.NO_POSITION) onGifFailed(p)
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>?,
                        dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean = false
                })
                .into(holder.gif)
        } else {
            Glide.with(holder.gif).clear(holder.gif)
            holder.gif.visibility = View.GONE
            holder.playerView.visibility = View.VISIBLE
        }

        holder.reveal.setOnClickListener {
            choice[item.preview] = false
            applyBlur(holder, item)
        }
        holder.toggle.setOnClickListener {
            choice[item.preview] = !isBlurred(item)
            applyBlur(holder, item)
        }
        applyBlur(holder, item)
        onBound(holder, position)
    }

    private fun applyBlur(h: VH, item: VideoItem) {
        val blurred = isBlurred(item)
        if (Build.VERSION.SDK_INT >= 31) {
            val fx = if (blurred) RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP) else null
            h.playerView.setRenderEffect(fx)
            h.gif.setRenderEffect(fx)
            h.cover.setBackgroundColor(Color.parseColor("#66000000"))
        } else {
            // Eski Android'de bulanıklık yok: tamamen kapat
            h.cover.setBackgroundColor(Color.BLACK)
        }
        h.cover.visibility = if (blurred) View.VISIBLE else View.GONE
        // Açık her içeriği elle de bulanıklaştırabilirsiniz
        h.toggle.visibility = if (blurred) View.GONE else View.VISIBLE
    }

    override fun onViewRecycled(holder: VH) {
        Glide.with(holder.gif).clear(holder.gif)
        onRecycled(holder)
    }
}
