package com.reelrecipes.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Ingredient(val qty: String = "", val item: String, val note: String = "")

data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val author: String = "",
    val servings: String = "",
    val time: String = "",
    val tags: List<String> = emptyList(),
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val notes: String = "",
    val url: String = "",
    /** File names inside the app's photos folder; the first is the cover. */
    val photos: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("author", author)
        put("servings", servings); put("time", time)
        put("tags", JSONArray(tags))
        put("ingredients", JSONArray(ingredients.map {
            JSONObject().put("qty", it.qty).put("item", it.item).put("note", it.note)
        }))
        put("steps", JSONArray(steps))
        put("notes", notes); put("url", url)
        put("photos", JSONArray(photos))
        put("createdAt", createdAt)
    }

    fun matches(q: String): Boolean {
        if (q.isBlank()) return true
        val hay = (listOf(title, author) + tags + ingredients.map { it.item }).joinToString(" ").lowercase()
        return hay.contains(q.trim().lowercase())
    }

    companion object {
        fun fromJson(o: JSONObject): Recipe = Recipe(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title"),
            author = o.optString("author"),
            servings = o.optString("servings"),
            time = o.optString("time"),
            tags = o.optJSONArray("tags").strings(),
            ingredients = o.optJSONArray("ingredients")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        Ingredient(it.optString("qty"), it.optString("item"), it.optString("note"))
                    }
                }
            } ?: emptyList(),
            steps = o.optJSONArray("steps").strings(),
            notes = o.optString("notes"),
            url = o.optString("url"),
            photos = o.optJSONArray("photos").strings(),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList()
    else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

/** "200 g | spaghetti | note" per line — the same editing format the web app uses. */
fun ingredientsToText(list: List<Ingredient>): String = list.joinToString("\n") { i ->
    when {
        i.qty.isBlank() && i.note.isBlank() -> i.item
        i.note.isBlank() -> "${i.qty} | ${i.item}"
        else -> "${i.qty} | ${i.item} | ${i.note}"
    }
}

fun textToIngredients(text: String): List<Ingredient> = text.lines()
    .map { it.trim() }.filter { it.isNotEmpty() }
    .map { line ->
        val p = line.split("|").map { it.trim() }
        when (p.size) {
            1 -> Ingredient(item = p[0])
            2 -> Ingredient(qty = p[0], item = p[1])
            else -> Ingredient(qty = p[0], item = p[1], note = p.drop(2).joinToString(" | "))
        }
    }
