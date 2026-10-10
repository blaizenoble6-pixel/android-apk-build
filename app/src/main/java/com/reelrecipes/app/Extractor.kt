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
     * With an Anthropic API key: Claude reads the caption and images.
     * Without one (default): the on-phone parser reads the caption plus any text
     * found in the photos/screenshots by on-phone OCR.
     * If Claude fails (no credit, network), it falls back to the on-phone parser.
     */
    suspend fun extract(caption: String, author: String, url: String, images: List<ByteArray>, apiKey: String, ocrText: String = ""): Draft {
        if (apiKey.isNotBlank()) {
            try { return withClaude(caption, author, url, images, apiKey) }
            catch (e: Exception) {
                val d = offline(caption, ocrText, author, url)
                return d.copy(missing = listOf("Claude couldn't be reached (${e.message}), so the on-phone reader was used.", d.missing).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
        return offline(caption, ocrText, author, url)
    }

    fun offline(caption: String, ocrText: String, author: String, url: String): Draft {
        val fromCaption = Heuristic.parse(caption, author, url)
        val fromImages = if (ocrText.isNotBlank()) Heuristic.parse(ocrText, author, url) else null
        var r = fromCaption
        if (fromImages != null) {
            fun score(x: Recipe) = x.ingredients.count { it.qty.isNotBlank() } * 2 + x.ingredients.size
            r = r.copy(
                ingredients = if (score(fromImages) > score(fromCaption)) fromImages.ingredients else fromCaption.ingredients,
                steps = if (fromImages.steps.size > fromCaption.steps.size) fromImages.steps else fromCaption.steps,
                title = if (caption.isBlank()) fromImages.title else fromCaption.title,
            )
        }
        val missing = when {
            r.ingredients.isEmpty() && r.steps.isEmpty() ->
                "Couldn't find a recipe in the caption or photos. The caption is in Notes so you can move lines across. Screenshots of the ingredients and method frames help."
            r.ingredients.isEmpty() -> "No ingredients list found. Add a screenshot of the ingredients frame, or type them in."
            r.steps.isEmpty() -> "No method steps found. Add a screenshot of the steps, or type them in."
            r.ingredients.any { it.qty.isBlank() && !it.item.contains("taste", true) } -> "Some ingredients have no amount in the post. Check those."
            else -> ""
        }
        if (r.ingredients.isEmpty() && r.steps.isEmpty()) r = r.copy(notes = listOf(caption, ocrText).filter { it.isNotBlank() }.joinToString("\n\n"))
        return Draft(r, missing)
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

/** On-phone recipe reader: handles "Ingredients" / "Method" layouts, emoji bullets, numbered steps and OCR text. */
object Heuristic {
    private const val UNITS = "g|kg|ml|l|tsp|tbsp|cups?|teaspoons?|tablespoons?|grams?|oz|lb|pinch(?:es)?|handfuls?|cloves?|cans?|tins?|bunch(?:es)?|slices?|pieces?|sprigs?|stalks?|sticks?|sheets?|packets?"
    private const val NUM = "(?:\\d+(?:[.,/]\\d+)?|[½¼¾⅓⅔⅛])(?:\\s*(?:-|–|to)\\s*\\d+(?:[.,/]\\d+)?)?"
    private val qty = Regex("^($NUM\\s*(?:x\\s*)?(?:(?:$UNITS)\\b)?\\.?)\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val trailingQty = Regex("^(.+?)\\s*[-–:]?\\s*($NUM\\s*(?:$UNITS)\\b\\.?)$", RegexOption.IGNORE_CASE)
    private val trailingBare = Regex("^([A-Za-z][^\\d]{1,30}?)\\s*[-–:x]?\\s*(\\d+(?:[.,/]\\d+)?|[½¼¾⅓⅔⅛])$", RegexOption.IGNORE_CASE)
    private val meta = Regex("^(serves?|makes|prep|cook(?:ing)?|total|ready|time|calories|kcal|protein|step|day|part)\\b", RegexOption.IGNORE_CASE)
    private val unitWord = Regex("\\b($UNITS)\\b", RegexOption.IGNORE_CASE)
    private val bullet = Regex("^(?:[\\-•*·▪◦–]|\\p{So}|\\uFE0F|\\u200D|\\s)+")
    private val stepNum = Regex("^(?:step\\s*)?\\d{1,2}\\s*[.):\\-–]\\s+", RegexOption.IGNORE_CASE)
    private val ingHead = Regex("^\\W*(ingredients?|you(?:'|’)ll need|what you need|shopping list)\\b", RegexOption.IGNORE_CASE)
    private val stepHead = Regex("^\\W*(method|instructions?|directions|steps|how to make(?: it)?|recipe|preparation)\\b\\W*$", RegexOption.IGNORE_CASE)
    private val signOff = Regex("^(save|follow|share|tag|comment|like|send)\\b.*\\b(later|me|friend|more|recipes?|this|post)\\b", RegexOption.IGNORE_CASE)
    private val cookVerb = Regex("^(add|bake|blend|boil|bring|chop|combine|cook|cover|cut|dice|drain|drizzle|fold|fry|garnish|grate|heat|knead|let|marinate|melt|mix|place|pour|preheat|put|reduce|remove|rest|roast|saute|sauté|season|serve|simmer|slice|spread|sprinkle|stir|strain|top|toss|transfer|whisk|wrap|sear|flip|grill|steam|baste|crack|beat|soak|rinse|peel|shred|mash|assemble|layer|chill|freeze|enjoy)\\b", RegexOption.IGNORE_CASE)

    fun parse(text: String, author: String, url: String): Recipe {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val tags = Regex("#(\\w+)").findAll(text).map { it.groupValues[1].lowercase() }.distinct().take(3).toList()
        var mode = 0 // 0 intro, 1 ingredients, 2 steps
        val ings = mutableListOf<Ingredient>()
        val steps = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var title = ""
        for (raw in lines) {
            if (Regex("^(#\\S+\\s*)+$").matches(raw)) continue
            if (ingHead.containsMatchIn(raw)) {
                mode = 1
                val rest = raw.replace(ingHead, "").trimStart(' ', ':', '-', '–')
                if (rest.isNotBlank()) ings += toIng(rest)
                continue
            }
            if (stepHead.matches(raw)) { mode = 2; continue }
            val line = raw.replace(bullet, "").replace(Regex("#\\S+"), "").trim()
            if (line.isEmpty() || signOff.containsMatchIn(line)) continue
            if (stepNum.containsMatchIn(line) && !(mode == 1 && qty.matches(line) && unitWord.containsMatchIn(line))) {
                mode = 2; steps += line.replace(stepNum, "").trim(); continue
            }
            when (mode) {
                1 -> if (cookVerb.containsMatchIn(line) && line.length > 25) { mode = 2; steps += line } else ings += toIng(line)
                2 -> steps += line
                else -> when {
                    looksLikeIngredient(line) -> { mode = 1; ings += toIng(line) }
                    title.isEmpty() -> title = line.replace(Regex("\\p{So}|\\uFE0F"), "").replace(Regex("\\s+"), " ").trimEnd('!', '.', ' ').take(70)
                    else -> notes += line
                }
            }
        }
        return Recipe(title = title.ifBlank { "Saved recipe" }, author = author, tags = tags,
            ingredients = ings, steps = steps, notes = notes.joinToString("\n"), url = url)
    }

    private fun looksLikeIngredient(line: String) = !meta.containsMatchIn(line) &&
        line.length < 60 && (qty.matches(line) && unitWord.containsMatchIn(line) || trailingQty.matches(line) || trailingBare.matches(line))

    private fun toIng(line: String): Ingredient {
        qty.find(line)?.let { m -> if (m.groupValues[2].isNotBlank()) return Ingredient(m.groupValues[1].trim(), m.groupValues[2].trim()) }
        trailingQty.find(line)?.let { m -> return Ingredient(m.groupValues[2].trim(), m.groupValues[1].trim()) }
        if (!meta.containsMatchIn(line)) trailingBare.find(line)?.let { m -> return Ingredient(m.groupValues[2].trim(), m.groupValues[1].trim()) }
        return Ingredient(item = line)
    }
}
