package com.reelrecipes.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.reelrecipes.app.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeScreen(
    r: Recipe,
    all: List<Recipe>,
    store: RecipeStore,
    prices: PriceBook,
    plan: PlanStore,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (String) -> Unit,
    onNewVariation: (Recipe) -> Unit,
    onCook: (List<Pair<String, Int>>) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var servings by rememberSaveable(r.id) { mutableIntStateOf(r.baseServings) }
    var shopAt by remember { mutableStateOf(prices.store) }
    var cost by remember { mutableStateOf<MealCost?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Ingredient?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val checked = remember(r.id) { mutableStateMapOf<Int, Boolean>() }
    val factor = servings.toDouble() / r.baseServings
    val rootId = r.parentId.ifBlank { r.id }
    val family = all.filter { it.id == rootId || it.parentId == rootId }.sortedBy { if (it.id == rootId) 0L else it.createdAt }

    LaunchedEffect(r, servings, shopAt, refreshTick) {
        delay(250)
        loading = true
        cost = runCatching { prices.mealCost(r, servings, shopAt, refresh = refreshTick > 0) }.getOrNull()
        loading = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { scope.launch { runCatching { PdfExport.share(ctx, store, r, servings, cost) }.onFailure { Toast.makeText(ctx, "Couldn't make the PDF: ${it.message}", Toast.LENGTH_LONG).show() } } }) { Icon(Icons.Default.Share, "Share as PDF") }
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp)) {
            r.photos.firstOrNull()?.let {
                AsyncImage(store.photo(it), null, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(14.dp))
            }
            Text(r.title.ifBlank { "Untitled" }, fontFamily = Display, fontSize = 30.sp, lineHeight = 34.sp)
            val meta = listOfNotNull(r.author.takeIf { it.isNotBlank() }, r.time.takeIf { it.isNotBlank() })
            if (meta.isNotEmpty()) Muted(meta.joinToString("   "), 14, Modifier.padding(top = 6.dp))

            // Versions: the original plus your variations, as tabs.
            Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                family.forEach { v ->
                    FilterChip(selected = v.id == r.id, onClick = { if (v.id != r.id) onOpen(v.id) },
                        label = { Text(if (v.id == rootId) "Original" else v.variantName.ifBlank { "My version" }) })
                }
                AssistChip(onClick = { naming = true }, label = { Text("+ New version") })
            }
            if (r.tags.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.tags.forEach { Pill(it) } }

            // Servings
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServingsStepper(servings, { servings = it })
                Muted(if (r.servingsStated) "Recipe is for ${r.baseServings}" else "Recipe doesn't say how many it serves, so 2 is assumed. Set it in Edit.", 12, Modifier.weight(1f))
            }

            CostCard(cost, loading, shopAt, prices.allStores, onStore = { shopAt = it }, onRefresh = { refreshTick++ })

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { planning = true }, modifier = Modifier.weight(1f)) { Text("Add to plan") }
                OutlinedButton(onClick = { onCook(listOf(r.id to servings)) }, modifier = Modifier.weight(1f)) { Text("Cook mode") }
            }

            if (prices.hubUrl.isNotBlank()) TextButton(onClick = {
                scope.launch {
                    val msg = runCatching { Grocery.sendRecipe(r) }.getOrElse { it.message ?: "Couldn't reach Grocery Hub" }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("Track in Grocery Hub (daily prices and deal alerts)") }

            if (r.url.isNotBlank()) {
                OutlinedCard(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) } }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Original post", fontWeight = FontWeight.SemiBold)
                            Text(r.url.removePrefix("https://www."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Watch ↗", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            SectionHead("Ingredients", "Tap a price to change the product")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            r.ingredients.forEachIndexed { i, ing ->
                val on = checked[i] == true
                val line = cost?.lines?.getOrNull(i)
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { checked[i] = !on }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { checked[i] = it })
                        Text(Qty.scale(ing.qty, factor), fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(84.dp))
                        Text(
                            if (ing.note.isBlank()) ing.item else "${ing.item}, ${ing.note}", Modifier.weight(1f),
                            textDecoration = if (on) TextDecoration.LineThrough else null,
                            color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    if (!MealCost.isFree(ing.item)) Row(
                        Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 4.dp).clip(RoundedCornerShape(6.dp)).clickable { picking = ing }.padding(vertical = 2.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val p = line?.product
                        Text(
                            when {
                                p != null -> p.displayName + (if (line.packs > 1) "  ×${line.packs}" else "")
                                line?.error?.isNotBlank() == true -> line.error
                                loading -> "Checking price…"
                                else -> "Tap to choose a product"
                            },
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (p?.isSpecial == true) { Pill("Special", strong = true); Spacer(Modifier.width(6.dp)) }
                        if (line?.pantry == true && p != null) { Pill("Pantry"); Spacer(Modifier.width(6.dp)) }
                        Text(Costing.money(line?.useCost), fontFamily = Mono, fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            SectionHead("Method", "${r.steps.size} steps")
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                r.steps.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("%02d".format(i + 1), fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondary).padding(horizontal = 6.dp, vertical = 2.dp))
                        Text(step, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (r.notes.isNotBlank()) {
                SectionHead("Notes", "")
                Text(r.notes, Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(14.dp))
            }
            if (r.photos.size > 1) {
                SectionHead("Reference shots", "${r.photos.size}")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(r.photos) { AsyncImage(store.photo(it), null, Modifier.height(200.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.FillHeight) }
                }
            }
        }
    }

    picking?.let { ing ->
        ProductPicker(ing, shopAt, prices, factor, onDone = { picking = null; scope.launch { cost = prices.mealCost(r, servings, shopAt) } })
    }

    if (planning) AddToPlanDialog(servings, onDismiss = { planning = false }) { date, n ->
        planning = false
        scope.launch { plan.add(date, r.id, n); Toast.makeText(ctx, "Added to ${date.format(DateTimeFormatter.ofPattern("EEE d MMM"))}", Toast.LENGTH_SHORT).show() }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New version") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("Makes a copy you can change freely. The original stays as it is.")
                    OutlinedTextField(name, { name = it }, label = { Text("Name it") }, placeholder = { Text("e.g. Less spicy, Air fryer") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    onNewVariation(r.copy(id = java.util.UUID.randomUUID().toString(), parentId = rootId, variantName = name.trim().ifBlank { "My version" }, createdAt = System.currentTimeMillis()))
                }) { Text("Create and edit") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(if (r.parentId.isNotBlank()) "Delete this version?" else "Delete this recipe?") },
        text = { Text(if (family.size > 1 && r.parentId.isBlank()) "Its versions are kept and the oldest becomes the original." else "It's also taken off your meal plan.") },
        confirmButton = {
            TextButton({
                confirmDelete = false
                scope.launch {
                    store.delete(r.id); plan.removeRecipe(r.id)
                    Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep it") } },
    )
}

@Composable
private fun CostCard(cost: MealCost?, loading: Boolean, at: StoreRef, stores: List<StoreRef>, onStore: (StoreRef) -> Unit, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Muted("Cost to make", 12)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (cost == null && loading) "…" else Costing.money(cost?.total), fontFamily = Display, fontSize = 30.sp)
                    if (cost != null) Muted("   ${Costing.money(cost.perServe)} a serve", 14, Modifier.padding(bottom = 5.dp))
                }
            }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh prices") }
        }
        if (cost != null) {
            Muted("Buying every pack new: ${Costing.money(cost.basket)} (pantry staples left out). The cost to make counts only what the recipe uses.", 12)
            val notes = listOfNotNull(
                cost.specials.takeIf { it > 0 }?.let { "$it on special" },
                cost.unpriced.takeIf { it > 0 }?.let { "$it not priced yet. Tap them below" },
            )
            if (notes.isNotEmpty()) Muted(notes.joinToString(" · "), 12)
        }
        if (stores.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stores.forEach { s -> FilterChip(selected = s == at, onClick = { onStore(s) }, label = { Text(s.label) }) }
        } else Muted("Prices from ${at.label}. Add stores to compare in Settings.", 12)
    }
}

@Composable
private fun AddToPlanDialog(servings: Int, onDismiss: () -> Unit, onAdd: (LocalDate, Int) -> Unit) {
    val today = LocalDate.now()
    var day by remember { mutableStateOf(today) }
    var n by remember { mutableIntStateOf(servings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to meal plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((0L until 14L).map { today.plusDays(it) }) { d ->
                        FilterChip(selected = d == day, onClick = { day = d }, label = {
                            Text(when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(DateTimeFormatter.ofPattern("EEE d")) })
                        })
                    }
                }
                ServingsStepper(n, { n = it })
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(day, n) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lets you pick which product an ingredient is priced as, or type your own price. */
@Composable
internal fun ProductPicker(ing: Ingredient, at: StoreRef, prices: PriceBook, factor: Double, onDone: () -> Unit) {
    var query by remember { mutableStateOf(Costing.searchQuery(ing.item)) }
    var results by remember { mutableStateOf<List<Product>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var mPrice by remember { mutableStateOf("") }
    var mSize by remember { mutableStateOf("") }
    val need = Costing.need(ing, factor)
    LaunchedEffect(query, at) {
        delay(400); loading = true; error = null
        runCatching { prices.options(at, query) }.onSuccess { results = it }.onFailure { error = it.message; results = emptyList() }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Price “${ing.item.take(40)}”") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("At ${at.label}. Choose the product you'd buy.", 12)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search") })
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (manual) {
                    OutlinedTextField(mPrice, { mPrice = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Price ($)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(mSize, { mSize = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Pack size") }, placeholder = { Text("e.g. 500g, 1L, 12pk") })
                } else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(results) { p ->
                        val pr = Costing.price(need, ing.item, p)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { prices.choose(at, ing.item, p); onDone() }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (p.image.isNotBlank()) AsyncImage(p.image, null, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
                            Column(Modifier.weight(1f)) {
                                Text(p.displayName, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Muted(listOfNotNull(Costing.money(p.price) + if (p.byWeight) "/kg" else "", p.unitPriceText.takeIf { it.isNotBlank() }, p.wasPrice?.let { "was ${Costing.money(it)}" }).joinToString(" · "), 12)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Costing.money(pr.use), fontFamily = Mono, fontSize = 13.sp)
                                if (p.isSpecial) Pill("Special", strong = true)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (manual) TextButton(onClick = {
                mPrice.replace("$", "").trim().toDoubleOrNull()?.let { prices.manual(at, ing.item, it, mSize) }
                onDone()
            }) { Text("Save price") }
            else TextButton(onClick = { manual = true }) { Text("Enter my own price") }
        },
        dismissButton = {
            Row {
                if (prices.matchFor(at, ing.item) != null) TextButton(onClick = { prices.forget(at, ing.item); onDone() }) { Text("Clear") }
                TextButton(onClick = onDone) { Text("Close") }
            }
        },
    )
}
