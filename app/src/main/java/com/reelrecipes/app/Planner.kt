package com.reelrecipes.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** One meal on the plan: a recipe on a day, for a number of people. */
data class PlanEntry(val id: String = UUID.randomUUID().toString(), val date: LocalDate, val recipeId: String, val servings: Int)

/** The weekly meal plan and shopping-list ticks, saved on the phone. */
class PlanStore(context: Context) {
    private val file = File(context.filesDir, "plan.json")
    private val _entries = MutableStateFlow<List<PlanEntry>>(emptyList())
    val entries: StateFlow<List<PlanEntry>> = _entries
    private val _ticked = MutableStateFlow<Set<String>>(emptySet())
    /** Shopping list items ticked off or marked "have it", per week ("2026-11-23|chicken thigh"). */
    val ticked: StateFlow<Set<String>> = _ticked

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val a = o.optJSONArray("entries") ?: JSONArray()
                _entries.value = (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { e -> runCatching { PlanEntry(e.getString("id"), LocalDate.parse(e.getString("date")), e.getString("recipeId"), e.optInt("servings", 2)) }.getOrNull() }
                }
                _ticked.value = o.optJSONArray("ticked").strings().toSet()
            }
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val o = JSONObject()
        o.put("entries", JSONArray(_entries.value.map { JSONObject().put("id", it.id).put("date", it.date.toString()).put("recipeId", it.recipeId).put("servings", it.servings) }))
        o.put("ticked", JSONArray(_ticked.value.toList()))
        val tmp = File(file.parentFile, "plan.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(file)
    }

    suspend fun add(date: LocalDate, recipeId: String, servings: Int) { _entries.value = _entries.value + PlanEntry(date = date, recipeId = recipeId, servings = servings); persist() }
    suspend fun update(e: PlanEntry) { _entries.value = _entries.value.map { if (it.id == e.id) e else it }; persist() }
    suspend fun remove(id: String) { _entries.value = _entries.value.filterNot { it.id == id }; persist() }
    suspend fun removeRecipe(recipeId: String) { _entries.value = _entries.value.filterNot { it.recipeId == recipeId }; persist() }
    suspend fun toggle(key: String) { _ticked.value = if (key in _ticked.value) _ticked.value - key else _ticked.value + key; persist() }

    fun week(start: LocalDate) = _entries.value.filter { !it.date.isBefore(start) && it.date.isBefore(start.plusDays(7)) }.sortedBy { it.date }

    companion object {
        fun weekStart(d: LocalDate = LocalDate.now()): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}

/** One line of the combined shopping list. */
data class ShopItem(
    val key: String,
    val name: String,
    /** The amounts each meal asks for, e.g. "300 g (Butter chicken), 2 (Tacos)". */
    val amounts: List<String>,
    /** Total need in one measure when the amounts could be added up. */
    val need: Base?,
    val ingredient: Ingredient,
    val pantry: Boolean,
)

/** Adds up the ingredients of several meals. Pure Kotlin, unit tested. */
object Shopping {
    fun combine(meals: List<Pair<Recipe, Int>>): List<ShopItem> {
        data class Acc(val name: String, val amounts: MutableList<String>, var need: Base?, var addable: Boolean, val ing: Ingredient)
        val map = linkedMapOf<String, Acc>()
        for ((r, servings) in meals) {
            val f = servings.toDouble() / r.baseServings
            for (ing in r.ingredients) {
                if (MealCost.isFree(ing.item)) continue
                val k = Costing.searchQuery(ing.item)
                val n = Costing.need(ing, f)
                val label = Qty.scale(ing.qty, f).ifBlank { "some" } + " (" + r.title.take(24) + ")"
                val a = map.getOrPut(k) { Acc(ing.item.substringBefore(",").trim(), mutableListOf(), null, true, ing) }
                a.amounts += label
                if (n == null) { a.addable = false; continue }
                val cur = a.need
                a.need = when {
                    cur == null && a.amounts.size == 1 -> n
                    cur == null -> null.also { a.addable = false }
                    else -> Costing.convert(n, cur.kind, ing.item)?.let { cur.copy(amount = cur.amount + it) } ?: run { a.addable = false; cur }
                }
            }
        }
        return map.map { (k, a) -> ShopItem(k, a.name, a.amounts, if (a.addable) a.need else null, a.ing.copy(qty = ""), Costing.isPantry(a.name)) }
            .sortedWith(compareBy({ it.pantry }, { it.name.lowercase() }))
    }

