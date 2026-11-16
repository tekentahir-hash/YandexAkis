package com.muhammed.yandexakis

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder

data class VideoItem(
    val preview: String,   // Yandex'in animasyonlu özet klibi (mp4)
    val title: String,
    val original: String?, // videonun asıl sayfası (YouTube, VK vb.)
    val host: String?,
    val sensitive: Boolean = false // Yandex'in "bulanıklaştır" işaretlediği içerik
)

/**
 * Yandex video arama sayfasını görünmez bir WebView'da açar ve sayfanın
 * içindeki video-preview.s3.yandex.net/....mp4 önizleme bağlantılarını toplar.
 * API anahtarı yok, ücret yok: tamamen telefonda çalışır.
 */
class YandexScraper(private val web: WebView, private val listener: Listener) {

    interface Listener {
        fun onItems(items: List<VideoItem>)
        fun onCaptcha(visible: Boolean)
        fun onRoundEnd(newCount: Int, exhausted: Boolean)
        fun onProgress(msg: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var query = ""
    private var page = 0
    private var misses = 0
    private var token = 0
    private var pending: Runnable? = null
    private val seen = HashSet<String>()

    var busy = false
        private set
    var exhausted = false
        private set

    /** Güvenli arama: 0 = kapalı, 1 = orta (Yandex varsayılanı), 2 = aile modu */
    var safeLevel = 1

    /** Gizli sekme: çerez, önbellek ve geçmiş kalıcı olarak saklanmaz */
    var incognito = true
        set(value) {
            field = value
            web.settings.cacheMode =
                if (value) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            if (value) wipe()
        }

    /** Gizli sekmenin izlerini siler: çerezler, site verileri, önbellek, geçmiş */
    fun wipe() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web.clearCache(true)
        web.clearHistory()
        web.clearFormData()
    }

