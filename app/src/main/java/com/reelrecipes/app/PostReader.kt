package com.reelrecipes.app

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** What we managed to read off an Instagram post page. */
data class ReadPost(val caption: String, val author: String, val imageUrls: List<String>)

/**
 * Reads a post the way a phone browser does: loads it in a real WebView and pulls the
 * caption, @handle and photo URLs out of the page. If you sign in to Instagram once
 * (Settings → Sign in to Instagram) the WebView keeps that session, which makes reels
 * and most posts readable.
 */
object PostReader {
    // Chrome-on-Android UA without the "; wv" WebView marker, which Instagram treats as a bot.
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun newWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.userAgentString = UA
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = WebViewClient()
    }

    fun isSignedIn(): Boolean =
        CookieManager.getInstance().getCookie("https://www.instagram.com")?.contains("sessionid=") == true

    fun signOut() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** Runs the extraction script in the page and parses its answer. */
    suspend fun extract(webView: WebView): ReadPost? = suspendCancellableCoroutine { cont ->
        try {
            webView.evaluateJavascript(SCRIPT) { raw ->
                val post = runCatching {
                    val s = JSONTokener(raw).nextValue() as? String ?: return@runCatching null
                    val o = JSONObject(s)
                    val imgs = o.optJSONArray("images").strings()
                    ReadPost(o.optString("caption").trim(), o.optString("author").trim(), imgs)
                }.getOrNull()
                if (cont.isActive) cont.resume(post)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** Downloads post images with the same cookies and UA as the WebView. */
    suspend fun download(urls: List<String>, max: Int = 10): List<ByteArray> = withContext(Dispatchers.IO) {
        val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com").orEmpty()
        urls.distinct().take(max).mapNotNull { u ->
            runCatching {
                http.newCall(Request.Builder().url(u).header("User-Agent", UA).header("Referer", "https://www.instagram.com/")
                    .apply { if (cookies.isNotEmpty()) header("Cookie", cookies) }.build())
                    .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            }.getOrNull()?.let { Extractor.shrink(it) }
        }
    }

    /**
     * Collects every caption candidate on the page (page data JSON, meta tags, the visible
     * caption) and returns the longest, plus post photo URLs (all carousel slides when
     * the page data includes them).
     */
    private val SCRIPT = """
(function(){
  var html = document.documentElement.innerHTML;
  var caps = [], imgs = [], author = "";
  function dec(s){ try { return JSON.parse('"' + s + '"'); } catch(e) { return ""; } }
  function meta(p){ var m = document.querySelector('meta[property="'+p+'"],meta[name="'+p+'"]'); return m ? (m.getAttribute('content')||"") : ""; }
  var m, re;
  re = /"caption":\{[^{}]*?"text":"((?:[^"\\]|\\.)*)"/g;            while ((m = re.exec(html))) caps.push(dec(m[1]));
  re = /"edge_media_to_caption":\{"edges":\[\{"node":\{"text":"((?:[^"\\]|\\.)*)"/g; while ((m = re.exec(html))) caps.push(dec(m[1]));
  var c = document.querySelector('.Caption');
  if (c) { var t = c.innerText; var u = c.querySelector('.CaptionUsername'); if (u) { author = u.innerText.trim(); t = t.replace(author, ''); } caps.push(t.replace(/\n?View all \d+ comments[\s\S]*$/,'')); }
  var h1 = document.querySelector('article h1, main h1, h1'); if (h1) caps.push(h1.innerText);
  var d = meta('og:description') || meta('description');
  if (d) { var q = d.match(/:\s*"([\s\S]*)"\s*\.?\s*$/); caps.push(q ? q[1] : d); var a = d.match(/-\s*([A-Za-z0-9._]+)\s+on\s/); if (a && !author) author = a[1]; }
  caps = caps.map(function(s){ return (s||"").trim(); }).filter(Boolean).sort(function(a,b){ return b.length - a.length; });
  if (!author) { m = html.match(/"owner":\{[^{}]*?"username":"([A-Za-z0-9._]+)"/) || html.match(/"username":"([A-Za-z0-9._]+)"/); if (m) author = m[1]; }
  if (!author) { var t2 = meta('og:title'); m = t2.match(/@([A-Za-z0-9._]+)/); if (m) author = m[1]; }
  re = /"display_url":"((?:[^"\\]|\\.)*)"/g;                         while ((m = re.exec(html))) imgs.push(dec(m[1]));
  re = /"image_versions2":\{"candidates":\[\{[^{}]*?"url":"((?:[^"\\]|\\.)*)"/g; while ((m = re.exec(html))) imgs.push(dec(m[1]));
  document.querySelectorAll('.EmbeddedMediaImage, article img, main img').forEach(function(i){
    var alt = (i.alt||"").toLowerCase();
    if (alt.indexOf('profile picture') >= 0) return;
    if ((i.naturalWidth||0) < 300 && (i.width||0) < 250) return;
    var best = i.currentSrc || i.src; if (best) imgs.push(best);
  });
  var og = meta('og:image'); if (og) imgs.push(og);
  var seen = {}, out = [];
  imgs.forEach(function(u){ if (!u || u.indexOf('http') !== 0) return; var k = u.split('?')[0]; if (seen[k]) return; seen[k] = 1; out.push(u); });
  return JSON.stringify({ caption: caps[0] || "", author: author ? ('@' + author.replace(/^@/,'')) : "", images: out.slice(0, 10),
    loginWall: !!document.querySelector('input[name="username"]') });
})();
""".trimIndent()
}
