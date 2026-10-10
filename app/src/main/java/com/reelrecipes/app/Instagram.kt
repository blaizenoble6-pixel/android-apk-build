package com.reelrecipes.app

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class PostInfo(val caption: String, val author: String, val image: ByteArray?)

/**
 * Best-effort read of a public Instagram post: caption, @handle and cover image.
 * Instagram has no public API for this, so it reads the same public pages a
 * logged-out browser sees. If Instagram blocks it, the app falls back to the
 * caption/screenshots you paste in.
 */
object Instagram {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private val linkRx = Regex("""https?://(?:www\.)?instagram\.com/\S+""", RegexOption.IGNORE_CASE)
    private val codeRx = Regex("""instagram\.com/(?:[^/\s]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)

    /** Pulls the Instagram link out of shared text and strips tracking (?igsh=…). */
    fun findLink(text: String): String? = linkRx.find(text)?.value?.let(::clean)

    fun clean(url: String): String {
        val code = codeRx.find(url)?.groupValues ?: return url.trim()
        val kind = if (url.contains("/reel", true)) "reel" else "p"
        return "https://www.instagram.com/$kind/${code[1]}/"
    }

    suspend fun fetch(url: String): PostInfo? = withContext(Dispatchers.IO) {
        val code = codeRx.find(url)?.groupValues?.get(1) ?: return@withContext null
        fromEmbed(code) ?: fromPage(clean(url))
    }

    // The captioned embed page usually works without signing in.
    private fun fromEmbed(code: String): PostInfo? {
        val html = get("https://www.instagram.com/p/$code/embed/captioned/") ?: return null
        val author = Regex("""class="CaptionUsername"[^>]*>([^<]+)<""").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val capHtml = Regex("""<div class="Caption">(.*?)<div class="CaptionComments">""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        var caption = capHtml?.let { stripHtml(it) }.orEmpty()
        if (author.isNotEmpty() && caption.startsWith(author)) caption = caption.removePrefix(author).trim()
        val img = Regex("""class="EmbeddedMediaImage"[^>]*src="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<img[^>]+class="EmbeddedMediaImage"[^>]*>""").find(html)?.value?.let { tag ->
                Regex("""src="([^"]+)"""").find(tag)?.groupValues?.get(1)
            }
        if (caption.isBlank() && img == null) return null
        return PostInfo(caption, if (author.isNotEmpty()) "@$author" else "", img?.let { getBytes(unescape(it)) })
    }

    private fun fromPage(url: String): PostInfo? {
        val html = get(url) ?: return null
        fun meta(p: String) = Regex("""<meta[^>]+property="$p"[^>]+content="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(::unescape)
        val desc = meta("og:description") ?: meta("og:title") ?: return null
        // e.g.  1,204 likes, 33 comments - somecook on October 2, 2026: "Caption text…"
        val author = Regex("""-\s*([A-Za-z0-9._]+)\s+on\s""").find(desc)?.groupValues?.get(1)
        val caption = Regex(""":\s*"(.*)"\s*\.?\s*$""", RegexOption.DOT_MATCHES_ALL).find(desc)?.groupValues?.get(1) ?: desc
        return PostInfo(caption.trim(), author?.let { "@$it" }.orEmpty(), meta("og:image")?.let { getBytes(it) })
    }

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en-NZ,en;q=0.9").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) { null }

    private fun getBytes(url: String): ByteArray? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
            .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    } catch (e: Exception) { null }

    private fun stripHtml(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY) // <br> becomes a newline
            .toString().lines().joinToString("\n") { it.trim() }.trim()

    private fun unescape(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
}
