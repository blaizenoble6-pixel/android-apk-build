package com.reelrecipes.app.ui

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelrecipes.app.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMM")
private val SHORT = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun WeekBar(start: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(start.minusWeeks(1)) }) { Text("‹ Prev") }
        Text(
            when (start) { PlanStore.weekStart() -> "This week"; PlanStore.weekStart().plusWeeks(1) -> "Next week"; else -> "Week of ${start.format(SHORT)}" },
            Modifier.weight(1f), fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        TextButton(onClick = { onChange(start.plusWeeks(1)) }) { Text("Next ›") }
    }
}

// ---------------------------------------------------------------- Weekly planner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanScreen(recipes: List<Recipe>, plan: PlanStore, prices: PriceBook, week: LocalDate, onWeek: (LocalDate) -> Unit, onOpen: (String) -> Unit, onCook: (List<Pair<String, Int>>) -> Unit, onShop: () -> Unit) {
    val scope = rememberCoroutineScope()
    val all by plan.entries.collectAsState()
    val entries = remember(all, week) { plan.week(week) }
    val costs = remember { mutableStateMapOf<String, Double>() }
    var addingTo by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(entries, prices.store) {
        entries.forEach { e ->
            if (costs.containsKey(e.id + e.servings)) return@forEach
            recipes.firstOrNull { it.id == e.recipeId }?.let { r -> runCatching { prices.mealCost(r, e.servings) }.getOrNull()?.let { costs[e.id + e.servings] = it.total } }
        }
    }
    val weekTotal = entries.sumOf { costs[it.id + it.servings] ?: 0.0 }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Meal plan", fontFamily = Display, fontSize = 26.sp) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { WeekBar(week, onWeek) }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Muted("${entries.size} meal${if (entries.size == 1) "" else "s"} · ${entries.sumOf { it.servings }} serves", 12)
                        Text(Costing.money(weekTotal), fontFamily = Display, fontSize = 26.sp)
                        Muted("Cost to make at ${prices.store.label}", 12)
                    }
                    Button(onClick = onShop, enabled = entries.isNotEmpty()) { Text("Shopping list") }
                }
            }
            items((0L until 7L).map { week.plusDays(it) }) { day ->
                val todays = entries.filter { it.date == day }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(day.format(DAY) + if (day == LocalDate.now()) "  · today" else "", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (todays.isNotEmpty()) TextButton(onClick = { onCook(todays.map { it.recipeId to it.servings }) }) { Text(if (todays.size > 1) "Cook all" else "Cook") }
                    }
                    todays.forEach { e ->
                        val r = recipes.firstOrNull { it.id == e.recipeId }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f).clickable(enabled = r != null) { r?.let { onOpen(it.id) } }) {
                                Text(r?.let { it.title + if (it.variantName.isNotBlank()) " (${it.variantName})" else "" } ?: "Deleted recipe", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Muted(Costing.money(costs[e.id + e.servings]) + " · " + (costs[e.id + e.servings]?.let { Costing.money(it / e.servings) + " a serve" } ?: "pricing…"), 12)
                            }
                            ServingsStepper(e.servings, { n -> scope.launch { plan.update(e.copy(servings = n)) } }, "")
                            IconButton(onClick = { scope.launch { plan.remove(e.id) } }) { Icon(Icons.Default.Close, "Remove") }
                        }
                    }
                    TextButton(onClick = { addingTo = day }) { Text("+ Add meal") }
                }
            }
        }
    }

    addingTo?.let { day ->
        RecipeChooser(recipes, prices, onDismiss = { addingTo = null }) { r ->
            addingTo = null
            scope.launch { plan.add(day, r.id, r.baseServings) }
        }
    }
}

