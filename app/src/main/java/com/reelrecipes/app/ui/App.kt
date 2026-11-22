package com.reelrecipes.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.reelrecipes.app.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A photo on a recipe: already saved on the phone, or picked and not yet saved. */
sealed interface Photo {
    data class Saved(val name: String) : Photo
    class Pending(val jpeg: ByteArray) : Photo
}

private enum class Tab(val label: String) { RECIPES("Recipes"), PLAN("Plan"), SHOP("Shopping") }

private sealed interface Screen {
    data object Home : Screen
    data class View(val id: String) : Screen
    data class Import(val shared: Shared?) : Screen
    data class Edit(val recipe: Recipe, val photos: List<Photo>, val note: String, val isNew: Boolean) : Screen
    data object Settings : Screen
    data class SignIn(val back: Screen) : Screen
    data class Cook(val dishes: List<Pair<String, Int>>, val back: Screen) : Screen
}

@Composable
fun App(store: RecipeStore, prices: PriceBook, plan: PlanStore, incoming: Shared?, onIncomingHandled: () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var tab by rememberSaveable { mutableStateOf(Tab.RECIPES) }
    var week by remember { mutableStateOf(PlanStore.weekStart()) }
    val recipes by store.recipes.collectAsState()

    LaunchedEffect(incoming?.stamp) {
        if (incoming != null) { screen = Screen.Import(incoming); onIncomingHandled() }
    }
    BackHandler(enabled = screen != Screen.Home || tab != Tab.RECIPES) {
        screen = when (val s = screen) {
            Screen.Home -> { tab = Tab.RECIPES; Screen.Home }
            is Screen.Edit -> if (s.isNew) (if (s.recipe.parentId.isNotBlank()) Screen.View(s.recipe.parentId) else Screen.Home) else Screen.View(s.recipe.id)
            is Screen.SignIn -> s.back
            is Screen.Cook -> s.back
            else -> Screen.Home
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            Screen.Home -> Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, icon = {
                                Icon(when (t) { Tab.RECIPES -> Icons.Default.Home; Tab.PLAN -> Icons.Default.DateRange; Tab.SHOP -> Icons.Default.ShoppingCart }, null)
                            })
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
                    when (tab) {
                        Tab.RECIPES -> ShelfScreen(recipes, store, prices,
                            onOpen = { screen = Screen.View(it) },
                            onAdd = { screen = Screen.Import(null) },
                            onSettings = { screen = Screen.Settings })
                        Tab.PLAN -> PlanScreen(recipes, plan, prices, week, { week = it },
                            onOpen = { screen = Screen.View(it) },
                            onCook = { screen = Screen.Cook(it, Screen.Home) },
                            onShop = { tab = Tab.SHOP })
                        Tab.SHOP -> ShopScreen(recipes, plan, prices, week) { week = it }
                    }
                }
            }
            is Screen.View -> {
                val r = recipes.firstOrNull { it.id == s.id }
                if (r == null) LaunchedEffect(Unit) { screen = Screen.Home }
                else RecipeScreen(r, recipes, store, prices, plan,
                    onBack = { screen = Screen.Home },
                    onEdit = { screen = Screen.Edit(r, r.photos.map { Photo.Saved(it) }, "", false) },
                    onOpen = { screen = Screen.View(it) },
                    onNewVariation = { v -> screen = Screen.Edit(v, v.photos.map { Photo.Saved(it) }, "Change whatever you like. The original stays as it was.", true) },
                    onCook = { screen = Screen.Cook(it, Screen.View(r.id)) })
            }
            is Screen.Import -> ImportScreen(s.shared, store,
                onCancel = { screen = Screen.Home },
                onDraft = { recipe, photos, note -> screen = Screen.Edit(recipe, photos, note, true) },
                onSignIn = { screen = Screen.SignIn(Screen.Import(s.shared)) })
            is Screen.Edit -> EditScreen(s, store,
                onBack = { screen = if (s.isNew) (if (s.recipe.parentId.isNotBlank()) Screen.View(s.recipe.parentId) else Screen.Home) else Screen.View(s.recipe.id) },
                onSaved = { screen = Screen.View(it) })
            Screen.Settings -> SettingsScreen(store, prices, onBack = { screen = Screen.Home }, onSignIn = { screen = Screen.SignIn(Screen.Settings) })
            is Screen.SignIn -> InstagramSignInScreen(onDone = { screen = s.back })
            is Screen.Cook -> {
                val dishes = s.dishes.mapNotNull { (id, n) -> recipes.firstOrNull { it.id == id }?.let { it to n } }
                CookScreen(dishes, onBack = { screen = s.back })
            }
        }
    }
}

