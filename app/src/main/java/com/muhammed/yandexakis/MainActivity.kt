package com.muhammed.yandexakis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.net.URLEncoder

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity(), YandexScraper.Listener {

    private lateinit var web: WebView
    private lateinit var pager: ViewPager2
    private lateinit var status: TextView
    private lateinit var queryBox: EditText
    private lateinit var auto: CheckBox
    private lateinit var captchaHint: TextView

    private lateinit var player: ExoPlayer
    private lateinit var scraper: YandexScraper
    private lateinit var adapter: FeedAdapter

    private val items = ArrayList<VideoItem>()
    private var current = -1
    private var attached: PlayerView? = null
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        pager = findViewById(R.id.pager)
        status = findViewById(R.id.status)
        queryBox = findViewById(R.id.query)
        auto = findViewById(R.id.auto)
        captchaHint = findViewById(R.id.captchaHint)

        val prefs = getSharedPreferences("ayar", Context.MODE_PRIVATE)
        auto.isChecked = prefs.getBoolean("oto", false)

        // Oynatıcı: önizleme klipleri bir oynatma listesi olarak sıralanır
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(YandexScraper.DESKTOP_UA)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://yandex.com.tr/"))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        player.repeatMode = repeatFor(auto.isChecked)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // "Oto" açıkken klip bitince bir sonrakine kendiliğinden kay
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    val i = player.currentMediaItemIndex
                    if (i != pager.currentItem) pager.setCurrentItem(i, true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Oynatılamayan klibi atla
                val next = current + 1
                if (next < items.size) pager.setCurrentItem(next, true)
                else Toast.makeText(this@MainActivity, "Klip oynatılamadı", Toast.LENGTH_SHORT).show()
            }
        })

        adapter = FeedAdapter(
            items,
            onBound = { vh, pos -> if (pos == current) attach(vh.playerView) },
            onRecycled = { vh ->
                if (vh.playerView === attached) {
                    vh.playerView.player = null
                    attached = null
                }
            },
            onOpen = { openOriginal(it) },
            onTap = { player.playWhenReady = !player.playWhenReady }
        )
        scraper = YandexScraper(web, this)
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position < items.size) playAt(position)
                if (items.size - position <= 3) scraper.more()
            }
        })

        auto.setOnCheckedChangeListener { _, checked ->
            player.repeatMode = repeatFor(checked)
            prefs.edit().putBoolean("oto", checked).apply()
        }
        findViewById<Button>(R.id.searchBtn).setOnClickListener { startSearch() }
        queryBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
        }

        val last = prefs.getString("son", "") ?: ""
        queryBox.setText(last)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (t.isNotEmpty()) { queryBox.setText(t); startSearch() }
        }
    }

    private fun repeatFor(autoNext: Boolean) =
        if (autoNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE

    private fun startSearch() {
        val q = queryBox.text.toString().trim()
        if (q.isEmpty()) return
        lastQuery = q
        getSharedPreferences("ayar", Context.MODE_PRIVATE).edit().putString("son", q).apply()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(queryBox.windowToken, 0)
        queryBox.clearFocus()

        player.stop()
        player.clearMediaItems()
        attached?.player = null
        attached = null
        current = -1
        items.clear()
        adapter.notifyDataSetChanged()
        status.text = "“$q” aranıyor…"
        status.visibility = View.VISIBLE
        scraper.search(q)
    }

    private fun playAt(pos: Int) {
        current = pos
        if (player.currentMediaItemIndex != pos || player.playbackState == Player.STATE_ENDED) {
            player.seekTo(pos, 0)
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = true
        pager.post {
            val rv = pager.getChildAt(0) as? RecyclerView
            val vh = rv?.findViewHolderForAdapterPosition(pos) as? FeedAdapter.VH
            if (vh != null) attach(vh.playerView)
        }
    }

    private fun attach(view: PlayerView) {
        if (attached === view) return
        PlayerView.switchTargetView(player, attached, view)
        attached = view
    }

    private fun openOriginal(item: VideoItem) {
        val url = item.original
            ?: ("https://yandex.com.tr/video/search?text=" +
                URLEncoder.encode(item.title.ifBlank { lastQuery }, "UTF-8"))
        try {
            player.pause()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- YandexScraper.Listener ----

    override fun onItems(items: List<VideoItem>) {
        val start = this.items.size
        this.items.addAll(items)
        player.addMediaItems(items.map { MediaItem.fromUri(it.preview) })
        adapter.notifyItemRangeInserted(start, items.size)
        // sıra numaralarındaki toplamı güncelle
        adapter.notifyItemRangeChanged(0, start)
        status.visibility = View.GONE
        if (start == 0) {
            pager.setCurrentItem(0, false)
            playAt(0)
        }
    }

    override fun onCaptcha(visible: Boolean) {
        web.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        pager.visibility = if (visible) View.INVISIBLE else View.VISIBLE
        captchaHint.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            status.visibility = View.GONE
            player.pause()
        } else if (items.isEmpty()) {
            status.visibility = View.VISIBLE
        }
    }

    override fun onRoundEnd(newCount: Int, exhausted: Boolean) {
        if (items.isEmpty() && exhausted) {
            status.text = "Önizlemesi olan video bulunamadı.\nBaşka bir arama deneyin."
            status.visibility = View.VISIBLE
        } else if (exhausted && current >= items.size - 1) {
            Toast.makeText(this, "Bu arama için başka video yok", Toast.LENGTH_SHORT).show()
        } else if (!exhausted && items.size - maxOf(current, 0) <= 3) {
            scraper.more()
        }
    }

    // ---- yaşam döngüsü ----

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onResume() {
        super.onResume()
        if (items.isNotEmpty() && web.visibility != View.VISIBLE) player.play()
    }

    override fun onDestroy() {
        player.release()
        web.destroy()
        super.onDestroy()
    }
}
