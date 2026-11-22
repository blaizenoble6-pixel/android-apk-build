package com.reelrecipes.app

import kotlin.math.ceil
import kotlin.math.max

/** What one ingredient line costs at a store. */
data class LineCost(
    val ingredient: Ingredient,
    val scaledQty: String,
    val product: Product?,
    /** Cost of the amount the recipe uses (a share of the pack). */
    val useCost: Double?,
    /** Cost of the whole packs you'd need to buy. */
    val buyCost: Double?,
    val packs: Int,
    val pantry: Boolean,
    val note: String = "",
    val error: String = "",
)

data class MealCost(val lines: List<LineCost>, val servings: Int, val store: String) {
    val total: Double get() = lines.sumOf { it.useCost ?: 0.0 }
    val perServe: Double get() = if (servings > 0) total / servings else total
    /** Shopping from scratch, excluding pantry staples you probably already have. */
    val basket: Double get() = lines.filter { !it.pantry }.sumOf { it.buyCost ?: 0.0 }
    val unpriced: Int get() = lines.count { it.product == null && !isFree(it.ingredient.item) }
    val specials: Int get() = lines.count { it.product?.isSpecial == true }

    companion object {
        fun isFree(item: String) = Regex("^\\s*(water|ice|boiling water|cold water|hot water)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item)
    }
}

/** The costing maths. Kept free of Android classes so it's unit tested. */
object Costing {
    private val PANTRY = listOf(
        "salt", "pepper", "olive oil", "vegetable oil", "canola oil", "oil", "cooking spray", "spray oil", "paprika", "cumin", "oregano",
        "cinnamon", "chilli flakes", "chili flakes", "garlic powder", "onion powder", "curry powder", "turmeric", "coriander seed",
        "dried", "soy sauce", "vinegar", "stock cube", "stock powder", "baking powder", "baking soda", "bicarbonate", "vanilla",
        "mixed herbs", "italian herbs", "cayenne", "nutmeg", "fish sauce", "worcestershire", "sesame oil", "honey", "sugar", "flour",
        "mustard", "tomato sauce", "ketchup", "mayonnaise", "mayo", "sriracha", "hot sauce",
    )
    private val NOISE = setOf(
        "fresh", "large", "small", "medium", "finely", "roughly", "thinly", "chopped", "diced", "sliced", "minced", "grated", "crushed",
        "peeled", "boneless", "skinless", "skin-on", "ripe", "organic", "optional", "extra", "cooked", "uncooked", "raw", "whole", "packed",
        "softened", "melted", "cold", "warm", "hot", "room", "temperature", "about", "approx", "plus", "for", "to", "serve", "garnish",
        "of", "the", "a", "an", "and", "or", "your", "favourite", "favorite", "good", "quality", "store-bought", "homemade", "lightly",
        "beaten", "drained", "rinsed", "halved", "quartered", "cubed", "shredded", "torn", "trimmed", "deseeded", "de-seeded", "seeded",
        "freshly", "ground", "squeezed", "juiced", "zested", "zest", "juice", "pieces", "piece", "bite-sized", "sized", "thick", "thin",
        "heaped", "level", "generous", "pinch", "handful", "splash", "dash", "few", "some", "more", "needed", "taste", "frozen", "tinned", "canned",
    )

    fun isPantry(item: String): Boolean {
        val t = item.lowercase()
        if (Regex("\\b(chicken|beef|pork|lamb|fish|salmon|mince|cheese|cream|milk|egg)").containsMatchIn(t)) return false
        return PANTRY.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }
    }

    /** "2 boneless chicken thighs, diced" → "chicken thighs". */
    fun searchQuery(item: String): String {
        val core = item.lowercase()
            .replace(Regex("\\(.*?\\)"), " ")
            .substringBefore(",").substringBefore(";").substringBefore(" - ").substringBefore(" or ")
            .replace(Regex("[^a-zà-ÿ' -]"), " ")
        val words = core.split(Regex("\\s+")).filter { it.isNotBlank() && it !in NOISE && it.length > 1 }
        return words.takeLast(4).joinToString(" ").ifBlank { item.trim() }
    }

    private fun stem(w: String) = w.lowercase().removeSuffix("es").removeSuffix("s")