// ---------------------------------------------------------------- Shelf

private enum class Sort(val label: String) { NEWEST("Newest"), CHEAPEST("Cheapest a serve"), QUICKEST("Quickest"), AZ("A–Z") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfScreen(recipes: List<Recipe>, store: RecipeStore, prices: PriceBook, onOpen: (String) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var maxMin by rememberSaveable { mutableStateOf<Int?>(null) }
    var maxCost by rememberSaveable { mutableStateOf<Double?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.NEWEST) }
    var sortOpen by remember { mutableStateOf(false) }
    val per by prices.perServe.collectAsState()
    // Versions live inside their original; the shelf shows each recipe once.
    val originals = recipes.filter { it.parentId.isBlank() || recipes.none { o -> o.id == it.parentId } }
    val versions = recipes.groupBy { it.parentId }
    val tags = remember(recipes) { recipes.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(20) }
    val shown = originals.filter { r ->
        (tag == null || tag in r.tags) && (r.matches(query) || versions[r.id].orEmpty().any { it.matches(query) }) &&
            (maxMin == null || (r.minutes ?: Int.MAX_VALUE) <= maxMin!!) &&
            (maxCost == null || (per[r.id] ?: Double.MAX_VALUE) <= maxCost!!)
    }.let { l ->
        when (sort) {
            Sort.NEWEST -> l.sortedByDescending { it.createdAt }
            Sort.CHEAPEST -> l.sortedBy { per[it.id] ?: Double.MAX_VALUE }
            Sort.QUICKEST -> l.sortedBy { it.minutes ?: Int.MAX_VALUE }
            Sort.AZ -> l.sortedBy { it.title.lowercase() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Reel Recipes", fontFamily = Display, fontSize = 26.sp) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Save a recipe") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search recipes or ingredients") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(50))
            }
            if (recipes.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Box {
                            AssistChip(onClick = { sortOpen = true }, label = { Text("Sort: ${sort.label}") })
                            DropdownMenu(sortOpen, { sortOpen = false }) {
                                Sort.entries.forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { sort = o; sortOpen = false }) }
                            }
                        }
                    }
                    items(listOf(20, 30, 45)) { m -> FilterChip(maxMin == m, { maxMin = if (maxMin == m) null else m }, { Text("≤ $m min") }) }
                    items(listOf(3.0, 5.0, 8.0)) { c -> FilterChip(maxCost == c, { maxCost = if (maxCost == c) null else c }, { Text("≤ $${c.toInt()}/serve") }) }
                }
            }
            if (tags.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(tag == null, { tag = null }, { Text("All") }) }
                    items(tags) { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, { Text(t) }) }
                }
            }
            if (recipes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyShelf() }
            else if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (maxCost != null && per.isEmpty()) "Costs show up once you've opened a recipe and its prices have loaded." else "No recipes match that.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            items(shown, key = { it.id }) { r -> RecipeCard(r, store, per[r.id], versions[r.id]?.size ?: 0) { onOpen(r.id) } }
        }
    }
}