@Composable
private fun RecipeChooser(recipes: List<Recipe>, prices: PriceBook, onDismiss: () -> Unit, onPick: (Recipe) -> Unit) {
    var q by remember { mutableStateOf("") }
    val per by prices.perServe.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a recipe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search") })
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(recipes.filter { it.matches(q) }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.title + if (r.variantName.isNotBlank()) " (${r.variantName})" else "", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            per[r.id]?.let { Muted(Costing.money(it) + "/serve", 12) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Shopping list

private data class Priced(val product: Product?, val cost: Double?, val packs: Int, val error: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShopScreen(recipes: List<Recipe>, plan: PlanStore, prices: PriceBook, week: LocalDate, onWeek: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by plan.entries.collectAsState()
    val ticked by plan.ticked.collectAsState()
    val entries = remember(all, week) { plan.week(week) }
    val items = remember(entries, recipes) {
        Shopping.combine(entries.mapNotNull { e -> recipes.firstOrNull { it.id == e.recipeId }?.let { it to e.servings } })
    }
    val stores = prices.allStores
    var at by remember { mutableStateOf(prices.store) }
    // store label → item key → price
    val priced = remember { mutableStateMapOf<String, Map<String, Priced>>() }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<ShopItem?>(null) }

    LaunchedEffect(items, tick) {
        loading = true
        for (s in stores) {
            val m = mutableMapOf<String, Priced>()
            for (item in items) {
                val (p, err) = prices.productFor(s, item.ingredient, 1.0, refresh = false)
                val pr = p?.let { prod -> Costing.price(item.need, item.name, prod) }
                m[item.key] = Priced(p, pr?.buy, pr?.packs ?: 0, err)
                priced[s.label] = m.toMap()
            }
        }
        loading = false
    }

    fun tkey(i: ShopItem) = "$week|${i.key}"
    fun total(s: StoreRef, onlyLeft: Boolean) = items.filter { !it.pantry && (!onlyLeft || tkey(it) !in ticked) }.sumOf { priced[s.label]?.get(it.key)?.cost ?: 0.0 }
    val cheapest = stores.filter { priced[it.label]?.size == items.size }.minByOrNull { total(it, false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Shopping list", fontFamily = Display, fontSize = 26.sp) },
                actions = {
                    IconButton(onClick = {
                        val text = buildString {
                            appendLine("Shopping list – week of ${week.format(SHORT)}")
                            items.filter { tkey(it) !in ticked }.forEach { i ->
                                val amt = Shopping.baseText(i.need).ifBlank { i.amounts.joinToString(", ") }
                                appendLine("☐ ${i.name}${if (amt.isNotBlank()) " – $amt" else ""}${if (i.pantry) " (check pantry)" else ""}")
                            }
                        }
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share list"))
                    }) { Icon(Icons.Default.Share, "Share list") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { WeekBar(week, onWeek) }
            if (items.isEmpty()) item { Muted("Nothing planned this week. Add meals on the Plan tab and their ingredients show up here, added together.", 14, Modifier.padding(vertical = 24.dp)) }
            else item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Muted("Left to buy at ${at.label}", 12)
                            Text(Costing.money(total(at, true)), fontFamily = Display, fontSize = 28.sp)
                            Muted("Whole list ${Costing.money(total(at, false))}, pantry staples not included", 12)
                        }
                        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    if (stores.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            stores.forEach { s ->
                                FilterChip(selected = s == at, onClick = { at = s }, label = {
                                    Text(s.label + "  " + (if (priced[s.label]?.size == items.size) Costing.money(total(s, false)) else "…") + if (s == cheapest) " ✓" else "")
                                })
                            }
                        }
                        cheapest?.let { c -> Muted("Cheapest for this list: ${c.label}", 12) }
                    } else Muted("Add stores to compare in Settings.", 12)
                }
            }
            val groups = items.groupBy { it.pantry }
            listOf(false, true).forEach { pantry ->
                val list = groups[pantry].orEmpty()
                if (list.isEmpty()) return@forEach
                item { SectionHead(if (pantry) "Check the pantry" else "To buy", if (pantry) "Usually already at home" else "${list.count { tkey(it) !in ticked }} left") }
                items(list, key = { it.key + pantry }) { i ->
                    val done = tkey(i) in ticked
                    val p = priced[at.label]?.get(i.key)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { scope.launch { plan.toggle(tkey(i)) } }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(done, { scope.launch { plan.toggle(tkey(i)) } })
                        Column(Modifier.weight(1f)) {
                            Text(i.name.replaceFirstChar { it.uppercase() }, textDecoration = if (done) TextDecoration.LineThrough else null,
                                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground)
                            Muted(Shopping.baseText(i.need).ifBlank { i.amounts.joinToString(", ") }.let { if (i.amounts.size > 1 && i.need != null) "$it total" else it }, 12)
                            Text(
                                p?.product?.let { it.displayName + if (p.packs > 1) "  ×${p.packs}" else "" } ?: p?.error?.ifBlank { null } ?: if (loading) "Checking price…" else "Tap the price to choose a product",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { picking = i }.padding(6.dp)) {
                            Text(Costing.money(p?.cost), fontFamily = Mono, fontSize = 13.sp)
                            if (p?.product?.isSpecial == true) Pill("Special", strong = true)
                        }
                    }
                }
            }
        }
    }

    picking?.let { i -> ProductPicker(i.ingredient, at, prices, 1.0, onDone = { picking = null; priced.clear(); tick++ }) }
}

// ---------------------------------------------------------------- Cook mode

private val DISH_COLORS = listOf(Color(0xFF2E6A4E), Color(0xFFB5651D), Color(0xFF3A5BA0), Color(0xFF8E3B6B), Color(0xFF6B7A1E))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookScreen(dishes: List<Pair<Recipe, Int>>, onBack: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val steps = remember(dishes) { CookPlan.plan(dishes.map { (r, _) -> r.title to r.steps }) }
    val length = steps.maxOfOrNull { it.start + it.minutes } ?: 0
    val now = LocalTime.now().withSecond(0).withNano(0)
    val readyOptions = remember { listOf(0L, 15L, 30L, 45L, 60L, 90L).map { now.plusMinutes(length + it) } }
    var readyAt by remember { mutableStateOf(readyOptions.first()) }
    val startAt = readyAt.minusMinutes(length.toLong())
    val done = remember { mutableStateMapOf<Int, Boolean>() }
    var timerFor by remember { mutableStateOf<Int?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    val fmt = DateTimeFormatter.ofPattern("h:mm a")

    LaunchedEffect(timerFor) {
        val i = timerFor ?: return@LaunchedEffect
        secondsLeft = steps[i].minutes * 60
        while (secondsLeft > 0) { delay(1000); secondsLeft-- }
        runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500) }
        timerFor = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("Cook mode") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        dishes.forEachIndexed { i, (r, n) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(DISH_COLORS[i % DISH_COLORS.size]))
                                Text("  ${r.title.take(28)} · $n", fontSize = 13.sp)
                            }
                        }
                    }
                    Muted("About ${CookPlan.clock(length)} in total. Times are estimates from the steps; hands-off steps (baking, simmering) let other jobs fit around them.", 12)
                    Text("Ready at", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        readyOptions.forEach { t -> FilterChip(selected = t == readyAt, onClick = { readyAt = t }, label = { Text(t.format(fmt)) }) }
                    }
                    Muted("Start at ${startAt.format(fmt)}", 13)
                    if (dishes.any { it.second != it.first.baseServings }) Muted("Amounts in the steps are as written. Check the scaled ingredients on each recipe.", 12)
                }
            }
            items(steps.indices.toList()) { i ->
                val s = steps[i]
                val isDone = done[i] == true
                val color = DISH_COLORS[s.dish % DISH_COLORS.size]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .clickable { done[i] = !isDone }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(70.dp)) {
                        Text(startAt.plusMinutes(s.start.toLong()).format(fmt), fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Muted("${s.minutes} min", 12)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                            Muted("  ${s.dishTitle.take(30)}${if (!s.handsOn) " · hands-off" else ""}", 12)
                        }
                        Text(s.text, textDecoration = if (isDone) TextDecoration.LineThrough else null,
                            color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground)
                        if (s.minutes >= 3 && !isDone) {
                            if (timerFor == i) Text("⏱ %d:%02d left".format(secondsLeft / 60, secondsLeft % 60), fontFamily = Mono, color = MaterialTheme.colorScheme.primary)
                            else TextButton(onClick = { timerFor = i }, contentPadding = PaddingValues(0.dp)) { Text("Start ${s.minutes} min timer") }
                        }
                    }
                }
            }
        }
    }
}