    fun baseText(b: Base?): String = when (b?.kind) {
        null -> ""
        Base.Kind.G -> if (b.amount >= 1000) Qty.format(b.amount / 1000, "kg") + " kg" else Qty.format(b.amount, "g") + " g"
        Base.Kind.ML -> if (b.amount >= 1000) Qty.format(b.amount / 1000, "l") + " L" else Qty.format(b.amount, "ml") + " ml"
        Base.Kind.EA -> Qty.format(b.amount, "")
    }
}

/** A step placed on the cooking timeline. Times are minutes from starting to cook. */
data class TimedStep(val dish: Int, val dishTitle: String, val text: String, val start: Int, val minutes: Int, val handsOn: Boolean)

/**
 * Merges the methods of several dishes into one timeline that finishes them together.
 * Each step gets an estimated duration (from "20 minutes" in the text, or a default);
 * longer dishes start first, and a dish is moved earlier when two hands-on steps clash.
 */
object CookPlan {
    private val PASSIVE = Regex("\\b(bake|roast|simmer|rest|marinate|chill|refrigerate|freeze|boil|slow cook|pressure cook|rise|prove|proof|soak|cool|set aside|leave|stand|braise|steam|oven)\\b", RegexOption.IGNORE_CASE)
    private val TIME = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:(?:-|–|to)\\s*(\\d+(?:\\.\\d+)?)\\s*)?(hours?|hrs?|h\\b|minutes?|mins?|m\\b)", RegexOption.IGNORE_CASE)
    private val OVERNIGHT = Regex("overnight", RegexOption.IGNORE_CASE)

    fun estimate(step: String): Pair<Int, Boolean> {
        val passive = PASSIVE.containsMatchIn(step)
        if (OVERNIGHT.containsMatchIn(step)) return 8 * 60 to true
        val m = TIME.findAll(step).map { t ->
            val v = (t.groupValues[2].toDoubleOrNull() ?: t.groupValues[1].toDouble())
            if (t.groupValues[3].lowercase().startsWith("h")) v * 60 else v
        }.maxOrNull()
        val mins = m?.toInt()?.coerceIn(1, 24 * 60) ?: when {
            Regex("\\b(chop|dice|slice|mince|grate|peel|prep|cut)\\b", RegexOption.IGNORE_CASE).containsMatchIn(step) -> 6
            passive -> 10
            else -> 4
        }
        // A timed step counts as hands-off when it's cooking on its own (bake 30 min); short timed tasks stay hands-on.
        return mins to (passive && mins >= 5)
    }

    fun plan(dishes: List<Pair<String, List<String>>>): List<TimedStep> {
        val est = dishes.map { (_, steps) -> steps.map { estimate(it) } }
        val totals = est.map { s -> s.sumOf { it.first } }
        val end = totals.maxOrNull() ?: 0
        val order = dishes.indices.sortedByDescending { totals[it] }
        val placed = mutableListOf<TimedStep>()
        for (d in order) {
            var start = end - totals[d]
            var tries = 0
            while (true) {
                val mine = layout(d, dishes[d].first, dishes[d].second, est[d], start)
                val clash = mine.filter { it.handsOn }.mapNotNull { a ->
                    placed.filter { it.handsOn && it.start < a.start + a.minutes && a.start < it.start + it.minutes }
                        .maxOfOrNull { b -> (a.start + a.minutes) - b.start }
                }.maxOrNull()
                if (clash == null || clash <= 0 || tries++ > 40) { placed += mine; break }
                start -= clash
            }
        }
        // Shift everything so the first step starts at 0.
        val min = placed.minOfOrNull { it.start } ?: 0
        return placed.map { it.copy(start = it.start - min) }.sortedWith(compareBy({ it.start }, { it.dish }))
    }

    private fun layout(d: Int, title: String, steps: List<String>, est: List<Pair<Int, Boolean>>, start: Int): List<TimedStep> {
        var t = start
        return steps.mapIndexed { i, s -> TimedStep(d, title, s, t, est[i].first, !est[i].second).also { t += est[i].first } }
    }

    fun clock(min: Int): String = "%d:%02d".format(min / 60, min % 60)
}
