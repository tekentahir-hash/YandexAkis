package com.muhammed.yandexakis

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
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
    private var pendingThumbs = 0

    /** Klip bulunamazsa sırayla denenecek Yandex alan adları */
    private val domains = listOf("yandex.com.tr", "yandex.com", "yandex.ru")
    private var domainIdx = 0
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
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, MOUSE_PATCH_JS, setOf("*"))
            }
        } catch (_: Throwable) {}
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
        domainIdx = 0
        hoverRunning = false
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
            Kind.VIDEO -> "https://${domains[domainIdx]}/video/search?text=$q$family$p"
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
            if (kind == Kind.VIDEO && !hoverRunning && pendingThumbs > 0) {
                hoverRunning = true
                hoverLeft = pendingThumbs
                hoverLoop(t)
            }
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
                // Bu alan adında hiç klip çıkmadıysa sıradaki Yandex alan adını dene
                kind == Kind.VIDEO && totalFound == 0 && misses >= 2 && domainIdx < domains.size - 1 -> {
                    domainIdx++
                    page = 0
                    misses = 0
                    pendingThumbs = 0
                    listener.onProgress(this, "Klip yok, ${domains[domainIdx]} deneniyor…")
                    load()
                }
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

    /**
     * Sıradaki küçük resmi ekrana getirir, Android'in gerçek fare sinyalini
     * (SOURCE_MOUSE hover) gönderir; Yandex klibi yükleyince adresini okur.
     */
    private fun hoverLoop(t: Int) {
        web.evaluateJavascript("(function(){$SETUP_JS return window.__yaNext(); })()") { res ->
            if (t != token) return@evaluateJavascript
            val o = try {
                JSONObject(JSONTokener(res).nextValue() as String)
            } catch (e: Exception) { JSONObject() }
            hoverLeft = o.optInt("left")
            if (hoverLeft <= 0) {
                hoverRunning = false
                return@evaluateJavascript
            }
            val cssW = o.optDouble("w", 0.0)
            val scale = if (cssW > 0 && web.width > 0) (web.width / cssW).toFloat() else 1f
            val x = (o.optDouble("x") * scale).toFloat()
            val y = (o.optDouble("y") * scale).toFloat()
            handler.postDelayed({
                if (t == token) {
                    mouse(x - 6, y - 4, MotionEvent.ACTION_HOVER_ENTER)
                    mouse(x - 3, y - 2, MotionEvent.ACTION_HOVER_MOVE)
                    mouse(x, y, MotionEvent.ACTION_HOVER_MOVE)
                }
            }, 250)
            handler.postDelayed({ if (t == token) mouse(x + 2, y + 1, MotionEvent.ACTION_HOVER_MOVE) }, 700)
            handler.postDelayed({
                if (t != token) return@postDelayed
                web.evaluateJavascript("window.__yaRead ? window.__yaRead() : 0") { found ->
                    if (t != token) return@evaluateJavascript
                    mouse(x, y, MotionEvent.ACTION_HOVER_EXIT)
                    // klip yakalandıysa beklemeden akışa ver
                    if (found?.trim() == "1") schedule(50) { if (t == token) extract(t, false) }
                    hoverLoop(t)
                }
            }, 1600)
        }
    }

    private fun mouse(x: Float, y: Float, action: Int) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 0f; size = 1f
        })
        val ev = MotionEvent.obtain(
            now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try { web.dispatchGenericMotionEvent(ev) } catch (_: Exception) {}
        ev.recycle()
    }

    private fun parse(res: String?): List<VideoItem> = try {
        val s = JSONTokener(res).nextValue() as? String
        if (s == null) { lastInfo = "JS sonucu boş"; emptyList() } else {
            val root = JSONObject(s)
            pendingThumbs = root.optInt("pend")
            lastInfo = "Sayfa: " + root.optString("title").take(50) +
                "\nAdres: " + root.optString("url").take(70) +
                "\nHTML: " + (root.optInt("len") / 1024) + " KB, aday: " + root.optInt("cand") +
                ", küçük resim: " + root.optInt("thumbs") + ", video etiketi: " + root.optInt("vtags") +
                ", ağdan video: " + root.optInt("net") +
                (if (root.optString("diag").isNotEmpty()) "\nBileşen: " + root.optString("diag") else "") +
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
    pend: window.__yaPend ? window.__yaPend() : 0, diag: window.__yaDiagText || '',
    thumbs: document.querySelectorAll('img').length, vtags: document.querySelectorAll('video').length,
    sample: (function(){ var v = document.querySelector('video'); if (!v) return '';
      return (v.outerHTML || '').replace(/<source/g,' <source').slice(0, 260) + ' | src=' + String(v.currentSrc || v.src || '').slice(0, 120); })(),
    net: performance.getEntriesByType('resource').filter(function(e){ return /\.(mp4|m3u8|ts|m4s)(\?|$)/i.test(e.name); }).length});
 } catch (e) { return JSON.stringify({items: [], len: 0, url: location.href, title: document.title, err: String(e)}); }
})();
"""

        // Sayfaya "fare var, dokunmatik ekran yok" dedirtir; Yandex dokunmatik
        // cihazlarda fareyle üzerine gelme önizlemesini kapatıyor olabilir.
        private const val MOUSE_PATCH_JS = """
