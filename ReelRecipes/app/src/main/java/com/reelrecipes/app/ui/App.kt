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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.reelrecipes.app.*
import kotlinx.coroutines.launch

/** A photo on a recipe: already saved on the phone, or picked and not yet saved. */
sealed interface Photo {
    data class Saved(val name: String) : Photo
    class Pending(val jpeg: ByteArray) : Photo
}

private sealed interface Screen {
    data object Shelf : Screen
    data class View(val id: String) : Screen
    data class Import(val shared: Shared?) : Screen
    data class Edit(val recipe: Recipe, val photos: List<Photo>, val note: String, val isNew: Boolean) : Screen
    data object Settings : Screen
}

@Composable
fun App(store: RecipeStore, incoming: Shared?, onIncomingHandled: () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Shelf) }
    val recipes by store.recipes.collectAsState()

    LaunchedEffect(incoming?.stamp) {
        if (incoming != null) { screen = Screen.Import(incoming); onIncomingHandled() }
    }
    BackHandler(enabled = screen != Screen.Shelf) {
        screen = when (val s = screen) {
            is Screen.Edit -> if (s.isNew) Screen.Shelf else Screen.View(s.recipe.id)
            else -> Screen.Shelf
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            Screen.Shelf -> ShelfScreen(recipes, store,
                onOpen = { screen = Screen.View(it) },
                onAdd = { screen = Screen.Import(null) },
                onSettings = { screen = Screen.Settings })
            is Screen.View -> {
                val r = recipes.firstOrNull { it.id == s.id }
                if (r == null) LaunchedEffect(Unit) { screen = Screen.Shelf }
                else RecipeScreen(r, store,
                    onBack = { screen = Screen.Shelf },
                    onEdit = { screen = Screen.Edit(r, r.photos.map { Photo.Saved(it) }, "", false) })
            }
            is Screen.Import -> ImportScreen(s.shared, store,
                onCancel = { screen = Screen.Shelf },
                onDraft = { recipe, photos, note -> screen = Screen.Edit(recipe, photos, note, true) })
            is Screen.Edit -> EditScreen(s, store,
                onBack = { screen = if (s.isNew) Screen.Shelf else Screen.View(s.recipe.id) },
                onSaved = { screen = Screen.View(it) })
            Screen.Settings -> SettingsScreen(store, onBack = { screen = Screen.Shelf })
        }
    }
}