    /** How well a product name matches the search words (0–1). */
    fun relevance(query: String, name: String): Double {
        val q = query.split(" ").filter { it.length > 1 }.map(::stem)
        if (q.isEmpty()) return 0.0
        val n = name.lowercase().split(Regex("[^a-z]+")).map(::stem).toSet()
        return q.count { it in n }.toDouble() / q.size
    }

    /** The amount a recipe line needs, after scaling, in grams / millilitres / count. */
    fun need(ing: Ingredient, factor: Double): Base? {
        val a = Qty.parse(ing.qty) ?: Qty.parse(ing.item)?.takeIf { Regex("^\\s*[\\d½¼¾⅓⅔]").containsMatchIn(ing.item) } ?: return null
        val b = Qty.toBase(a, ing.item) ?: return null
        return b.copy(amount = b.amount * factor)
    }

    /** Converts [need] into the product's measure (g ↔ ml by density, count ↔ g by typical weight). */
    fun convert(need: Base, to: Base.Kind, item: String): Double? = when {
        need.kind == to -> need.amount
        need.kind == Base.Kind.ML && to == Base.Kind.G -> need.amount * Qty.density(item)
        need.kind == Base.Kind.G && to == Base.Kind.ML -> need.amount / Qty.density(item)
        need.kind == Base.Kind.EA && to != Base.Kind.EA -> Qty.eachWeight(item)?.let { need.amount * it }?.let { if (to == Base.Kind.ML) it / Qty.density(item) else it }
        need.kind != Base.Kind.EA && to == Base.Kind.EA -> Qty.eachWeight(item)?.let { w -> (if (need.kind == Base.Kind.ML) need.amount * Qty.density(item) else need.amount) / w }
        else -> null
    }

    data class Price(val use: Double?, val buy: Double?, val packs: Int, val note: String)

    /** Cost of [need] of [item] when bought as [p]. */
    fun price(need: Base?, item: String, p: Product): Price {
        if (need == null) {
            return if (isPantry(item)) Price(0.0, p.price, 1, "Amount not given, so not counted")
            else Price(p.price, p.price, 1, "Amount not given, so counted as one pack")
        }
        if (p.byWeight) {
            val g = convert(need, Base.Kind.G, item) ?: return Price(p.price, p.price, 1, "Counted as 1 kg")
            val c = p.price * g / 1000.0
            return Price(c, c, 1, "")
        }
        val pack = p.packAmount
        val kind = p.packKind
        if (pack == null || kind == null || pack <= 0) return Price(p.price, p.price, 1, "Pack size unknown, so counted as one pack")
        val amt = convert(need, kind, item) ?: return Price(p.price, p.price, 1, "Couldn't compare amounts, so counted as one pack")
        val packs = max(1, ceil(amt / pack - 0.02).toInt())
        return Price(p.price * amt / pack, p.price * packs, packs, "")
    }

    /**
     * Picks the product that matches the ingredient and is cheapest to buy for this
     * amount (so it won't pick a 10 kg sack for one cup of rice).
     */
    fun pickBest(query: String, need: Base?, item: String, options: List<Product>): Product? {
        if (options.isEmpty()) return null
        val scored = options.map { it to relevance(query, it.name + " " + it.brand) }
        val top = scored.maxOf { it.second }
        if (top < 0.5) return null
        val good = scored.filter { it.second >= top - 0.01 }.map { it.first }
        return good.minWithOrNull(compareBy<Product>({ price(need, item, it).buy ?: Double.MAX_VALUE }, { price(need, item, it).use ?: Double.MAX_VALUE }))
    }

    fun line(ing: Ingredient, factor: Double, p: Product?, error: String = ""): LineCost {
        val need = need(ing, factor)
        val scaled = Qty.scale(ing.qty, factor)
        if (p == null) return LineCost(ing, scaled, null, null, null, 0, isPantry(ing.item), error = error)
        val pr = price(need, ing.item, p)
        return LineCost(ing, scaled, p, pr.use, pr.buy, pr.packs, isPantry(ing.item), pr.note, error)
    }

    fun money(v: Double?): String = if (v == null) "–" else "$%.2f".format(v)
}