(function(){ try {
  var mm = window.matchMedia ? window.matchMedia.bind(window) : null;
  if (mm) window.matchMedia = function(q){
    var s = String(q), fake = null;
    if (/(any-)?hover:\s*hover|(any-)?pointer:\s*fine/.test(s)) fake = true;
    if (/(any-)?hover:\s*none|(any-)?pointer:\s*coarse/.test(s)) fake = false;
    if (fake === null) return mm(q);
    return {matches: fake, media: s, onchange: null,
      addListener: function(){}, removeListener: function(){},
      addEventListener: function(){}, removeEventListener: function(){},
      dispatchEvent: function(){ return false; }};
  };
  try { Object.defineProperty(navigator, 'maxTouchPoints', {get: function(){ return 0; }}); } catch(e) {}
  [window.Window, window.Document, window.HTMLElement, window.Element].forEach(function(C){
    try { if (C && C.prototype) { delete C.prototype.ontouchstart; delete C.prototype.ontouchend; } } catch(e) {} });
} catch(e) {} })();
"""

        // Sayfaya bir kez yüklenen yardımcılar: sıradaki küçük resmi seç (__yaNext),
        // fare üzerindeyken yüklenen klibi oku (__yaRead), kalanları say (__yaPend).
        private const val SETUP_JS = """
 if (!window.__yaSetup) {
  window.__yaSetup = 1; window.__yaOut = []; window.__yaSeen = {}; window.__yaDone = new WeakSet();
  window.__yaCur = null; window.__yaBefore = 0;
  var thumbs = function(){ return Array.prototype.slice.call(document.querySelectorAll('video, img')).filter(function(i){
      var r = i.getBoundingClientRect();
      if (window.__yaDone.has(i) || r.width < 80 || r.height < 45) return false;
      return i.tagName == 'VIDEO' || /vthumb/i.test(i.currentSrc || i.src || ''); }); };
  window.__yaPend = function(){ return thumbs().length; };
  window.__yaNext = function(){
    var c = thumbs();
    if (!c.length) return JSON.stringify({left: 0});
    var el = c[0]; window.__yaDone.add(el); window.__yaCur = el;
    try { el.scrollIntoView({block: 'center'}); } catch(e) {}
    var r = el.getBoundingClientRect();
    window.__yaBefore = performance.getEntriesByType('resource').length;
    return JSON.stringify({left: c.length, x: r.left + r.width / 2, y: r.top + r.height / 2, w: window.innerWidth});
  };
  var srcOf = function(v){
    if (!v || v.tagName != 'VIDEO') return null;
    v.muted = true;
    var c = [v.currentSrc, v.src, (v.querySelector('source') || {}).src,
             v.getAttribute('data-src'), v.getAttribute('data-video'), v.getAttribute('data-preview')];
    for (var i = 0; i < c.length; i++) { var s = c[i];
      if (s && /^https?:/.test(s) && !window.__yaSeen[s]) return s; }
    return null; };
  var cardOf = function(el){ var e = el;
    for (var k = 0; k < 8 && e.parentElement; k++) { e = e.parentElement;
      if ((e.innerText || '').trim().length > 25 && e.querySelector('a')) return e; }
    return el.parentElement || el; };
  var netMedia = function(from){ var es = performance.getEntriesByType('resource');
    for (var i = es.length - 1; i >= from; i--) { var n = es[i].name;
      if ((/\.(mp4|m3u8)(\?|$)/i.test(n) || /video-preview|master.*\.m3u8/i.test(n)) && !window.__yaSeen[n]) return n; }
    return null; };
  window.__yaRead = function(){
    var el = window.__yaCur; if (!el) return 0;
    var card = cardOf(el);
    var s = srcOf(el);
    if (!s) { var vs = card.querySelectorAll('video'); for (var i = 0; i < vs.length && !s; i++) s = srcOf(vs[i]); }
    if (!s) s = netMedia(window.__yaBefore);
    if (!s) return 0;
    window.__yaSeen[s] = 1;
    var te = card.querySelector('[class*="title" i], h2, h3');
    var title = ((te ? te.innerText : card.innerText) || '').trim().split('\n')[0].slice(0, 200);
    var links = Array.prototype.slice.call(card.querySelectorAll('a[href]')).map(function(a){ return a.href; });
    var ext = links.filter(function(x){ return !/yandex\./.test(x); })[0] || links[0] || '';
    var adult = /blur|adult/i.test(card.className + ' ' + (card.innerHTML || '').slice(0, 4000));
    window.__yaOut.push({p: s, t: title, u: ext, a: adult});
    return 1;
  };
 }
