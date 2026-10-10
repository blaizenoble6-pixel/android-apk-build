package com.reelrecipes.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Recipes live in one JSON file on the phone; photos sit next to it. */
class RecipeStore(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "recipes.json")
    val photoDir = File(dir, "photos").apply { mkdirs() }

    private val _recipes = MutableStateFlow(load())
    val recipes: StateFlow<List<Recipe>> = _recipes

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) { prefs.edit().putString("api_key", v.trim()).apply() }

    fun photo(name: String) = File(photoDir, name)

    private fun load(): List<Recipe> = try {
        if (!file.exists()) emptyList()
        else JSONArray(file.readText()).let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Recipe::fromJson) }
        }.sortedByDescending { it.createdAt }
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(list: List<Recipe>) {
        val tmp = File(dir, "recipes.json.tmp")
        tmp.writeText(JSONArray(list.map { it.toJson() }).toString())
        tmp.renameTo(file)
        _recipes.value = list.sortedByDescending { it.createdAt }
    }

    suspend fun savePhoto(jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString() + ".jpg"
        photo(name).writeBytes(jpeg)
        name
    }

    suspend fun save(recipe: Recipe) = withContext(Dispatchers.IO) {
        val old = _recipes.value.firstOrNull { it.id == recipe.id }
        // Remove photos the user took off the recipe while editing.
        old?.photos?.filterNot { it in recipe.photos }?.forEach { photo(it).delete() }
        persist(_recipes.value.filterNot { it.id == recipe.id } + recipe)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        _recipes.value.firstOrNull { it.id == id }?.photos?.forEach { photo(it).delete() }
        persist(_recipes.value.filterNot { it.id == id })
    }
}
