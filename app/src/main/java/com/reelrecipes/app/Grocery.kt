package com.reelrecipes.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Where a price comes from. [HUB] is Grocery Hub on your PC, which also covers FreshChoice, The Warehouse and Costco receipts. */
enum class Chain(val label: String, val site: String) {
    WOOLWORTHS("Woolworths", "woolworths"), PAKNSAVE("Pak'nSave", "paknsave"), NEWWORLD("New World", "newworld"), HUB("Grocery Hub", "");
}

/** A store to price against. Woolworths prices are its online prices, so no store id is needed. */
data class StoreRef(val chain: Chain, val id: String = "", val name: String = "") {
    val label get() = if (name.isBlank()) chain.label else name
    fun toJson(): JSONObject = JSONObject().put("chain", chain.name).put("id", id).put("name", name)
    companion object {
        fun fromJson(o: JSONObject?): StoreRef? = o?.let { runCatching { StoreRef(Chain.valueOf(it.getString("chain")), it.optString("id"), it.optString("name")) }.getOrNull() }
    }
}

/**
 * One product on a shelf. [packAmount]/[packKind] is what one pack holds (500 g, 1 L, 12 ea);
 * [byWeight] means it's priced per kg at the counter (meat, loose produce), so you pay for what you use.
 */
data class Product(
    val chain: Chain,
    val id: String,
    val name: String,
    val brand: String,
    val price: Double,
    val wasPrice: Double? = null,
    val isSpecial: Boolean = false,
    val packAmount: Double? = null,
    val packKind: Base.Kind? = null,
    val byWeight: Boolean = false,
    val unitPriceText: String = "",
    val image: String = "",
    val sizeText: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    val displayName get() = listOf(brand, name).filter { it.isNotBlank() }.distinct().joinToString(" ").let { n -> if (sizeText.isNotBlank() && !n.contains(sizeText, true)) "$n $sizeText" else n }

    fun toJson(): JSONObject = JSONObject().apply {
        put("chain", chain.name); put("id", id); put("name", name); put("brand", brand); put("price", price)
        wasPrice?.let { put("wasPrice", it) }; put("isSpecial", isSpecial)
        packAmount?.let { put("packAmount", it) }; packKind?.let { put("packKind", it.name) }
        put("byWeight", byWeight); put("unitPriceText", unitPriceText); put("image", image); put("sizeText", sizeText); put("fetchedAt", fetchedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Product? = runCatching {
            Product(
                Chain.valueOf(o.getString("chain")), o.getString("id"), o.optString("name"), o.optString("brand"), o.getDouble("price"),
                if (o.has("wasPrice")) o.getDouble("wasPrice") else null, o.optBoolean("isSpecial"),
                if (o.has("packAmount")) o.getDouble("packAmount") else null,
                o.optString("packKind").takeIf { it.isNotBlank() }?.let { Base.Kind.valueOf(it) },
                o.optBoolean("byWeight"), o.optString("unitPriceText"), o.optString("image"), o.optString("sizeText"), o.optLong("fetchedAt"),
            )
        }.getOrNull()

        private val SIZE = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:x\\s*(\\d+(?:\\.\\d+)?)\\s*)?(kg|g|ml|l|litre|litres|ltr|pk|pack|ea|each|s)\\b", RegexOption.IGNORE_CASE)

        /** Reads "500g", "1.5kg", "2L", "12pk", "6 x 250ml" into an amount and kind. */
        fun parseSize(text: String): Pair<Double, Base.Kind>? {
            val m = SIZE.findAll(text).lastOrNull() ?: return null
            var v = m.groupValues[1].toDoubleOrNull() ?: return null
            m.groupValues[2].toDoubleOrNull()?.let { v *= it }
            return when (m.groupValues[3].lowercase()) {
                "kg" -> v * 1000 to Base.Kind.G
                "g" -> v to Base.Kind.G
                "l", "litre", "litres", "ltr" -> v * 1000 to Base.Kind.ML
                "ml" -> v to Base.Kind.ML
                else -> v to Base.Kind.EA
            }
        }
    }
}

class PriceException(message: String) : Exception(message)

/**
 * Live prices. Supermarkets are asked through the same product searches their own websites use
 * (the ones Grocery Hub documents), with the app identifying itself honestly. Nothing here disguises
 * the app or works around a store's bot checks: if a store blocks it, the error says so and its prices
 * come from Grocery Hub, receipts, or a price you type in.
 */
object Grocery {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    /** Grocery Hub's address on the home network, e.g. http://192.168.1.20:8765. Set from Settings. */
    @Volatile var hubUrl: String = ""

