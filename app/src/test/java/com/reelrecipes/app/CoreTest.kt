package com.reelrecipes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QtyTest {
    @Test fun parsesCommonAmounts() {
        assertEquals(200.0, Qty.parse("200 g")!!.value, 1e-9)
        assertEquals("g", Qty.parse("200g")!!.unit)
        assertEquals(1.5, Qty.parse("1 1/2 cups")!!.value, 1e-9)
        assertEquals(1.5, Qty.parse("1½ cups")!!.value, 1e-9)
        assertEquals(0.5, Qty.parse("½ tsp")!!.value, 1e-9)
        assertEquals("tsp", Qty.parse("½ tsp")!!.unit)
        assertEquals(3.0, Qty.parse("2-3")!!.upper!!, 1e-9)
        assertTrue(Qty.parse("~1 tbsp")!!.approx)
        assertNull(Qty.parse("to taste"))
        assertEquals("pinch", Qty.parse("a pinch")!!.unit)
    }

    @Test fun scalesForMorePeople() {
        assertEquals("400 g", Qty.scale("200 g", 2.0))
        assertEquals("400g", Qty.scale("200g", 2.0))
        assertEquals("1½ cups", Qty.scale("¾ cup", 2.0))
        assertEquals("1 cup", Qty.scale("2 cups", 0.5))
        assertEquals("6", Qty.scale("3", 2.0))
        assertEquals("1.2 kg", Qty.scale("300 g", 4.0))
        assertEquals("to taste", Qty.scale("to taste", 3.0))
        assertEquals("2 cans", Qty.scale("1 can", 2.0))
        assertEquals("200 g", Qty.scale("200 g", 1.0))
        assertEquals("4–6", Qty.scale("2-3", 2.0))
        assertEquals("~2 tbsp", Qty.scale("~1 tbsp", 2.0))
    }
}

class CostingTest {
    private fun product(price: Double, size: String, byWeight: Boolean = false, name: String = "Thing") =
        Product(Chain.WOOLWORTHS, "1", name, "", price, packAmount = if (byWeight) 1000.0 else Product.parseSize(size)?.first,
            packKind = if (byWeight) Base.Kind.G else Product.parseSize(size)?.second, byWeight = byWeight)

    @Test fun readsPackSizes() {
        assertEquals(1500.0, Product.parseSize("1.5kg")!!.first, 1e-9)
        assertEquals(Base.Kind.ML, Product.parseSize("2L")!!.second)
        assertEquals(12.0, Product.parseSize("12pk")!!.first, 1e-9)
        assertEquals(1500.0, Product.parseSize("6 x 250ml")!!.first, 1e-9)
    }

    @Test fun costsAShareOfThePack() {
        val p = Costing.price(Base(250.0, Base.Kind.G), "pasta", product(3.00, "500g"))
        assertEquals(1.50, p.use!!, 1e-9)
        assertEquals(3.00, p.buy!!, 1e-9)
        assertEquals(1, p.packs)
    }

    @Test fun buysEnoughPacks() {
        val p = Costing.price(Base(1200.0, Base.Kind.G), "chicken thighs", product(10.0, "500g"))
        assertEquals(3, p.packs)
        assertEquals(30.0, p.buy!!, 1e-9)
        assertEquals(24.0, p.use!!, 1e-9)
    }

    @Test fun perKgItems() {
        val p = Costing.price(Base(300.0, Base.Kind.G), "beef mince", product(20.0, "", byWeight = true))
        assertEquals(6.0, p.use!!, 1e-9)
    }

    @Test fun countsToWeight() {
        // 2 onions ≈ 300 g of a $3/kg bag
        val need = Costing.need(Ingredient("2", "onions"), 1.0)!!
        val p = Costing.price(need, "onions", product(3.0, "1kg"))
        assertEquals(0.90, p.use!!, 1e-9)
    }

