package com.reelrecipes.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remembers which product each ingredient maps to at each store, keeps prices fresh
 * (re-checked after 12 hours), and stores the store choices.
 */
class PriceBook(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "prices.json")
    private val prefs = context.getSharedPreferences("prices", Context.MODE_PRIVATE)

    /** key = "<chain>|<storeId>|<ingredient search words>" */
    private val matches = java.util.concurrent.ConcurrentHashMap<String, Product>()
    /** Ingredients you've said are unavailable/not needed at a store. */
    private val skipped: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** recipeId → cost per serve at the main store, for list filters and sorting. */
    private val _perServe = MutableStateFlow<Map<String, Double>>(emptyMap())
    val perServe: StateFlow<Map<String, Double>> = _perServe

    private val limiter = Semaphore(4)

    init { load(); Grocery.hubUrl = hubUrl }

    /** Grocery Hub on the home PC, e.g. http://192.168.1.20:8765. Blank = not used. */
    var hubUrl: String
        get() = prefs.getString("hub", "") ?: ""
        set(v) { prefs.edit().putString("hub", v.trim()).apply(); Grocery.hubUrl = v.trim() }

    var store: StoreRef
        get() = StoreRef.fromJson(prefs.getString("store", null)?.let { runCatching { JSONObject(it) }.getOrNull() })?.let(::tidy) ?: StoreRef(Chain.WOOLWORTHS, "", "Woolworths (national prices)")
        set(v) { prefs.edit().putString("store", v.toJson().toString()).apply() }

    /** Other stores to compare a shopping list against. */
    var compare: List<StoreRef>
        get() = prefs.getString("compare", null)?.let { s -> runCatching { JSONArray(s) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { StoreRef.fromJson(a.optJSONObject(it))?.let(::tidy) } } } ?: emptyList()
        set(v) { prefs.edit().putString("compare", JSONArray(v.map { it.toJson() }).toString()).apply() }

    private fun tidy(s: StoreRef) = if (s.chain == Chain.WOOLWORTHS) StoreRef(Chain.WOOLWORTHS, "", "Woolworths (national prices)") else s

    val allStores get() = (listOf(store) + compare).distinctBy { it.chain to it.id }

    private fun key(store: StoreRef, item: String) = "${store.chain}|${store.id}|${Costing.searchQuery(item)}"

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            o.optJSONObject("matches")?.let { m -> m.keys().forEach { k -> Product.fromJson(m.getJSONObject(k))?.let { matches[k] = it } } }
            o.optJSONArray("skipped")?.let { a -> (0 until a.length()).forEach { skipped += a.getString(it) } }
            o.optJSONObject("perServe")?.let { m -> _perServe.value = m.keys().asSequence().associateWith { m.getDouble(it) } }
        }
    }

    @Synchronized private fun persist() {
        val o = JSONObject()
        o.put("matches", JSONObject().apply { matches.forEach { (k, v) -> put(k, v.toJson()) } })
        o.put("skipped", JSONArray(skipped.toList()))
        o.put("perServe", JSONObject().apply { _perServe.value.forEach { (k, v) -> put(k, v) } })
        val tmp = File(dir, "prices.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(file)
    }

    fun matchFor(store: StoreRef, item: String): Product? = matches[key(store, item)]

    /** You picked a different product (or typed your own price) for this ingredient. */
    fun choose(store: StoreRef, item: String, p: Product) { matches[key(store, item)] = p; skipped -= key(store, item); persist() }

    fun manual(store: StoreRef, item: String, price: Double, size: String) {
        val s = Product.parseSize(size)
        choose(store, item, Product(store.chain, "manual", Costing.searchQuery(item).replaceFirstChar { it.uppercase() }, "Your price", price,
            packAmount = s?.first, packKind = s?.second, sizeText = size, fetchedAt = Long.MAX_VALUE / 2))
    }

    fun forget(store: StoreRef, item: String) { matches.remove(key(store, item)); persist() }

    fun rememberPerServe(recipeId: String, v: Double) { _perServe.value = _perServe.value + (recipeId to v); persist() }

    /** Finds (or refreshes) the product for one ingredient. */
    suspend fun productFor(store: StoreRef, ing: Ingredient, factor: Double, refresh: Boolean): Pair<Product?, String> {
        val k = key(store, ing.item)
        val known = matches[k]
        val fresh = known != null && (known.id == "manual" || System.currentTimeMillis() - known.fetchedAt < 12 * 3600_000L)
        if (known != null && fresh && !refresh) return known to ""
        val query = Costing.searchQuery(ing.item)
        return try {
            val results = limiter.withPermit { Grocery.search(store, query) }
            val updated = known?.let { k0 -> results.firstOrNull { it.id == k0.id } }
            val p = updated ?: Costing.pickBest(query, Costing.need(ing, factor), ing.item, results)
            if (p != null) { matches[k] = p; persist() }
            (p ?: known) to if (p == null && known == null) "No match found for “$query”" else ""
        } catch (e: Exception) {
            known to (e.message ?: "Price lookup failed")
        }
    }

    /** Prices every line of a recipe at [servings]. */
    suspend fun mealCost(r: Recipe, servings: Int, store: StoreRef = this.store, refresh: Boolean = false): MealCost = withContext(Dispatchers.IO) {
        val factor = servings.toDouble() / r.baseServings
        val lines = coroutineScope {
            r.ingredients.map { ing ->
                async {
                    if (MealCost.isFree(ing.item)) Costing.line(ing, factor, null)
                    else { val (p, err) = productFor(store, ing, factor, refresh); Costing.line(ing, factor, p, err) }
                }
            }.awaitAll()
        }
        val m = MealCost(lines, servings, store.label)
        if (store == this@PriceBook.store && m.lines.any { it.product != null }) rememberPerServe(r.id, m.perServe)
        m
    }

    /** Every price known at [store], as JSON the Reel Recipes web page can import. */
    fun exportJson(store: StoreRef = this.store): String {
        val prefix = "${store.chain}|${store.id}|"
        val arr = JSONArray()
        matches.filterKeys { it.startsWith(prefix) }.forEach { (k, p) ->
            arr.put(JSONObject().put("key", k.removePrefix(prefix)).put("name", p.displayName).put("price", p.price)
                .put("size", if (p.byWeight) "per kg" else p.sizeText).put("byWeight", p.byWeight).put("store", store.label))
        }
        return arr.toString()
    }

    fun count(store: StoreRef = this.store) = matches.keys.count { it.startsWith("${store.chain}|${store.id}|") }

    /** Search results for the product picker. */
    suspend fun options(store: StoreRef, query: String): List<Product> = Grocery.search(store, query, 24)
}