    /** Last raw response per source, shown in Settings → Test prices to help fix breakages. */
    val lastDebug = mutableMapOf<Chain, String>()

    suspend fun search(store: StoreRef, query: String, limit: Int = 12): List<Product> = withContext(Dispatchers.IO) {
        when (store.chain) {
            Chain.WOOLWORTHS -> woolworths(query, limit)
            Chain.PAKNSAVE, Chain.NEWWORLD -> foodstuffs(store, query, limit)
            Chain.HUB -> hub(store, query, limit)
        }
    }

    // ------------------------------------------------------------------ Woolworths NZ

    private fun woolworths(query: String, limit: Int): List<Product> {
        val url = "https://www.woolworths.co.nz/api/v1/products?target=search&search=${enc(query)}&inStockProductsOnly=false&size=${limit.coerceAtMost(48)}"
        // x-requested-with is the header this endpoint requires (Grocery Hub sends the same).
        val body = get(url, mapOf("x-requested-with" to "OnlineShopping.WebApp", "Accept" to "application/json"), Chain.WOOLWORTHS)
        val root = JSONObject(body)
        val items = root.optJSONObject("products")?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "Product") return@mapNotNull null
            val pr = o.optJSONObject("price") ?: return@mapNotNull null
            val sale = pr.optDouble("salePrice").takeIf { !it.isNaN() && it > 0 } ?: return@mapNotNull null
            val orig = pr.optDouble("originalPrice").takeIf { !it.isNaN() && it > 0 }
            val size = o.optJSONObject("size")
            val volume = size?.optString("volumeSize").orEmpty()
            val byKg = o.optString("unit").equals("kg", true)
            val parsed = if (byKg) 1000.0 to Base.Kind.G else Product.parseSize(volume) ?: Product.parseSize(o.optString("name"))
            val cup = size?.optDouble("cupPrice")?.takeIf { !it.isNaN() && it > 0 }
            val cupMeasure = size?.optString("cupMeasure").orEmpty()
            Product(
                chain = Chain.WOOLWORTHS,
                id = o.optString("sku").ifBlank { o.optString("barcode") },
                name = o.optString("name").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
                brand = o.optString("brand").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
                price = sale,
                wasPrice = orig?.takeIf { it > sale + 0.001 },
                isSpecial = pr.optBoolean("isSpecial") || (orig != null && orig > sale + 0.001),
                packAmount = parsed?.first, packKind = parsed?.second, byWeight = byKg,
                unitPriceText = when { byKg -> "$%.2f / kg".format(sale); cup != null && cupMeasure.isNotBlank() -> "$%.2f / %s".format(cup, cupMeasure); else -> "" },
                image = o.optJSONObject("images")?.optString("small").orEmpty(),
                sizeText = if (byKg) "per kg" else volume,
            )
        }
    }

    // ------------------------------------------------------------------ Pak'nSave / New World (Foodstuffs web API)

    private val tokens = mutableMapOf<Chain, Pair<String, Long>>()
    private val tokenLock = Mutex()

    /** A short-lived guest token, the same one their website fetches for every visitor. */
    private suspend fun token(chain: Chain): String = tokenLock.withLock {
        tokens[chain]?.let { (t, exp) -> if (System.currentTimeMillis() < exp) return t }
        val body = post("https://www.${chain.site}.co.nz/api/user/get-current-user", "{}", mapOf("Accept" to "application/json"), chain)
        val t = JSONObject(body).optString("access_token")
        if (t.isBlank()) throw PriceException("${chain.label} didn't hand out a guest token, so its prices can't be read from the phone right now.")
        tokens[chain] = t to System.currentTimeMillis() + 10 * 60_000L
        t
    }

    private suspend fun fsHeaders(chain: Chain) = mapOf("Authorization" to "Bearer ${token(chain)}", "Accept" to "application/json")

    /** Lists the chain's stores so you can pick yours. */
    suspend fun stores(chain: Chain): List<StoreRef> = withContext(Dispatchers.IO) {
        when (chain) {
            Chain.WOOLWORTHS -> listOf(StoreRef(Chain.WOOLWORTHS, "", "Woolworths (national prices)"))
            Chain.HUB -> hubStores()
            else -> {
                val body = get("https://api-prod.${chain.site}.co.nz/v1/edge/store", fsHeaders(chain), chain)
                val arr = JSONObject(body).optJSONArray("stores") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    if (id.isBlank()) null else StoreRef(chain, id, o.optString("name").ifBlank { chain.label })
                }.sortedBy { it.name }
            }
        }
    }

    private suspend fun foodstuffs(store: StoreRef, query: String, limit: Int): List<Product> {
        if (store.id.isBlank()) throw PriceException("Pick your ${store.chain.label} store in Settings first.")
        val req = JSONObject()
            .put("algoliaQuery", JSONObject().put("attributesToHighlight", JSONArray()).put("attributesToRetrieve", JSONArray(listOf("productID", "Type")))
                .put("facets", JSONArray()).put("filters", "stores:${store.id}").put("hitsPerPage", limit).put("page", 0).put("query", query))
            .put("algoliaFacetQueries", JSONArray()).put("storeId", store.id).put("hitsPerPage", limit).put("page", 0)
            .put("sortOrder", "NI_POPULARITY_ASC").put("tobaccoQuery", false)
        val url = "https://api-prod.${store.chain.site}.co.nz/v1/edge/search/paginated/products"
        val body = try { post(url, req.toString(), fsHeaders(store.chain), store.chain) }
            catch (e: PriceException) { tokens.remove(store.chain); throw e }
        val arr = JSONObject(body).optJSONArray("products") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> parseFoodstuffs(arr.optJSONObject(i) ?: return@mapNotNull null, store.chain) }
    }

    /** Same rules as Grocery Hub's parse_foodstuffs: prices in cents, promotions can set a new price. */
    private fun parseFoodstuffs(p: JSONObject, chain: Chain): Product? {
        val sp = p.optJSONObject("singlePrice") ?: return null
        if (!sp.has("price")) return null
        val usual = sp.optDouble("price") / 100.0
        var price = usual
        var special = false
        var member = false
        var promo = ""
        val promos = p.optJSONArray("promotions")
        val best = promos?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.let { l -> l.firstOrNull { it.optBoolean("bestPromotion") } ?: l.firstOrNull() } }
        if (best != null) {
            special = true
            member = best.optBoolean("cardDependencyFlag")
            if (best.optString("rewardType") == "NEW_PRICE" && best.optDouble("rewardValue", 0.0) > 0) price = best.optDouble("rewardValue") / 100.0
            if (best.optInt("threshold", 1) > 1 || best.optBoolean("multiProducts")) promo = "Multi-buy (${best.optInt("threshold")}+)"
        }
        val byKg = p.optString("saleType") == "WEIGHT"
        val sizeText = p.optString("displayName")
        val size = if (byKg) 1000.0 to Base.Kind.G else Product.parseSize(sizeText) ?: Product.parseSize(p.optString("name"))
        val cp = (best?.optJSONObject("comparativePrice") ?: sp.optJSONObject("comparativePrice"))
        val unitText = cp?.let { "$%.2f / %s%s".format(it.optDouble("pricePerUnit") / 100.0, it.optString("unitQuantity", "1").let { q -> if (q == "1") "" else q }, it.optString("unitQuantityUom")) }.orEmpty()
        return Product(
            chain = chain, id = p.optString("productId"), name = p.optString("name"), brand = p.optString("brand"),
            price = price, wasPrice = usual.takeIf { it > price + 0.001 }, isSpecial = special,
            packAmount = size?.first, packKind = size?.second, byWeight = byKg,
            unitPriceText = listOf(if (byKg) "$%.2f / kg".format(price) else unitText, if (member) "Club price" else "", promo).filter { it.isNotBlank() }.joinToString(" · "),
            sizeText = if (byKg) "per kg" else sizeText,
        )
    }

    // ------------------------------------------------------------------ Grocery Hub (your PC)

    private fun hubBase(): String {
        val u = hubUrl.trim().trimEnd('/')
        if (u.isBlank()) throw PriceException("Add Grocery Hub's address in Settings first.")
        return if (u.startsWith("http")) u else "http://$u"
    }

    private fun hubStores(): List<StoreRef> {
        val body = get(hubBase() + "/api/meta", emptyMap(), Chain.HUB)
        val arr = JSONObject(body).optJSONArray("stores") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { StoreRef(Chain.HUB, it.optString("key"), it.optString("name")) }
        }.filter { it.id.isNotBlank() }
    }

    /** Latest prices Grocery Hub has for this ingredient at one store (asks Hub to start checking it if it hasn't yet). */
    private fun hub(store: StoreRef, query: String, limit: Int): List<Product> {
        val body = get(hubBase() + "/api/lookup?q=${enc(query)}&store_key=${enc(store.id)}&limit=$limit", emptyMap(), Chain.HUB)
        val arr = JSONObject(body).optJSONArray("products") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { hubProduct(it) } }
    }

    /** Converts one of Grocery Hub's normalised records. */
    fun hubProduct(o: JSONObject): Product? {
        val price = o.optDouble("price").takeIf { !it.isNaN() && it > 0 } ?: return null
        val unit = o.optString("unit")
        val unitPrice = o.optDouble("unit_price").takeIf { !it.isNaN() && it > 0 }
        val size = o.optString("size")
        val byKg = unit.equals("kg", true) && unitPrice != null && kotlin.math.abs(unitPrice - price) < 0.006 && Product.parseSize(size) == null
        var pack = if (byKg) 1000.0 to Base.Kind.G else Product.parseSize(size) ?: Product.parseSize(o.optString("name"))
        // No size written anywhere: work it out from the unit price (price ÷ $/kg = kg in the pack).
        if (pack == null && unitPrice != null) pack = when (unit.lowercase()) {
            "kg" -> price / unitPrice * 1000 to Base.Kind.G
            "l" -> price / unitPrice * 1000 to Base.Kind.ML
            else -> price / unitPrice to Base.Kind.EA
        }
        val was = o.optDouble("was_price").takeIf { !it.isNaN() && it > price + 0.001 }
        return Product(
            chain = Chain.HUB, id = o.optString("store_key") + ":" + o.optString("product_id"), name = o.optString("name"), brand = "",
            price = price, wasPrice = was, isSpecial = o.optBoolean("is_special") || o.optInt("is_special", 0) == 1,
            packAmount = pack?.first, packKind = pack?.second, byWeight = byKg,
            unitPriceText = listOfNotNull(unitPrice?.let { "$%.2f / %s".format(it, unit) }, o.optString("promo_text").takeIf { it.isNotBlank() },
                o.optString("store_name").takeIf { it.isNotBlank() }, o.optString("scraped_at").take(10).takeIf { it.isNotBlank() }?.let { "checked $it" }).joinToString(" · "),
            image = o.optString("image"), sizeText = if (byKg) "per kg" else size,
        )
    }

    /** Sends a recipe to Grocery Hub so its ingredients are price-checked every morning and recipe deal alerts work. */
    suspend fun sendRecipe(r: Recipe): String = withContext(Dispatchers.IO) {
        val lines = r.ingredients.map { listOf(it.qty, it.item).filter { s -> s.isNotBlank() }.joinToString(" ") }
        val body = JSONObject().put("title", r.title + if (r.variantName.isNotBlank()) " (${r.variantName})" else "")
            .put("url", r.url).put("source", "reel-recipes").put("ingredients", JSONArray(lines))
        val res = JSONObject(post(hubBase() + "/api/recipes", body.toString(), emptyMap(), Chain.HUB))
        "Sent ${res.optInt("ingredients")} ingredients to Grocery Hub"
    }

    // ------------------------------------------------------------------ HTTP

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, headers: Map<String, String>, chain: Chain): String = call(Request.Builder().url(url).get(), headers, chain)

    private fun post(url: String, body: String, headers: Map<String, String>, chain: Chain): String =
        call(Request.Builder().url(url).post(body.toRequestBody(JSON)), headers, chain)

    private fun call(b: Request.Builder, headers: Map<String, String>, chain: Chain): String {
        b.header("User-Agent", PostReader.UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        val req = b.build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                lastDebug[chain] = "${req.method} ${req.url.host}${req.url.encodedPath} → HTTP ${r.code}\n" + text.take(1500)
                if (r.code == 403 || r.code == 429) throw PriceException("${chain.label} declined the request (error ${r.code}). It may not allow apps to read prices; use Grocery Hub, receipts or your own price for this store.")
                if (!r.isSuccessful) throw PriceException("${chain.label} prices didn't load (error ${r.code}).")
                if (text.trimStart().startsWith("<")) throw PriceException("${chain.label} sent a web page instead of prices. It may not allow apps to read prices; use Grocery Hub, receipts or your own price for this store.")
                return text
            }
        } catch (e: PriceException) {
            throw e
        } catch (e: Exception) {
            lastDebug[chain] = "${req.method} ${req.url.host}${req.url.encodedPath} → ${e.javaClass.simpleName}: ${e.message}"
            throw PriceException(if (chain == Chain.HUB) "Couldn't reach Grocery Hub. Check the PC is on with run.bat open, and the phone is on home Wi-Fi." else "Couldn't reach ${chain.label}. Check your internet connection.")
        }
    }
}