    @Test fun cupsToGrams() {
        val need = Costing.need(Ingredient("2 cups", "plain flour"), 1.0)!!
        val p = Costing.price(need, "plain flour", product(3.0, "1.5kg"))
        assertEquals(3.0 * 265 / 1500, p.use!!, 1e-6)
    }

    @Test fun picksCheapestSensibleOption() {
        val small = Product(Chain.WOOLWORTHS, "a", "Basmati Rice", "Pams", 3.0, packAmount = 1000.0, packKind = Base.Kind.G)
        val sack = Product(Chain.WOOLWORTHS, "b", "Basmati Rice", "Tilda", 30.0, packAmount = 10000.0, packKind = Base.Kind.G)
        val wrong = Product(Chain.WOOLWORTHS, "c", "Rice Crackers", "Huntley", 2.0, packAmount = 100.0, packKind = Base.Kind.G)
        val best = Costing.pickBest("basmati rice", Base(300.0, Base.Kind.G), "basmati rice", listOf(sack, wrong, small))
        assertEquals("a", best!!.id)
    }

    @Test fun cleansSearchWords() {
        assertEquals("chicken thighs", Costing.searchQuery("boneless skinless chicken thighs, diced"))
        assertEquals("garlic cloves", Costing.searchQuery("garlic cloves (crushed)"))
        assertTrue(Costing.isPantry("salt and pepper"))
        assertTrue(!Costing.isPantry("chicken thighs"))
    }

    @Test fun scalesNeedWithServings() {
        assertEquals(400.0, Costing.need(Ingredient("200 g", "mince"), 2.0)!!.amount, 1e-9)
    }
}

class PlanTest {
    private fun recipe(title: String, serves: String, vararg ings: Ingredient, steps: List<String> = emptyList()) =
        Recipe(title = title, servings = serves, ingredients = ings.toList(), steps = steps)

    @Test fun combinesTheSameIngredient() {
        val a = recipe("Tacos", "2", Ingredient("300 g", "beef mince"), Ingredient("1", "onion"))
        val b = recipe("Bolognese", "4", Ingredient("500g", "beef mince"), Ingredient("to taste", "salt"))
        val list = Shopping.combine(listOf(a to 4, b to 4))
        val mince = list.first { it.key == "beef mince" }
        assertEquals(1100.0, mince.need!!.amount, 1e-9)
        assertEquals(2, mince.amounts.size)
        assertTrue(list.last().pantry) // salt goes to the pantry section at the end
    }

    @Test fun estimatesStepTimes() {
        assertEquals(25, CookPlan.estimate("Bake for 20-25 minutes until golden").first)
        assertTrue(CookPlan.estimate("Bake for 20-25 minutes until golden").second)
        assertEquals(90, CookPlan.estimate("Simmer for 1.5 hours").first)
        assertEquals(6, CookPlan.estimate("Dice the onion").first)
    }

    @Test fun dishesFinishTogether() {
        val plan = CookPlan.plan(listOf(
            "Roast" to listOf("Season the chicken", "Roast for 60 minutes", "Rest for 10 minutes"),
            "Salad" to listOf("Chop the lettuce", "Dress and toss"),
        ))
        val endRoast = plan.filter { it.dish == 0 }.maxOf { it.start + it.minutes }
        val endSalad = plan.filter { it.dish == 1 }.maxOf { it.start + it.minutes }
        assertEquals(0, plan.first().start)
        assertTrue(endSalad <= endRoast)
        // No two hands-on steps overlap.
        val hands = plan.filter { it.handsOn }
        for (x in hands) for (y in hands) if (x !== y) assertTrue(x.start + x.minutes <= y.start || y.start + y.minutes <= x.start)
    }

    @Test fun recipeMinutesAndServings() {
        assertEquals(70, Recipe(time = "1 hr 10 min").minutes)
        assertEquals(35, Recipe(time = "35 min").minutes)
        assertEquals(4, Recipe(servings = "Serves 4").baseServings)
        assertEquals(2, Recipe(servings = "").baseServings)
        assertNotNull(Recipe(time = "45").minutes)
    }
}
