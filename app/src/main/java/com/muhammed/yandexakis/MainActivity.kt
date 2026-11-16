package com.muhammed.yandexakis

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

    private lateinit var webVideo: WebView
    private lateinit var webGif: WebView
    private lateinit var pager: ViewPager2
    private lateinit var status: TextView
    private lateinit var queryBox: EditText
    private lateinit var captchaHint: TextView

    private lateinit var player: ExoPlayer
    private lateinit var videoScraper: YandexScraper
    private lateinit var gifScraper: YandexScraper
    private lateinit var adapter: FeedAdapter

    private val items = ArrayList<VideoItem>()
    private val handler = Handler(Looper.getMainLooper())
    private var current = -1
    private var attached: PlayerView? = null
    private var lastQuery = ""
    private var autoNext = false

    /** 0 = video + GIF karışık, 1 = sadece video, 2 = sadece GIF */
    private var contentMode = 0

    /** Arama sürerken ekranda gösterilen son durum (her kaynak için) */
    private val progress = LinkedHashMap<String, String>()

    /** ⚙ uzun basma: 0 akış, 1 video sayfası, 2 GIF sayfası */
    private var debugView = 0

    private val gifAdvance = Runnable { goNext() }

    private val scrapers get() = listOf(videoScraper, gifScraper)
    private fun active(s: YandexScraper) = when (contentMode) {
        1 -> s.kind == YandexScraper.Kind.VIDEO
        2 -> s.kind == YandexScraper.Kind.GIF
        else -> true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webVideo = findViewById(R.id.web)
        webGif = findViewById(R.id.webGif)
        pager = findViewById(R.id.pager)
        status = findViewById(R.id.status)
        queryBox = findViewById(R.id.query)
        captchaHint = findViewById(R.id.captchaHint)

        val prefs = getSharedPreferences("ayar", Context.MODE_PRIVATE)
        autoNext = prefs.getBoolean("oto", false)
        contentMode = prefs.getInt("icerik", 0)

        // Oynatıcı: her seferinde yalnızca seçili videonun klibini oynatır
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(YandexScraper.DESKTOP_UA)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://yandex.com.tr/"))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        player.repeatMode = repeatFor(autoNext)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // "Oto" açıkken klip bitince sonrakine kay
                if (state == Player.STATE_ENDED && autoNext) goNext()
            }

            override fun onPlayerError(error: PlaybackException) {
                goNext() // oynatılamayan klibi atla
            }
        })

        adapter = FeedAdapter(
            items,
            onBound = { vh, pos -> if (pos == current && !items[pos].isGif) attach(vh.playerView) },
            onRecycled = { vh ->
                if (vh.playerView === attached) {
                    vh.playerView.player = null
                    attached = null
                }
            },
            onOpen = { openOriginal(it) },
            onTap = { vh -> togglePause(vh) },
            onGifFailed = { pos -> if (pos == current) goNext() }
        )

        videoScraper = YandexScraper(webVideo, YandexScraper.Kind.VIDEO, this)
        gifScraper = YandexScraper(webGif, YandexScraper.Kind.GIF, this)
        for (s in scrapers) {
            s.safeLevel = prefs.getInt("filtre", 1)
            s.incognito = prefs.getBoolean("gizli", true)
        }
        adapter.blurSensitive = prefs.getBoolean("bulanik", true)
        adapter.incognito = videoScraper.incognito

        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position < items.size && position != current) playAt(position)
                if (items.size - position <= 3) loadMore()
            }
        })

        findViewById<Button>(R.id.settingsBtn).apply {
            setOnClickListener { showSettings() }
            // Uzun bas: arka plandaki Yandex sayfalarını sırayla göster (sorun giderme)
            setOnLongClickListener { cycleDebugView(); true }
        }
        findViewById<Button>(R.id.searchBtn).setOnClickListener { startSearch() }
        queryBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
        }

        if (videoScraper.incognito) prefs.edit().remove("son").apply()
        else queryBox.setText(prefs.getString("son", "") ?: "")
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

    private fun cycleDebugView() {
        debugView = (debugView + 1) % 3
        webVideo.visibility = if (debugView == 1) View.VISIBLE else View.INVISIBLE
        webGif.visibility = if (debugView == 2) View.VISIBLE else View.INVISIBLE
        pager.visibility = if (debugView == 0) View.VISIBLE else View.INVISIBLE
        if (debugView != 0) {
            webVideo.settings.blockNetworkImage = false
            webGif.settings.blockNetworkImage = false
            player.pause()
        }
        val msg = when (debugView) {
            1 -> "Yandex video sayfası"
            2 -> "Yandex GIF sayfası"
            else -> "Akışa dönüldü"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun startSearch() {
        val q = queryBox.text.toString().trim()
        if (q.isEmpty()) return
        lastQuery = q
        // Gizli sekmede son arama kaydedilmez
        if (!videoScraper.incognito) {
            getSharedPreferences("ayar", Context.MODE_PRIVATE).edit().putString("son", q).apply()
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(queryBox.windowToken, 0)
        queryBox.clearFocus()

        handler.removeCallbacks(gifAdvance)
        player.stop()
        player.clearMediaItems()
        attached?.player = null
        attached = null
        current = -1
        adapter.resetChoices()
        items.clear()
        adapter.notifyDataSetChanged()
        progress.clear()
        status.text = "“$q” aranıyor…"
        status.visibility = View.VISIBLE
        for (s in scrapers) if (active(s)) s.search(q) else s.stop()
    }

    private fun loadMore() {
        for (s in scrapers) if (active(s)) s.more()
    }

    private fun goNext() {
        val next = current + 1
        if (next < items.size) pager.setCurrentItem(next, true)
    }

    private fun togglePause(vh: FeedAdapter.VH) {
        val pos = vh.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION || pos >= items.size) return
        if (items[pos].isGif) {
            val d = vh.gif.drawable as? Animatable ?: return
            if (d.isRunning) d.stop() else d.start()
        } else {
            player.playWhenReady = !player.playWhenReady
        }
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("ayar", Context.MODE_PRIVATE)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            setPadding(0, pad / 2, 0, 0)
            textSize = 16f
        }
        fun radios(names: List<String>, base: Int, selected: Int) = RadioGroup(this).apply {
            names.forEachIndexed { i, n -> addView(RadioButton(context).apply { id = base + i; text = n }) }
            check(base + selected)
        }

        val gizli = CheckBox(this).apply {
            text = "Gizli sekmede ara (çerez, geçmiş ve önbellek saklanmaz)"
            isChecked = videoScraper.incognito
        }
        val oto = CheckBox(this).apply {
            text = "Bitince sonrakine otomatik geç (GIF'lerde 8 sn)"
            isChecked = autoNext
        }
        val bulanik = CheckBox(this).apply {
            text = "Hassas içeriği bulanıklaştır (\"Göster\" ile açılır)"
            isChecked = adapter.blurSensitive
        }
        val icerik = radios(listOf("Video + GIF (karışık)", "Sadece video", "Sadece GIF"), 2000, contentMode)
        val filtre = radios(
            listOf("Kapalı (filtre yok)", "Orta (varsayılan)", "Aile modu (sıkı)"), 1000, videoScraper.safeLevel
        )
        box.addView(label("Gösterilecek içerik")); box.addView(icerik)
        box.addView(gizli); box.addView(oto); box.addView(bulanik)
        box.addView(label("Güvenli arama")); box.addView(filtre)

        val scroll = android.widget.ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("Ayarlar")
            .setView(scroll)
            .setPositiveButton("Kaydet") { _, _ ->
                val level = (filtre.checkedRadioButtonId - 1000).coerceIn(0, 2)
                val mode = (icerik.checkedRadioButtonId - 2000).coerceIn(0, 2)
                val redo = level != videoScraper.safeLevel || mode != contentMode
                contentMode = mode
                for (s in scrapers) {
                    s.safeLevel = level
                    s.incognito = gizli.isChecked
                }
                adapter.incognito = gizli.isChecked
                autoNext = oto.isChecked
                player.repeatMode = repeatFor(autoNext)
                if (bulanik.isChecked != adapter.blurSensitive) {
                    adapter.blurSensitive = bulanik.isChecked
                    adapter.resetChoices()
                    adapter.notifyItemRangeChanged(0, items.size)
                }
                val e = prefs.edit()
                    .putInt("filtre", level)
                    .putInt("icerik", mode)
                    .putBoolean("gizli", gizli.isChecked)
                    .putBoolean("oto", autoNext)
                    .putBoolean("bulanik", bulanik.isChecked)
                if (gizli.isChecked) e.remove("son")
                e.apply()
                // Filtre ya da içerik türü değiştiyse aramayı yeni ayarla tekrarla
                if (redo && lastQuery.isNotEmpty()) startSearch()
                else if (autoNext && current in items.indices && items[current].isGif) playAt(current)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun playAt(pos: Int) {
        current = pos
        handler.removeCallbacks(gifAdvance)
        val item = items[pos]
        if (item.isGif) {
            // GIF kendi kendine döngüde oynar; oynatıcı durur
            player.stop()
            attached?.player = null
            attached = null
            if (autoNext) handler.postDelayed(gifAdvance, 8000)
            return
        }
        player.setMediaItem(MediaItem.fromUri(item.preview))
        player.prepare()
        player.playWhenReady = true
        pager.post {
            val rv = pager.getChildAt(0) as? RecyclerView
            val vh = rv?.findViewHolderForAdapterPosition(pos) as? FeedAdapter.VH
            if (vh != null && current == pos) attach(vh.playerView)
        }
    }

    private fun attach(view: PlayerView) {
        if (attached === view) return
        PlayerView.switchTargetView(player, attached, view)
        attached = view
    }

    private fun openOriginal(item: VideoItem) {
        val url = item.original
            ?: (if (item.isGif) item.preview
            else "https://yandex.com.tr/video/search?text=" +
                URLEncoder.encode(item.title.ifBlank { lastQuery }, "UTF-8"))
        try {
            player.pause()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- YandexScraper.Listener ----

    override fun onItems(src: YandexScraper, items: List<VideoItem>) {
        if (!active(src)) return
        val wasEmpty = this.items.isEmpty()
        // Henüz görülmemiş kuyruğu (şu anki ve bir sonraki hariç) yeni gelenlerle karıştır
        val keep = if (current < 0) 0 else minOf(this.items.size, current + 2)
        val tail = ArrayList(this.items.subList(keep, this.items.size))
        val mixed = mix(tail, items)
        this.items.subList(keep, this.items.size).clear()
        this.items.addAll(mixed)
        if (tail.isNotEmpty()) adapter.notifyItemRangeChanged(keep, tail.size)
        adapter.notifyItemRangeInserted(keep + tail.size, items.size)
        // sıra numaralarındaki toplamı güncelle
        if (keep > 0) adapter.notifyItemRangeChanged(0, keep)
        status.visibility = View.GONE
        if (wasEmpty) {
            pager.setCurrentItem(0, false)
            playAt(0)
        }
    }

    /** Videoları ve GIF'leri dönüşümlü, hafif rastgele bir sırayla birleştirir. */
    private fun mix(a: List<VideoItem>, b: List<VideoItem>): List<VideoItem> {
        val all = (a + b)
        val vids = ArrayDeque(all.filter { !it.isGif }.shuffled())
        val gifs = ArrayDeque(all.filter { it.isGif }.shuffled())
        val out = ArrayList<VideoItem>(all.size)
        var takeVideo = Math.random() < 0.5
        while (vids.isNotEmpty() || gifs.isNotEmpty()) {
            val q = if ((takeVideo && vids.isNotEmpty()) || gifs.isEmpty()) vids else gifs
            out.add(q.removeFirst())
            // çoğunlukla sırayla değiştir, ara sıra aynı türden iki tane gelsin
            if (Math.random() < 0.8) takeVideo = !takeVideo
        }
        return out
    }

    override fun onCaptcha(src: YandexScraper, visible: Boolean) {
        if (!active(src)) return
        src.web.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        val anyCaptcha = scrapers.any { it.web.visibility == View.VISIBLE }
        pager.visibility = if (anyCaptcha) View.INVISIBLE else View.VISIBLE
        captchaHint.visibility = if (anyCaptcha) View.VISIBLE else View.GONE
        if (visible) {
            status.visibility = View.GONE
            player.pause()
        } else if (items.isEmpty()) {
            status.visibility = View.VISIBLE
        }
    }

    override fun onProgress(src: YandexScraper, msg: String) {
        if (!active(src)) return
        progress[src.kind.label] = msg
        if (items.isEmpty()) {
            status.text = "“$lastQuery” aranıyor…\n\n" +
                progress.entries.joinToString("\n\n") { "[${it.key}]\n${it.value}" }
            status.visibility = View.VISIBLE
        }
    }

    override fun onRoundEnd(src: YandexScraper, newCount: Int, exhausted: Boolean) {
        val all = scrapers.filter { active(it) }
        val allDone = all.all { it.exhausted }
        if (items.isEmpty() && allDone) {
            status.text = "Sonuç bulunamadı.\n\n" +
                progress.entries.joinToString("\n\n") { "[${it.key}]\n${it.value}" } +
                "\n\n(Yandex sayfalarını görmek için ⚙ düğmesine uzun basın)"
            status.visibility = View.VISIBLE
        } else if (allDone && current >= items.size - 1) {
            Toast.makeText(this, "Bu arama için başka sonuç yok", Toast.LENGTH_SHORT).show()
        } else if (!exhausted && items.size - maxOf(current, 0) <= 3) {
            src.more()
        }
    }

    // ---- yaşam döngüsü ----

    override fun onPause() {
        super.onPause()
        player.pause()
        handler.removeCallbacks(gifAdvance)
    }

    override fun onResume() {
        super.onResume()
        if (current in items.indices && debugView == 0 && !items[current].isGif) player.play()
    }

    override fun onDestroy() {
        if (videoScraper.incognito) videoScraper.wipe()
        handler.removeCallbacksAndMessages(null)
        player.release()
        webVideo.destroy()
        webGif.destroy()
        super.onDestroy()
    }
}
