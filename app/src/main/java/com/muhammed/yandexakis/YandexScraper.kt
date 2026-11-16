package com.muhammed.yandexakis

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder

data class VideoItem(
    val preview: String,   // oynatılacak dosya: video özet klibi (mp4) ya da GIF
    val title: String,
    val original: String?, // asıl sayfa (YouTube, VK, GIF'in bulunduğu site vb.)
    val host: String?,
    val sensitive: Boolean = false, // Yandex'in "bulanıklaştır" işaretlediği içerik
    val isGif: Boolean = false
)

/**
 * Yandex arama sayfasını görünmez bir WebView'da açar ve sayfanın içinden
 *  - VIDEO: video-preview.s3.yandex.net/....mp4 önizleme kliplerini
 *  - GIF:   Görseller'de GIF filtresiyle (itype=gifan) bulunan hareketli resimleri
 * toplar. API anahtarı yok, ücret yok: tamamen telefonda çalışır.
 */
class YandexScraper(
    val web: WebView,
    val kind: Kind,
    private val listener: Listener
) {
    enum class Kind(val label: String) { VIDEO("Video"), GIF("GIF") }

    interface Listener {
        fun onItems(src: YandexScraper, items: List<VideoItem>)
        fun onCaptcha(src: YandexScraper, visible: Boolean)
        fun onRoundEnd(src: YandexScraper, newCount: Int, exhausted: Boolean)
        fun onProgress(src: YandexScraper, msg: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var query = ""
    private var page = 0
    private var misses = 0
    private var token = 0
    private var pending: Runnable? = null
    private val seen = HashSet<String>()
    private var lastInfo = ""
    private var hoverRunning = false
    private var hoverLeft = 0
    private var totalFound = 0

    var busy = false
        private set
    var exhausted = true
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
            mediaPlaybackRequiresUserGesture = false // sayfadaki önizleme klipleri kendiliğinden başlasın
            blockNetworkImage = true // veri tasarrufu; doğrulamada açılır
        }
        forceDesktop()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageFinished(url)
            }

            // Yandex mobil (touch) sayfaya yönlendirirse masaüstü adresine geri çevir
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.contains("/touch/") && touchRedirects < 2) {
                    touchRedirects++
                    view.loadUrl(u.replace("/touch/", "/"))
                    return true
                }
                return false
            }
        }
    }

    private var touchRedirects = 0

    /**
     * Android WebView, sitelere "Sec-CH-UA-Mobile: ?1" başlığıyla telefon olduğunu bildirir;
     * Yandex de bu yüzden önizlemesiz mobil sayfayı açar. Burada kendimizi Windows
     * masaüstü olarak tanıtıyoruz.
     */
    private fun forceDesktop() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val cur = WebSettingsCompat.getUserAgentMetadata(web.settings)
                val md = UserAgentMetadata.Builder()
                    .setBrandVersionList(cur.brandVersionList)
                    .setFullVersion(cur.fullVersion)
                    .setPlatform("Windows")
                    .setPlatformVersion("10.0.0")
                    .setArchitecture("x86")
                    .setModel("")
                    .setMobile(false)
                    .setBitness(64)
                    .setWow64(false)
                    .build()
                WebSettingsCompat.setUserAgentMetadata(web.settings, md)
            }
        } catch (e: Throwable) {
            // desteklenmiyorsa yalnızca masaüstü User-Agent ile devam
        }
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

    fun search(q: String) {
        query = q
        page = 0
        misses = 0
        totalFound = 0
        exhausted = false
        seen.clear()
        token++
        busy = true
        listener.onProgress(this, "Arama başlatıldı…")
        load()
    }

    fun stop() {
        token++
        busy = false
        exhausted = true
        pending?.let { handler.removeCallbacks(it) }
        web.stopLoading()
    }

    /** Akışın sonuna yaklaşınca daha fazla sonuç getirir. */
    fun more() {
        if (busy || exhausted || query.isEmpty()) return
        busy = true
        scrollAndExtract()
    }

    private fun load() {
        touchRedirects = 0
        applySafeSearch()
        val family = when (safeLevel) { 0 -> "&family=no"; 2 -> "&family=yes"; else -> "" }
        val q = URLEncoder.encode(query, "UTF-8")
        val p = if (page > 0) "&p=$page" else ""
        val url = when (kind) {
            Kind.VIDEO -> "https://yandex.com.tr/video/search?text=$q$family$p"
            Kind.GIF -> "https://yandex.com.tr/images/search?text=$q&itype=gifan$family$p"
        }
        web.loadUrl(url)
        // Sayfa hiç bitmezse yine de dene
        val t = token
        schedule(15000) { if (t == token && busy) extract(t, fromLoad = true) }
    }

    /** Yandex'in arama ayarı çerezi (sp.family) ile filtre düzeyini bildirir. */
    private fun applySafeSearch() {
        val cm = CookieManager.getInstance()
        for (d in listOf(".yandex.com.tr", ".yandex.com", ".yandex.ru")) {
            cm.setCookie(
                "https://${d.removePrefix(".")}",
                "yp=2145916800.sp.family:$safeLevel; Domain=$d; Path=/"
            )
        }
        cm.flush()
    }

    private fun pageFinished(url: String) {
        if (!busy) return
        listener.onProgress(this, "Sayfa yüklendi: " + url.take(70) +
            (if (url.contains("/touch/")) "\n(Yandex mobil sayfayı açtı)" else ""))
        if (url.contains("captcha", ignoreCase = true)) {
            pending?.let { handler.removeCallbacks(it) }
            web.settings.blockNetworkImage = false
            listener.onCaptcha(this, true)
            return
        }
        if (!url.contains("/video") && !url.contains("/images")) return
        web.settings.blockNetworkImage = true
        listener.onCaptcha(this, false)
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
        listener.onProgress(this, "Sonuçlar aranıyor (deneme ${misses + 1}/4)…")
        val js = if (kind == Kind.VIDEO) VIDEO_JS else GIF_JS
        web.evaluateJavascript(js) { res ->
            if (t != token) return@evaluateJavascript
            val all = parse(res)
            val fresh = all.filter { seen.add(it.preview) }
            totalFound += fresh.size
            listener.onProgress(this, lastInfo + "\nbulunan toplam: $totalFound, yeni: ${fresh.size}" +
                (if (hoverRunning) "\nvideoların üzerine geliniyor: $hoverLeft kaldı" else ""))
            if (hoverRunning) {
                // Fareyle üzerine gelme işlemi sürüyor: yakalananları aldıkça ver
                if (fresh.isNotEmpty()) {
                    misses = 0
                    busy = false
                    listener.onItems(this, fresh)
                    listener.onRoundEnd(this, fresh.size, false)
                }
                schedule(2000) { if (t == token) extract(t, fromLoad) }
                return@evaluateJavascript
            }
            if (fresh.isNotEmpty()) {
                misses = 0
                busy = false
                listener.onItems(this, fresh)
                listener.onRoundEnd(this, fresh.size, false)
                return@evaluateJavascript
            }
            misses++
            when {
                misses >= 4 -> {
                    exhausted = true
                    busy = false
                    listener.onRoundEnd(this, 0, true)
                }
                fromLoad -> scrollAndExtract()       // önce sayfayı aşağı kaydır
                else -> { page++; load() }           // olmadıysa sonraki sayfa
            }
        }
    }

    private fun parse(res: String?): List<VideoItem> = try {
        val s = JSONTokener(res).nextValue() as? String
        if (s == null) { lastInfo = "JS sonucu boş"; emptyList() } else {
            val root = JSONObject(s)
            hoverRunning = root.optInt("run") == 1
            hoverLeft = root.optInt("left")
            lastInfo = "Sayfa: " + root.optString("title").take(50) +
                "\nAdres: " + root.optString("url").take(70) +
                "\nHTML: " + (root.optInt("len") / 1024) + " KB, aday: " + root.optInt("cand") +
                ", küçük resim: " + root.optInt("thumbs") + ", video etiketi: " + root.optInt("vtags") +
                ", ağdan video: " + root.optInt("net") +
                (if (root.optString("sample").isNotEmpty()) "\nÖrnek: " + root.optString("sample") else "") +
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
                    sensitive = o.optBoolean("a", false),
                    isGif = kind == Kind.GIF
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

        // İki aramada ortak yardımcılar: HTML'i çöz, en yakın başlığı / sayfayı bul,
        // hassas içerik işaretini yakala.
        private const val JS_COMMON = """
  var h = document.documentElement.outerHTML;
  h = h.replace(/&quot;/g,'"').replace(/&amp;/g,'&').replace(/\\u002F/gi,'/').replace(/\\\//g,'/');
  var titleRe = /"(?:title|name|clear_title|headline|alt|snippet)"\s*:\s*"((?:[^"\\]|\\.){3,300})"/g;
  var urlRe = /"(?:url|originalUrl|original_url|link|href|page_url|pageUrl|source_url|visibleUrl|sourceUrl)"\s*:\s*"(https?:\/\/[^"]+)"/g;
  function nearest(r, s, c, ok){ r.lastIndex = 0; var best = null, bd = 1e9, mm;
    while ((mm = r.exec(s))) { var d = Math.abs(mm.index - c); if (d < bd && ok(mm[1])) { bd = d; best = mm[1]; } }
    return best; }
  function okUrl(u){ return !/yandex\.|yastatic|ya\.ru|mds\.|\.mp4|\.gif|\.jpe?g|\.png|\.webp|\.js|\.css/i.test(u); }
  function okTitle(x){ return !/^https?:/.test(x) && !/^[a-z_\-]+$/.test(x); }
  function info(i){
    var a = Math.max(0, i - 4000), s = h.substring(a, i + 4000), c = i - a;
    var t = nearest(titleRe, s, c, okTitle);
    if (t) { try { t = JSON.parse('"' + t + '"'); } catch(e) {} t = t.replace(/<[^>]+>/g,''); }
    var u = nearest(urlRe, s, c, okUrl);
    var near = h.substring(Math.max(0, i - 1500), i + 1500);
    var adult = /"(?:is_?adult|adult|is_?porno?|porno|blur(?:red)?|need_?blur|nsfw|sensitive|is_?erotic|erotic)"\s*:\s*(?:true|1|"(?:true|1|yes)")/i.test(near)
      || /serp-item_(?:adult|blur)|thumb_blur|_blurred|is-adult/i.test(near);
    return {t: t || '', u: u || '', a: adult};
  }
  var out = [], seen = {}, m, cand = 0;
"""

        private const val JS_END = """
  return JSON.stringify({items: out, len: h.length, cand: cand, url: location.href, title: document.title,
    run: window.__yaRun ? 1 : 0, left: window.__yaLeft || 0,
    thumbs: document.querySelectorAll('img').length, vtags: document.querySelectorAll('video').length,
    sample: (function(){ var v = document.querySelector('video'); if (!v) return '';
      return (v.outerHTML || '').replace(/<source/g,' <source').slice(0, 260) + ' | src=' + String(v.currentSrc || v.src || '').slice(0, 120); })(),
    net: performance.getEntriesByType('resource').filter(function(e){ return /\.(mp4|m3u8|ts|m4s)(\?|$)/i.test(e.name); }).length});
 } catch (e) { return JSON.stringify({items: [], len: 0, url: location.href, title: document.title, err: String(e)}); }
})();
"""

        // Masaüstü Yandex, önizleme klibini ancak fare videonun üzerine gelince yükler.
        // Burada her küçük resmin üzerine sırayla "fareyle gelinmiş" gibi olay gönderip
        // o anda oynamaya başlayan <video> klibini (ya da ağdan inen mp4'ü) yakalıyoruz.
        private const val HOVER_JS = """
  document.querySelectorAll('video').forEach(function(v){ try { v.muted = true; } catch(e) {} });
  window.__yaOut = window.__yaOut || [];
  window.__yaSeen = window.__yaSeen || {};
  if (!window.__yaDone) window.__yaDone = new WeakSet();
  if (!window.__yaRun) {
    var imgs = Array.prototype.slice.call(document.querySelectorAll('img, video')).filter(function(i){
      var r = i.getBoundingClientRect();
      if (window.__yaDone.has(i) || r.width < 80 || r.height < 45) return false;
      if (i.tagName == 'VIDEO') return true;
      return /vthumb|avatars\.mds|video/i.test(i.currentSrc || i.src || '');
    });
    if (imgs.length) {
      window.__yaRun = 1; window.__yaLeft = imgs.length;
      var fire = function(el, types){ types.forEach(function(tp){ try {
        var ev = tp.indexOf('pointer') == 0
          ? new PointerEvent(tp, {bubbles: tp.indexOf('enter') < 0 && tp.indexOf('leave') < 0, cancelable: true, pointerType: 'mouse', view: window})
          : new MouseEvent(tp, {bubbles: tp.indexOf('enter') < 0 && tp.indexOf('leave') < 0, cancelable: true, view: window});
        el.dispatchEvent(ev); } catch(e) {} }); };
      var cardOf = function(img){ var e = img;
        for (var k = 0; k < 8 && e.parentElement; k++) { e = e.parentElement;
          if ((e.innerText || '').trim().length > 25 && e.querySelector('a')) return e; }
        return img.parentElement || img; };
      var srcOf = function(v){
        var c = [v.currentSrc, v.src, (v.querySelector('source') || {}).src,
                 v.getAttribute('data-src'), v.getAttribute('data-video'), v.getAttribute('data-preview')];
        for (var i = 0; i < c.length; i++) { var s = c[i];
          if (s && /^https?:/.test(s) && !window.__yaSeen[s]) return s; }
        return null; };
      var newSrc = function(root){
        if (root.tagName == 'VIDEO') { root.muted = true; var r0 = srcOf(root); if (r0) return r0; }
        var vs = root.querySelectorAll('video');
        for (var i = 0; i < vs.length; i++) { vs[i].muted = true; var s = srcOf(vs[i]); if (s) return s; }
        return null; };
      var netMp4 = function(from){ var es = performance.getEntriesByType('resource');
        for (var i = es.length - 1; i >= from; i--) { var n = es[i].name;
          if ((/\.(mp4|m3u8)(\?|$)/i.test(n) || /video-preview|vh-preview|preview.*\.mp4|master.*\.m3u8/i.test(n)) && !window.__yaSeen[n]) return n; }
        return null; };
      var idx = 0;
      var step = function(){
        if (idx >= imgs.length) { window.__yaRun = 0; window.__yaLeft = 0; return; }
        var img = imgs[idx++]; window.__yaDone.add(img); window.__yaLeft = imgs.length - idx;
        var card = cardOf(img);
        try { img.scrollIntoView({block: 'center'}); } catch(e) {}
        var before = performance.getEntriesByType('resource').length;
        var tg = [img, img.parentElement, card];
        tg.forEach(function(t){ if (t) fire(t, ['pointerover','pointerenter','mouseover','mouseenter','pointermove','mousemove']); });
        setTimeout(function(){
          var s = newSrc(img) || newSrc(card) || netMp4(before);
          if (s) {
            window.__yaSeen[s] = 1;
            var te = card.querySelector('[class*="title" i], h2, h3');
            var title = ((te ? te.innerText : card.innerText) || '').trim().split('\n')[0].slice(0, 200);
            var links = Array.prototype.slice.call(card.querySelectorAll('a[href]')).map(function(a){ return a.href; });
            var ext = links.filter(function(x){ return !/yandex\./.test(x); })[0] || links[0] || '';
            var adult = /blur|adult/i.test(card.className + ' ' + (card.innerHTML || '').slice(0, 4000));
            window.__yaOut.push({p: s, t: title, u: ext, a: adult});
          }
          tg.forEach(function(t){ if (t) fire(t, ['pointerout','pointerleave','mouseout','mouseleave']); });
          step();
        }, 1400);
      };
      step();
    }
  }
  window.__yaOut.splice(0).forEach(function(o){ if (!seen[o.p]) { seen[o.p] = 1; cand++; out.push(o); } });
"""

        // Video: Yandex'in animasyonlu önizleme klipleri
        private const val VIDEO_JS = "(function(){\n try {" + JS_COMMON + """
  var re = /video-preview\.s3\.yandex\.net(?:\/|%2F)([A-Za-z0-9_\-]+(?:(?:\/|%2F)[A-Za-z0-9_\-]+)*)\.mp4/gi;
  while ((m = re.exec(h))) {
    cand++;
    var id = m[1].replace(/%2F/gi,'/');
    if (seen[id]) continue; seen[id] = 1;
    var x = info(m.index);
    out.push({p: 'https://video-preview.s3.yandex.net/' + id + '.mp4', t: x.t, u: x.u, a: x.a});
  }
""" + HOVER_JS + JS_END

        // GIF: Görseller'de GIF filtresiyle bulunan hareketli resimlerin asıl adresleri
        private const val GIF_JS = "(function(){\n try {" + JS_COMMON + """
  function bad(u){ return /yandex\.|yastatic|ya\.ru|favicon|sprite|spacer|pixel|blank\.gif/i.test(u); }
  function add(u, i){
    cand++;
    if (bad(u) || seen[u]) return; seen[u] = 1;
    var x = info(i);
    out.push({p: u, t: x.t, u: x.u, a: x.a});
  }
  var keyRe = /"(?:origUrl|img_href|originalUrl|orig_url|imgHref|origin_url)"\s*:\s*"(https?:\/\/[^"]+)"/g;
  while ((m = keyRe.exec(h))) add(m[1], m.index);
  if (out.length == 0) {
    var gifRe = /https?:\/\/[^"'\s<>\\]+?\.gif(?:\?[^"'\s<>\\]*)?(?=["'\s<>\\])/gi;
    while ((m = gifRe.exec(h))) add(m[0], m.index);
  }
""" + JS_END
    }
}