"""

        // Fareyle yakalanan klipleri ekler; ayrıca her küçük resmin React bileşen
        // bilgilerinde (fiber props) saklanan önizleme adresini doğrudan okur.
        private const val HOVER_JS = SETUP_JS + """
  window.__yaOut.splice(0).forEach(function(o){ if (!seen[o.p]) { seen[o.p] = 1; cand++; out.push(o); } });
  var mediaRe = /(\.(mp4|m3u8|webm)(\?|$))|video-preview|vh-preview/i;
  var diag = window.__yaDiag || (window.__yaDiag = {fibers: 0, keys: []});
  var walk = function(o, d, path, seenObj){
    if (o == null || d > 5) return null;
    if (typeof o == 'string') return (mediaRe.test(o) && /^(https?:)?\/\//.test(o)) ? o : null;
    if (typeof o != 'object' || seenObj.indexOf(o) >= 0) return null;
    if (o.nodeType || o === window) return null;
    seenObj.push(o);
    var ks = Object.keys(o).slice(0, 60);
    for (var i = 0; i < ks.length; i++) { var k = ks[i];
      if (k == 'children' || k == '_owner' || k == 'ref' || k.charAt(0) == '_') continue;
      var v; try { v = o[k]; } catch(e) { continue; }
      if (/preview|video|src|url|stream/i.test(k) && diag.keys.length < 6 && (typeof v == 'string' || typeof v == 'number')) {
        var line = path + '.' + k + '=' + String(v).slice(0, 90);
        if (diag.keys.indexOf(line) < 0 && /preview/i.test(k)) diag.keys.push(line);
      }
      var r = walk(v, d + 1, path + '.' + k, seenObj); if (r) return r; }
    return null; };
  var fiberOf = function(el){ var ks = Object.keys(el);
    for (var i = 0; i < ks.length; i++) if (ks[i].indexOf('__reactFiber') == 0 || ks[i].indexOf('__reactInternalInstance') == 0) return el[ks[i]];
    return null; };
  var vids = document.querySelectorAll('video');
  for (var vi = 0; vi < vids.length; vi++) {
    var v = vids[vi], f = fiberOf(v), found = null, hops = 0;
    if (f) diag.fibers++;
    while (f && hops < 25 && !found) { found = walk(f.memoizedProps, 0, 'p' + hops, []); f = f.return; hops++; }
    if (!found) continue;
    if (found.indexOf('//') == 0) found = 'https:' + found;
    cand++;
    if (seen[found] || window.__yaSeen[found]) continue; seen[found] = 1; window.__yaSeen[found] = 1;
    var card = v; for (var k2 = 0; k2 < 8 && card.parentElement; k2++) { card = card.parentElement;
      if ((card.innerText || '').trim().length > 25 && card.querySelector('a')) break; }
    var te = card.querySelector('[class*="title" i], h2, h3');
    var title = ((te ? te.innerText : card.innerText) || '').trim().split('\n')[0].slice(0, 200);
    var links = Array.prototype.slice.call(card.querySelectorAll('a[href]')).map(function(a){ return a.href; });
    var ext = links.filter(function(x){ return !/yandex\./.test(x); })[0] || links[0] || '';
    out.push({p: found, t: title, u: ext, a: /blur|adult/i.test(card.className + ' ' + (card.innerHTML || '').slice(0, 4000))});
  }
  window.__yaDiagText = 'react: ' + diag.fibers + (diag.keys.length ? ' | ' + diag.keys.join(' | ') : ' | önizleme alanı yok');
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
