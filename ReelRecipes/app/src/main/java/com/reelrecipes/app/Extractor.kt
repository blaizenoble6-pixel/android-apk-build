package com.reelrecipes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Result of reading a post: a recipe to review, plus anything Claude said is missing. */
data class Draft(val recipe: Recipe, val missing: String = "")

object Extractor {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    const val MODEL = "claude-sonnet-5-5"

    private const val PROMPT = """You turn Instagram cooking posts into clean recipe cards for a home cook in New Zealand.
Use the caption text and any screenshots. Keep the creator's quantities; use metric where the source does, and don't invent amounts that aren't given (leave qty empty instead).
If the recipe is incomplete, fill in only what the sources support and explain what's missing in "missing".
Return ONLY JSON of this shape:
{"title":string,"author":string,"servings":string,"time":string,"tags":string[],"ingredients":[{"qty":string,"item":string,"note":string}],"steps":string[],"notes":string,"missing":string}
tags: 2-4 short lowercase tags (cuisine, main ingredient, "quick"). steps: clear imperative sentences."""

    /**
     * With an Anthropic API key: Claude reads the caption and screenshots.
     * Without one: a simple on-phone parser splits the caption into ingredients and steps.
     */
    suspend fun extract(caption: String, author: String, url: String, images: List<ByteArray>, apiKey: String): Draft {
        if (apiKey.isNotBlank()) {
            return withClaude(caption, author, url, images, apiKey)
        }
        return Draft(Heuristic.parse(caption, author, url),
            if (caption.isBlank()) "No caption to read. Add an API key in Settings to read screenshots, or type it in." else "")
    }

    private suspend fun withClaude(caption: String, author: String, url: String, images: List<ByteArray>, key: String): Draft =
        withContext(Dispatchers.IO) {
            val content = JSONArray()
            images.take(8).forEach { jpg ->
                content.put(JSONObject().put("type", "image").put("source", JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpg, Base64.NO_WRAP))))
            }
            content.put(JSONObject().put("type", "text").put("text",
                "$PROMPT\n\nPost link: ${url.ifBlank { "(none)" }}\nCreator: ${author.ifBlank { "(unknown)" }}\n\nCaption:\n${caption.ifBlank { "(no caption — use the screenshots)" }}"))
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 3000)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val text = http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(s).getJSONObject("error").getString("message") }.getOrNull()
                    throw IllegalStateException(when (r.code) {
                        401 -> "The API key was rejected. Check it in Settings."
                        429 -> "Too many requests just now. Wait a minute and try again."
                        else -> msg ?: "Claude couldn't read this post (error ${r.code})."
                    })
                }
                val parts = JSONObject(s).getJSONArray("content")
                (0 until parts.length()).map { parts.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            }
            val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            val r = Recipe.fromJson(json).copy(url = url)
            Draft(if (r.author.isBlank()) r.copy(author = author) else r, json.optString("missing"))
        }

    /** Loads a picked/shared image and shrinks it to a sensible JPEG. */
    suspend fun loadJpeg(context: Context, uri: Uri, max: Int = 1568): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { shrink(it, max) }
    }

    fun shrink(bytes: ByteArray, max: Int = 1568): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val s = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        return ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
    }
}

/** Offline fallback: works well for captions laid out as "Ingredients:" then "Method:". */
object Heuristic {
    private val qtyStart = Regex("""^([\d½¼¾⅓⅔⅛.,/\-–\s]+(?:x\s)?(?:g|kg|ml|l|tsp|tbsp|cups?|oz|lb|pinch|handful|cloves?|cans?|tins?|bunch|slices?|pieces?)?\.?)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val bullet = Regex("""^(?:[\-•*·▪◦]|\p{So}|️|\s)+""")
    private val stepNum = Regex("""^(?:step\s*)?\d{1,2}\s*[.):\-–]\s*""", RegexOption.IGNORE_CASE)
    private val ingHeader = Regex("""^\W*(ingredients?|you(?:'|’)ll need|what you need)\b.*""", RegexOption.IGNORE_CASE)
    private val stepHeader = Regex("""^\W*(method|instructions?|directions|steps|how to make( it)?|recipe)\b\W*$""", RegexOption.IGNORE_CASE)

    fun parse(caption: String, author: String, url: String): Recipe {
        val lines = caption.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val tags = Regex("""#(\w+)""").findAll(caption).map { it.groupValues[1].lowercase() }.distinct().take(3).toList()
        var mode = 0 // 0 = intro, 1 = ingredients, 2 = steps
        val ings = mutableListOf<Ingredient>()
        val steps = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var title = ""
        for (raw in lines) {
            if (raw.startsWith("#") && raw.split(" ").all { it.startsWith("#") }) continue
            when {
                ingHeader.matches(raw) -> { mode = 1; continue }
                stepHeader.matches(raw) -> { mode = 2; continue }
            }
            val line = raw.replace(bullet, "").trim()
            if (line.isEmpty()) continue
            if (stepNum.containsMatchIn(line) && (mode != 1 || !qtyStart.matches(line))) {
                mode = 2; steps += line.replace(stepNum, "").trim(); continue
            }
            when (mode) {
                1 -> qtyStart.find(line)?.let { ings += Ingredient(it.groupValues[1].trim(), it.groupValues[2].trim()) }
                    ?: run { ings += Ingredient(item = line) }
                2 -> steps += line
                else -> if (title.isEmpty()) title = line.take(70) else notes += line
            }
        }
        return Recipe(title = title.ifBlank { "Saved recipe" }, author = author, tags = tags,
            ingredients = ings, steps = steps, notes = notes.joinToString("\n"), url = url)
    }
}
