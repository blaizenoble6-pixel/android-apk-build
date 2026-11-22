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

/** Which supermarket chain a price comes from. */
enum class Chain(val label: String, val banner: String) {
    WOOLWORTHS("Woolworths", ""), PAKNSAVE("Pak'nSave", "PNS"), NEWWORLD("New World", "MNW");
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
 * Live supermarket prices, read from the same product searches the supermarkets' own
 * websites and apps use. These aren't official public APIs: they can change without
 * notice, so every failure comes back as a clear message and the app keeps working
 * with the last prices it saw (or ones you enter).
 */
object Grocery {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private const val UA = PostReader.UA
    private val JSON = "application/json".toMediaType()

    /** Last raw response per chain, shown in Settings → Test prices to help fix breakages. */
    val lastDebug = mutableMapOf<Chain, String>()

    suspend fun search(store: StoreRef, query: String, limit: Int = 12): List<Product> = withContext(Dispatchers.IO) {
        when (store.chain) {
            Chain.WOOLWORTHS -> woolworths(query, limit)
            Chain.PAKNSAVE, Chain.NEWWORLD -> foodstuffs(store, query, limit)
        }
    }

    // ------------------------------------------------------------------ Woolworths NZ

    private fun woolworths(query: String, limit: Int): List<Product> {
        val url = "https://www.woolworths.co.nz/api/v1/products?target=search&search=${enc(query)}&inStockProductsOnly=false&size=${limit.coerceAtMost(48)}"
        val body = get(url, mapOf(
            "x-requested-with" to "OnlineShopping.WebApp",
            "x-ui-ver" to "7.60.32",
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "https://www.woolworths.co.nz/shop/searchproducts?search=${enc(query)}",
        ), Chain.WOOLWORTHS)
        val root = JSONObject(body)
        val items = root.optJSONObject("products")?.optJSONArray("items") ?: root.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type", "Product") != "Product") return@mapNotNull null
            val pr = o.optJSONObject("price") ?: return@mapNotNull null
            val sale = pr.optDouble("salePrice").takeIf { !it.isNaN() && it > 0 } ?: return@mapNotNull null
            val orig = pr.optDouble("originalPrice").takeIf { !it.isNaN() && it > 0 }
            val size = o.optJSONObject("size")
            val volume = size?.optString("volumeSize").orEmpty()
            val unit = o.optString("unit") // "Each" or "Kg"
            val byKg = unit.equals("kg", true)
            val parsed = if (byKg) 1000.0 to Base.Kind.G else Product.parseSize(volume) ?: Product.parseSize(o.optString("name"))
            val cup = size?.optDouble("cupPrice")?.takeIf { !it.isNaN() && it > 0 }
            val cupMeasure = size?.optString("cupMeasure").orEmpty()
            Product(
                chain = Chain.WOOLWORTHS,
                id = o.optString("sku").ifBlank { o.optString("barcode") },
                name = o.optString("name"),
                brand = o.optString("brand").replaceFirstChar { it.uppercase() },
                price = sale,
                wasPrice = orig?.takeIf { it > sale + 0.001 },
                isSpecial = pr.optBoolean("isSpecial") || (orig != null && orig > sale + 0.001),
                packAmount = parsed?.first, packKind = parsed?.second, byWeight = byKg,
                unitPriceText = if (cup != null && cupMeasure.isNotBlank()) "$%.2f / %s".format(cup, cupMeasure) else if (byKg) "$%.2f / kg".format(sale) else "",
                image = o.optJSONObject("images")?.optString("big").orEmpty(),
                sizeText = if (byKg) "per kg" else volume,
            )
        }
    }

    // ------------------------------------------------------------------ Pak'nSave / New World (Foodstuffs)

    private const val FS = "https://api-prod.prod.fsniwaikato.kiwi/prod/mobile"
    private const val FS_UA = "PAKnSAVEApp/4.32.0"
    private val tokens = mutableMapOf<Chain, Pair<String, Long>>()
    private val tokenLock = Mutex()

    private suspend fun token(chain: Chain): String = tokenLock.withLock {
        tokens[chain]?.let { (t, exp) -> if (System.currentTimeMillis() < exp) return t }
        val body = post("$FS/user/login/guest", JSONObject().put("banner", chain.banner).toString(), mapOf("User-Agent" to FS_UA), chain)
        val o = JSONObject(body)
        val t = o.optString("access_token").ifBlank { o.optString("accessToken") }
        if (t.isBlank()) throw PriceException("${chain.label} didn't return a sign-in token.")
        val life = o.optLong("expires_in", 1800).coerceAtLeast(120)
        tokens[chain] = t to System.currentTimeMillis() + (life - 60) * 1000
        t
    }