    init {
        setup()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            blockNetworkImage = true // veri tasarrufu; doğrulamada açılır
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageFinished(url)
            }
        }
    }

    fun search(q: String) {
        query = q
        page = 0
        misses = 0
        exhausted = false
        seen.clear()
        token++
        busy = true
        load()
    }

    /** Akışın sonuna yaklaşınca daha fazla video getirir. */
    fun more() {
        if (busy || exhausted || query.isEmpty()) return
        busy = true
        scrollAndExtract()
    }

    private fun load() {
        applySafeSearch()
        val family = when (safeLevel) { 0 -> "&family=no"; 2 -> "&family=yes"; else -> "" }
        val url = "https://yandex.com.tr/video/search?text=" +
            URLEncoder.encode(query, "UTF-8") + family + (if (page > 0) "&p=$page" else "")
        web.loadUrl(url)
        // Sayfa hiç bitmezse yine de dene
        val t = token
        schedule(15000) { if (t == token && busy) extract(t, fromLoad = true) }
    }

    /** Yandex'in arama ayarı çerezi (sp.family) ile filtre düzeyini bildirir. */
    private fun applySafeSearch() {
        val cm = CookieManager.getInstance()
        for (d in listOf(".yandex.com.tr", ".yandex.com", ".yandex.ru")) {
            cm.setCookie("https://${d.removePrefix(".")}",
                "yp=2145916800.sp.family:$safeLevel; Domain=$d; Path=/")
        }
        cm.flush()
    }

    private fun pageFinished(url: String) {
        listener.onProgress("Sayfa yüklendi: " + url.take(70))
        if (url.contains("captcha", ignoreCase = true)) {
            handler.removeCallbacksAndMessages(null)
            web.settings.blockNetworkImage = false
            listener.onCaptcha(true)
            return
        }
        if (!url.contains("/video")) return
        web.settings.blockNetworkImage = true
        listener.onCaptcha(false)
        val t = token
        schedule(2500) { if (t == token) extract(t, fromLoad = true) }
    }

    private fun scrollAndExtract() {
        web.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
        val t = token
        schedule(2500) { if (t == token) extract(t, fromLoad = false) }
    }

    private fun schedule(delay: Long, block: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable(block)
        pending = r
        handler.postDelayed(r, delay)
    }

    private fun extract(t: Int, fromLoad: Boolean) {
        listener.onProgress("Önizlemeler aranıyor (deneme ${misses + 1}/4)…")
        web.evaluateJavascript(EXTRACT_JS) { res ->
            if (t != token) return@evaluateJavascript
            val all = parse(res)
            val fresh = all.filter { seen.add(it.preview) }
            listener.onProgress(lastInfo + "\nbulunan önizleme: ${all.size}, yeni: ${fresh.size}")
            if (fresh.isNotEmpty()) {
                misses = 0
                busy = false
                listener.onItems(fresh)
                listener.onRoundEnd(fresh.size, false)
                return@evaluateJavascript
            }
            misses++
            when {
                misses >= 4 -> {
                    exhausted = true
                    busy = false
                    listener.onRoundEnd(0, true)
                }
                fromLoad -> scrollAndExtract()       // önce sayfayı aşağı kaydır
                else -> { page++; load() }           // olmadıysa sonraki sayfa
            }
        }
    }

    private var lastInfo = ""

    private fun parse(res: String?): List<VideoItem> = try {
        val s = JSONTokener(res).nextValue() as? String
        if (s == null) { lastInfo = "JS sonucu boş"; emptyList() } else {
            val root = org.json.JSONObject(s)
            lastInfo = "Sayfa: " + root.optString("title").take(50) +
                "\nAdres: " + root.optString("url").take(70) +
                "\nHTML: " + (root.optInt("len") / 1024) + " KB, mp4: " + root.optInt("mp4") +
                (if (root.has("err")) "\nHata: " + root.optString("err") else "")
            val a = root.getJSONArray("items")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val u = o.optString("u").ifBlank { null }
                VideoItem(
                    preview = o.getString("p"),
                    title = o.optString("t"),
                    original = u,
                    host = u?.let { Uri.parse(it).host?.removePrefix("www.") },
                    sensitive = o.optBoolean("a", false)
                )
            }
        }
    } catch (e: Exception) {
        lastInfo = "Okuma hatası: " + e.message
        emptyList()
    }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        // Sayfanın HTML'inde önizleme bağlantılarını bulur; her birinin
        // yakınındaki başlığı ve asıl video adresini eşleştirir.
        private val EXTRACT_JS = """
(function(){
 var h = '';
 try {
  h = document.documentElement.outerHTML;
  h = h.replace(/&quot;/g,'"').replace(/&amp;/g,'&').replace(/\\u002F/gi,'/').replace(/\\\//g,'/');
  var re = /video-preview\.s3\.yandex\.net(?:\/|%2F)([A-Za-z0-9_\-]+(?:(?:\/|%2F)[A-Za-z0-9_\-]+)*)\.mp4/gi;
  var titleRe = /"(?:title|name|clear_title|headline)"\s*:\s*"((?:[^"\\]|\\.){3,300})"/g;
  var urlRe = /"(?:url|originalUrl|original_url|link|href|page_url|pageUrl|source_url|visibleUrl)"\s*:\s*"(https?:\/\/[^"]+)"/g;
  function nearest(r, s, c, ok){ r.lastIndex = 0; var best = null, bd = 1e9, mm;
    while ((mm = r.exec(s))) { var d = Math.abs(mm.index - c); if (d < bd && ok(mm[1])) { bd = d; best = mm[1]; } }
    return best; }
  function okUrl(u){ return !/yandex\.|yastatic|ya\.ru|mds\.|\.mp4|\.jpe?g|\.png|\.webp|\.js|\.css/i.test(u); }
  function okTitle(x){ return !/^https?:/.test(x) && !/^[a-z_\-]+$/.test(x); }
  var out = [], seen = {}, m;
  while ((m = re.exec(h))) {
    var id = m[1].replace(/%2F/gi,'/');
    if (seen[id]) continue; seen[id] = 1;
    var a = Math.max(0, m.index - 4000), s = h.substring(a, m.index + 4000), c = m.index - a;
    var t = nearest(titleRe, s, c, okTitle);
    if (t) { try { t = JSON.parse('"' + t + '"'); } catch(e) {} t = t.replace(/<[^>]+>/g,''); }
    var u = nearest(urlRe, s, c, okUrl);
    var near = h.substring(Math.max(0, m.index - 1500), m.index + 1500);
    var adult = /"(?:is_?adult|adult|is_?porno?|porno|blur(?:red)?|need_?blur|nsfw|sensitive|is_?erotic|erotic)"\s*:\s*(?:true|1|"(?:true|1|yes)")/i.test(near)
      || /serp-item_(?:adult|blur)|thumb_blur|_blurred|is-adult/i.test(near);
    out.push({p: 'https://video-preview.s3.yandex.net/' + id + '.mp4', t: t || '', u: u || '', a: adult});
  }
  var mp4 = (h.match(/\.mp4/g) || []).length;
  return JSON.stringify({items: out, len: h.length, mp4: mp4, url: location.href, title: document.title});
 } catch (e) { return JSON.stringify({items: [], len: h.length, url: location.href, title: document.title, err: String(e)}); }
})();
""".trimIndent()
    }
}