// ---------------------------------------------------------------- Shelf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfScreen(recipes: List<Recipe>, store: RecipeStore, onOpen: (String) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    val tags = remember(recipes) { recipes.flatMap { it.tags }.distinct().sorted() }
    val shown = recipes.filter { (tag == null || tag in it.tags) && it.matches(query) }

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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search recipes or ingredients") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(50))
            }
            if (tags.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(tag == null, { tag = null }, { Text("All") }) }
                    items(tags) { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, { Text(t) }) }
                }
            }
            if (recipes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyShelf() }
            else if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No recipes match that.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            items(shown, key = { it.id }) { r -> RecipeCard(r, store) { onOpen(r.id) } }
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
private fun RecipeCard(r: Recipe, store: RecipeStore, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val cover = r.photos.firstOrNull()
            if (cover != null) AsyncImage(store.photo(cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(r.title.take(1).uppercase(), fontFamily = Mono, fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Text(r.title.ifBlank { "Untitled" }, fontFamily = Display, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val by = listOf(r.author, r.time).filter { it.isNotBlank() }.joinToString(" · ")
            if (by.isNotEmpty()) Text(by, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Recipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeScreen(r: Recipe, store: RecipeStore, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val checked = remember(r.id) { mutableStateMapOf<Int, Boolean>() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
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
            val meta = listOfNotNull(
                r.author.takeIf { it.isNotBlank() },
                r.servings.takeIf { it.isNotBlank() }?.let { "Serves $it" },
                r.time.takeIf { it.isNotBlank() },
            )
            if (meta.isNotEmpty()) Text(meta.joinToString("   "), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (r.tags.isNotEmpty()) Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.tags.forEach { Text(it, fontSize = 12.sp, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 3.dp)) }
            }
            if (r.url.isNotBlank()) {
                OutlinedCard(
                    onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Original post", fontWeight = FontWeight.SemiBold)
                            Text(r.url.removePrefix("https://www."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Watch ↗", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            SectionHead("Ingredients", "Tick as you go")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            r.ingredients.forEachIndexed { i, ing ->
                val on = checked[i] == true
                Row(
                    Modifier.fillMaxWidth().clickable { checked[i] = !on }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, { checked[i] = it })
                    Text(ing.qty, fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(78.dp))
                    Text(
                        if (ing.note.isBlank()) ing.item else "${ing.item}, ${ing.note}",
                        modifier = Modifier.weight(1f),
                        textDecoration = if (on) TextDecoration.LineThrough else null,
                        color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            SectionHead("Method", "${r.steps.size} steps")
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                r.steps.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("%02d".format(i + 1), fontFamily = Mono, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSecondary,
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

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this recipe?") },
        text = { Text("Its photos are deleted too.") },
        confirmButton = {
            TextButton({
                confirmDelete = false
                scope.launch {
                    store.delete(r.id)
                    Toast.makeText(ctx, "Recipe deleted", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep it") } },
    )
}

@Composable
private fun SectionHead(title: String, aside: String) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontFamily = Display, fontSize = 21.sp, modifier = Modifier.weight(1f))
        Text(aside, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Import

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportScreen(shared: Shared?, store: RecipeStore, onCancel: () -> Unit, onDraft: (Recipe, List<Photo>, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<ByteArray>() }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        scope.launch { uris.forEach { u -> Extractor.loadJpeg(ctx, u)?.let { photos += it } } }
    }

    // Fill in what was shared, then try to read the caption from Instagram.
    LaunchedEffect(shared?.stamp) {
        if (shared == null) return@LaunchedEffect
        val link = Instagram.findLink(shared.text)
        url = link ?: ""
        val rest = shared.text.replace(Regex("""https?://\S+"""), "").trim()
        if (rest.length > 20) caption = rest
        shared.images.forEach { u -> Extractor.loadJpeg(ctx, u)?.let { photos += it } }
        if (link != null && caption.isBlank()) {
            busy = true; status = "Reading the post from Instagram…"
            val info = Instagram.fetch(link)
            busy = false
            if (info != null && info.caption.isNotBlank()) {
                caption = info.caption; author = info.author
                info.image?.let { Extractor.shrink(it) }?.let { photos.add(0, it) }
                status = null
            } else {
                status = "Instagram didn't hand over the caption. Copy it from the post and paste it below, or add screenshots."
            }
        }
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
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Post or reel link") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(caption, { caption = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("Caption") },
                placeholder = { Text("Paste the post's caption here") })
            Text("Screenshots & photos", fontWeight = FontWeight.SemiBold)
            PhotoStrip(photos.map { Photo.Pending(it) }, store, onRemove = { photos.removeAt(it) },
                onAdd = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            Text("If the recipe is only in the video, screenshot the frames showing ingredients and steps. The first photo is the cover.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (store.apiKey.isBlank()) Text("Tip: add an Anthropic API key in Settings so Claude can read screenshots and messy captions.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status?.let { StatusBox(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy, onClick = {
                    if (caption.isBlank() && photos.isEmpty()) { status = "Paste the caption or add a screenshot first."; return@Button }
                    busy = true; status = "Pulling out the recipe…"
                    scope.launch {
                        try {
                            val d = Extractor.extract(caption, author, Instagram.clean(url), photos.toList(), store.apiKey)
                            onDraft(d.recipe, photos.map { Photo.Pending(it) }, d.missing)
                        } catch (e: Exception) {
                            status = e.message ?: "Couldn't read a recipe from that."
                        } finally { busy = false }
                    }
                }) { Text("Pull out the recipe") }
                TextButton(enabled = !busy, onClick = {
                    onDraft(Recipe(url = Instagram.clean(url), author = author, notes = caption), photos.map { Photo.Pending(it) }, "")
                }) { Text("Type it myself") }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
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
private fun SettingsScreen(store: RecipeStore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(store.apiKey) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Claude recipe reading", fontFamily = Display, fontSize = 20.sp)
            Text("Without a key, the app splits captions into ingredients and steps on the phone. That works for neatly laid-out captions. " +
                "With an Anthropic API key, Claude reads messy captions and screenshots too. API use is billed by Anthropic separately from a Claude subscription; " +
                "create a key at console.anthropic.com.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Anthropic API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), placeholder = { Text("sk-ant-…") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { store.apiKey = key; Toast.makeText(ctx, "Key saved", Toast.LENGTH_SHORT).show(); onBack() }) { Text("Save key") }
                if (key.isNotBlank()) TextButton(onClick = { key = ""; store.apiKey = "" }) { Text("Remove key") }
            }
            Text("The key is stored only on this phone and is sent only to api.anthropic.com.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