    private suspend fun fsHeaders(chain: Chain): Map<String, String> {
        val t = token(chain)
        return mapOf("User-Agent" to FS_UA, "authorization" to "Bearer $t", "access_token" to t, "Accept" to "application/json")
    }

    /** Lists the chain's stores so you can pick yours (e.g. Pak'nSave Mt Albert). */
    suspend fun stores(chain: Chain): List<StoreRef> = withContext(Dispatchers.IO) {
        if (chain == Chain.WOOLWORTHS) return@withContext listOf(StoreRef(Chain.WOOLWORTHS, "", "Woolworths (online prices)"))
        val body = get("$FS/store/physical", fsHeaders(chain), chain)
        val arr = runCatching { JSONArray(body) }.getOrElse { JSONObject(body).optJSONArray("stores") ?: JSONArray() }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val banner = o.optString("banner")
            if (banner.isNotBlank() && !banner.equals(chain.banner, true) && !o.optString("name").contains(chain.label.take(3), true)) return@mapNotNull null
            val id = o.optString("id").ifBlank { o.optString("storeId") }
            if (id.isBlank()) null else StoreRef(chain, id, o.optString("name").ifBlank { chain.label })
        }.sortedBy { it.name }
    }

    private suspend fun foodstuffs(store: StoreRef, query: String, limit: Int): List<Product> {
        if (store.id.isBlank()) throw PriceException("Pick your ${store.chain.label} store in Settings first.")
        val url = "$FS/ecomm-products/${store.chain.banner}/${store.id}/search?q=${enc(query)}&sortOrder=relevance&disableAdsOverride=true"
        val body = post(url, "[]", fsHeaders(store.chain), store.chain)
        val root = JSONObject(body)
        val arr = root.optJSONArray("products") ?: JSONArray()
        return (0 until minOf(arr.length(), limit)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val raw = o.optDouble("price")
            if (raw.isNaN() || raw <= 0) return@mapNotNull null
            val price = centsOrDollars(o.opt("price"), raw)
            val units = o.optString("units") // "kg" for by-weight, or a pack size
            val byKg = units.equals("kg", true) || o.optString("saleType").equals("WEIGHT", true)
            val name = o.optString("name")
            val size = if (byKg) 1000.0 to Base.Kind.G else Product.parseSize(units) ?: Product.parseSize(o.optString("displayName")) ?: Product.parseSize(name)
            val was = o.optDouble("originalPrice").takeIf { !it.isNaN() && it > 0 }?.let { centsOrDollars(o.opt("originalPrice"), it) }
            Product(
                chain = store.chain,
                id = o.optString("productId"),
                name = name,
                brand = o.optString("brand"),
                price = price,
                wasPrice = was?.takeIf { it > price + 0.001 },
                isSpecial = o.optBoolean("isSpecial") || o.optString("saleType").contains("special", true) || (was != null && was > price + 0.001),
                packAmount = size?.first, packKind = size?.second, byWeight = byKg,
                unitPriceText = o.optString("unitPrice"),
                image = o.optJSONObject("productImageUrls")?.let { it.optString("400").ifBlank { it.optString("200") } }.orEmpty(),
                sizeText = if (byKg) "per kg" else units.takeIf { !it.equals("ea", true) }.orEmpty(),
            )
        }
    }

    /** Foodstuffs sends whole cents (399 = $3.99); a decimal value is already dollars. */
    private fun centsOrDollars(v: Any?, d: Double): Double = when {
        v is Int || v is Long -> d / 100.0
        d != Math.floor(d) -> d
        d >= 100 -> d / 100.0
        else -> d
    }

    // ------------------------------------------------------------------ HTTP

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, headers: Map<String, String>, chain: Chain): String = call(Request.Builder().url(url).get(), headers, chain)

    private fun post(url: String, body: String, headers: Map<String, String>, chain: Chain): String =
        call(Request.Builder().url(url).post(body.toRequestBody(JSON)), headers, chain)

    private fun call(b: Request.Builder, headers: Map<String, String>, chain: Chain): String {
        if (!headers.containsKey("User-Agent")) b.header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        val req = b.build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                lastDebug[chain] = "${req.method} ${req.url.encodedPath} → HTTP ${r.code}\n" + text.take(1500)
                if (!r.isSuccessful) throw PriceException("${chain.label} prices didn't load (error ${r.code}). The supermarket may have changed its site.")
                if (text.trimStart().startsWith("<")) throw PriceException("${chain.label} sent a web page instead of prices. It may be blocking the request.")
                return text
            }
        } catch (e: PriceException) {
            throw e
        } catch (e: Exception) {
            lastDebug[chain] = "${req.method} ${req.url.encodedPath} → ${e.javaClass.simpleName}: ${e.message}"
            throw PriceException("Couldn't reach ${chain.label}. Check your internet connection.")
        }
    }
}