@Composable
private fun EmptyShelf() {
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Your cookbook is empty", fontFamily = Display, fontSize = 22.sp)
        Text("Save the next recipe you spot on Instagram:", color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            "On the post or reel, tap Share (the paper plane).",
            "Tap Share to… / More and pick “Save recipe”.",
            "Reel Recipes reads the caption and lays out the recipe for you to check.",
        ).forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${i + 1}", fontFamily = Mono, color = MaterialTheme.colorScheme.primary)
                Text(s)
            }
        }
    }
}

@Composable
private fun RecipeCard(r: Recipe, store: RecipeStore, perServe: Double?, versions: Int, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val cover = r.photos.firstOrNull()
            if (cover != null) AsyncImage(store.photo(cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(r.title.take(1).uppercase(), fontFamily = Mono, fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (perServe != null) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Pill(Costing.money(perServe) + "/serve", strong = true) }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Text(r.title.ifBlank { "Untitled" }, fontFamily = Display, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val by = listOfNotNull(r.author.takeIf { it.isNotBlank() }, r.time.takeIf { it.isNotBlank() }, versions.takeIf { it > 0 }?.let { "+$it version${if (it > 1) "s" else ""}" }).joinToString(" · ")
            if (by.isNotEmpty()) Text(by, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Import

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportScreen(shared: Shared?, store: RecipeStore, onCancel: () -> Unit, onDraft: (Recipe, List<Photo>, String) -> Unit, onSignIn: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<ByteArray>() }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loginHint by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val webView = remember { PostReader.newWebView(ctx) }
    DisposableEffect(Unit) { onDispose { webView.stopLoading(); webView.destroy() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        scope.launch { uris.forEach { u -> Extractor.loadJpeg(ctx, u)?.let { photos += it } } }
    }

    // Reads the text in every photo on the phone, then builds the recipe.
    suspend fun buildDraft(auto: Boolean) {
        busy = true
        try {
            var ocr = ""
            if (photos.isNotEmpty()) { status = "Reading text in ${photos.size} photo${if (photos.size == 1) "" else "s"}…"; ocr = Ocr.readAll(photos.toList()) }
            if (caption.isBlank() && ocr.isBlank()) {
                status = if (auto) "Couldn't read this post automatically. " + (if (PostReader.isSignedIn()) "" else "Signing in to Instagram usually fixes this. ") + "You can also add screenshots of the recipe."
                         else "Nothing to read yet. Add the caption or screenshots of the recipe."
                loginHint = !PostReader.isSignedIn()
                return
            }
            status = "Pulling out the recipe…"
            val d = Extractor.extract(caption, author, Instagram.clean(url), photos.toList(), store.apiKey, ocr)
            onDraft(d.recipe, photos.map { Photo.Pending(it) }, d.missing)
        } catch (e: Exception) {
            status = e.message ?: "Couldn't read a recipe from that."
        } finally { busy = false }
    }

    // Open the post like a browser would, collect caption and photos, then build the recipe automatically.
    LaunchedEffect(shared?.stamp, attempt) {
        if (attempt == 0) {
            if (shared == null) return@LaunchedEffect
            url = Instagram.findLink(shared.text) ?: ""
            val rest = shared.text.replace(Regex("""https?://\S+"""), "").trim()
            if (rest.length > 20) caption = rest
            shared.images.forEach { u -> Extractor.loadJpeg(ctx, u)?.let { photos += it } }
        }
        val link = url.takeIf { it.contains("instagram.com") } ?: run { if (attempt > 0 || photos.isNotEmpty() || caption.isNotBlank()) buildDraft(true); return@LaunchedEffect }
        busy = true; loginHint = false; status = "Opening the post…"
        var best: ReadPost? = null
        val code = Regex("""/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""").find(link)?.groupValues?.get(1)
        val pages = listOfNotNull(Instagram.clean(link), code?.let { "https://www.instagram.com/p/$it/embed/captioned/" })
        for (page in pages) {
            webView.loadUrl(page)
            for (i in 0 until 8) {
                delay(1500)
                val p = PostReader.extract(webView) ?: continue
                val prev = best
                val imgs = (p.imageUrls + (prev?.imageUrls ?: emptyList())).distinct()
                best = if (prev == null || p.caption.length > prev.caption.length) p.copy(imageUrls = imgs) else prev.copy(imageUrls = imgs)
                val got = best!!
                if (got.caption.length > 40 && got.imageUrls.isNotEmpty() && i >= 1) break
            }
            if ((best?.caption?.length ?: 0) > 40) break
        }
        if (best?.caption.isNullOrBlank()) {
            status = "Trying another way…"
            Instagram.fetch(link)?.let { info -> best = ReadPost(info.caption, info.author, emptyList()); info.image?.let { Extractor.shrink(it) }?.let { photos.add(0, it) } }
        }
        best?.let { b ->
            if (b.caption.length > caption.length) caption = b.caption
            if (b.author.isNotBlank()) author = b.author
            if (b.imageUrls.isNotEmpty()) {
                status = "Saving ${b.imageUrls.size} photo${if (b.imageUrls.size == 1) "" else "s"} from the post…"
                val imgs = PostReader.download(b.imageUrls)
                photos.addAll(0, imgs)
            }
        }
        buildDraft(true)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("Save from Instagram") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            status?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(it, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
            if (loginHint) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSignIn) { Text("Sign in to Instagram") }
                OutlinedButton(onClick = { attempt++ }) { Text("Try again") }
            }
            // The post itself, loaded like a browser. Also serves as a visual reference while checking.
            if (url.contains("instagram.com")) {
                Text("Post", fontWeight = FontWeight.SemiBold)
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)))
            }
            Text("Photos", fontWeight = FontWeight.SemiBold)
            PhotoStrip(photos.map { Photo.Pending(it) }, store, onRemove = { photos.removeAt(it) },
                onAdd = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            Text("Photos from the post are added for you. If the recipe is only shown in the video, add screenshots of those frames. The text in them is read automatically.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Post or reel link") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(caption, { caption = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("Caption") },
                placeholder = { Text("Filled in from the post") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy, onClick = { scope.launch { buildDraft(false) } }) { Text("Pull out the recipe") }
                TextButton(enabled = !busy, onClick = {
                    onDraft(Recipe(url = Instagram.clean(url), author = author, notes = caption), photos.map { Photo.Pending(it) }, "")
                }) { Text("Type it myself") }
            }
        }
    }
}

// ---------------------------------------------------------------- Instagram sign-in

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstagramSignInScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val webView = remember { PostReader.newWebView(ctx).apply { loadUrl("https://www.instagram.com/accounts/login/") } }
    DisposableEffect(Unit) { onDispose { android.webkit.CookieManager.getInstance().flush(); webView.destroy() } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("Sign in to Instagram") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, "Close") } },
                actions = { TextButton(onClick = onDone) { Text("Done", fontWeight = FontWeight.Bold) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("This is Instagram's own sign-in page. Reel Recipes never sees your password; it only keeps Instagram's sign-in cookie so it can open posts for you. Tap Done when you see your feed.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StatusBox(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), fontSize = 14.sp)
}

@Composable
private fun PhotoStrip(photos: List<Photo>, store: RecipeStore, onRemove: (Int) -> Unit, onAdd: () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(photos) { i, p ->
            Box(Modifier.size(76.dp, 96.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val model: Any = when (p) { is Photo.Saved -> store.photo(p.name); is Photo.Pending -> p.jpeg }
                AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(24.dp).clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .6f)).clickable { onRemove(i) },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "Remove photo", Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        item {
            Box(Modifier.size(76.dp, 96.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                Text("+ Add\nphotos", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- Edit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScreen(s: Screen.Edit, store: RecipeStore, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = s.recipe
    var title by remember { mutableStateOf(r.title) }
    var author by remember { mutableStateOf(r.author) }
    var serves by remember { mutableStateOf(r.servings) }
    var time by remember { mutableStateOf(r.time) }
    var ings by remember { mutableStateOf(ingredientsToText(r.ingredients)) }
    var steps by remember { mutableStateOf(r.steps.joinToString("\n")) }
    var tags by remember { mutableStateOf(r.tags.joinToString(", ")) }
    var notes by remember { mutableStateOf(r.notes) }
    var url by remember { mutableStateOf(r.url) }
    val photos = remember { mutableStateListOf<Photo>().apply { addAll(s.photos) } }
    var status by remember { mutableStateOf(if (s.note.isNotBlank()) "Check this: ${s.note}" else if (s.isNew) "Check it over, then save." else null) }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        scope.launch { uris.forEach { u -> Extractor.loadJpeg(ctx, u)?.let { photos += Photo.Pending(it) } } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text(if (s.isNew) "New recipe" else "Edit recipe") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(enabled = !saving, onClick = {
                        if (title.isBlank()) { status = "Give the recipe a name."; return@TextButton }
                        saving = true
                        scope.launch {
                            try {
                                val names = photos.map { p -> when (p) { is Photo.Saved -> p.name; is Photo.Pending -> store.savePhoto(p.jpeg) } }
                                val saved = r.copy(
                                    title = title.trim(), author = author.trim(), servings = serves.trim(), time = time.trim(),
                                    ingredients = textToIngredients(ings),
                                    steps = steps.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                    tags = tags.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                                    notes = notes.trim(), url = Instagram.clean(url), photos = names,
                                )
                                store.save(saved)
                                Toast.makeText(ctx, if (s.isNew) "Recipe saved" else "Recipe updated", Toast.LENGTH_SHORT).show()
                                onSaved(saved.id)
                            } catch (e: Exception) {
                                status = "Couldn't save: ${e.message}. Your text is still here, so try again."
                            } finally { saving = false }
                        }
                    }) { Text("Save", fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            status?.let { StatusBox(it) }
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(author, { author = it }, Modifier.weight(1.3f), label = { Text("Creator") }, singleLine = true)
                OutlinedTextField(serves, { serves = it }, Modifier.weight(1f), label = { Text("Serves") }, singleLine = true)
                OutlinedTextField(time, { time = it }, Modifier.weight(1f), label = { Text("Time") }, singleLine = true)
            }
            OutlinedTextField(ings, { ings = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text("Ingredients") },
                supportingText = { Text("One per line: amount | ingredient | note") })
            OutlinedTextField(steps, { steps = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp), label = { Text("Method") },
                supportingText = { Text("One step per line") })
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Tags") }, placeholder = { Text("pasta, quick") }, singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp), label = { Text("Notes") })
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Instagram link") }, singleLine = true)
            Text("Photos", fontWeight = FontWeight.SemiBold)
            PhotoStrip(photos, store, onRemove = { photos.removeAt(it) },
                onAdd = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        }
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(store: RecipeStore, prices: PriceBook, onBack: () -> Unit, onSignIn: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(store.apiKey) }
    var signedIn by remember { mutableStateOf(PostReader.isSignedIn()) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StoresSection(prices)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
            Text("Instagram", fontFamily = Display, fontSize = 20.sp)
            Text(if (signedIn) "Signed in. Reel Recipes can open posts and reels the way your browser does."
                 else "Optional, but recommended. Instagram shows less to signed-out visitors, so signing in once makes captions and photos come through for far more posts.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signedIn) OutlinedButton(onClick = { PostReader.signOut(); signedIn = false }) { Text("Sign out of Instagram") }
                else Button(onClick = onSignIn) { Text("Sign in to Instagram") }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
            Text("Claude recipe reading (optional)", fontFamily = Display, fontSize = 20.sp)
            Text("The app works without this: it reads the caption and the text in photos on your phone, for free. " +
                "Adding an Anthropic API key lets Claude tidy up messy captions, estimate missing amounts and understand photos without text. " +
                "API use is billed by Anthropic separately from a Claude subscription; create a key at console.anthropic.com.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Anthropic API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), placeholder = { Text("sk-ant-…") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { store.apiKey = key; Toast.makeText(ctx, "Key saved", Toast.LENGTH_SHORT).show() }) { Text("Save key") }
                if (key.isNotBlank()) TextButton(onClick = { key = ""; store.apiKey = "" }) { Text("Remove key") }
            }
            Text("The key is stored only on this phone and is sent only to api.anthropic.com.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StoresSection(prices: PriceBook) {
    val scope = rememberCoroutineScope()
    var main by remember { mutableStateOf(prices.store) }
    var others by remember { mutableStateOf(prices.compare) }
    var adding by remember { mutableStateOf<Chain?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testOut by remember { mutableStateOf("") }
    fun save() { prices.store = main; prices.compare = others.filter { it != main } }

    Text("Supermarket prices", fontFamily = Display, fontSize = 20.sp)
    Muted("Meal costs use live prices from your main store. Add more stores to compare shopping lists. Prices come from the supermarkets' own websites and apps, so a store can stop working if it changes its site; you can always type a price in by hand.", 14)
    (listOf(main) + others.filter { it != main }).forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s == main, onClick = { main = s; others = (others + prices.store).distinct().filter { it != s }; save() })
            Column(Modifier.weight(1f)) {
                Text(s.label)
                Muted(if (s == main) "Main store" else "Compare", 12)
            }
            if (s != main) IconButton(onClick = { others = others - s; save() }) { Icon(Icons.Default.Close, "Remove store") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chain.entries.forEach { c -> AssistChip(onClick = {
            if (c == Chain.WOOLWORTHS) { val w = StoreRef(Chain.WOOLWORTHS, "", "Woolworths (online prices)"); if (w != main && w !in others) { others = others + w; save() } }
            else adding = c
        }, label = { Text("+ ${c.label}") }) }
    }
    OutlinedButton(enabled = !testing, onClick = {
        testing = true; testOut = ""
        scope.launch {
            val lines = prices.allStores.map { st ->
                runCatching { Grocery.search(st, "milk", 5) }.fold(
                    { r -> if (r.isEmpty()) "${st.label}: connected, but no products came back." else "${st.label}: working. e.g. ${r.first().displayName} ${Costing.money(r.first().price)}" },
                    { e -> "${st.label}: ${e.message}\n" + (Grocery.lastDebug[st.chain] ?: "").take(400) },
                )
            }
            testOut = lines.joinToString("\n\n"); testing = false
        }
    }) { Text(if (testing) "Testing…" else "Test prices") }
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        val n = prices.count(main)
        if (n == 0) { Toast.makeText(ctx, "No prices yet. Open a recipe first so its prices load.", Toast.LENGTH_LONG).show(); return@OutlinedButton }
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Reel Recipes prices", prices.exportJson(main)))
        Toast.makeText(ctx, "Copied $n prices. Paste them in the web app under ⚙ → Import prices.", Toast.LENGTH_LONG).show()
    }) { Text("Copy prices for the web app") }
    if (testOut.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
        Text(testOut, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp))
    }

    adding?.let { chain ->
        var list by remember(chain) { mutableStateOf<List<StoreRef>>(emptyList()) }
        var err by remember(chain) { mutableStateOf<String?>(null) }
        var q by remember(chain) { mutableStateOf("") }
        LaunchedEffect(chain) { runCatching { Grocery.stores(chain) }.onSuccess { list = it }.onFailure { err = it.message } }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text("Choose your ${chain.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search, e.g. Mt Albert") })
                    if (list.isEmpty() && err == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    err?.let { Text(it + "\n\n" + (Grocery.lastDebug[chain] ?: "").take(300), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list.filter { it.name.contains(q, true) }) { st ->
                            Text(st.name, Modifier.fillMaxWidth().clickable {
                                if (st != main && st !in others) { others = others + st; save() }
                                adding = null
                            }.padding(vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = null }) { Text("Close") } },
        )
    }
}
