package fr.reelmind.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage

private val blue = Color(0xFF397FDD)
private val paleBlue = Color(0xFF7CB5FF)
private val darkBackground = Color(0xFF10151E)

@Composable
fun ReelMindScreen(vm: ReelModel, activity: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tout") }
    var selected by remember { mutableStateOf<Reel?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var newCaption by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    var libraryQuestion by remember { mutableStateOf("") }
    var showCategories by remember { mutableStateOf(false) }
    var categoryRevision by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("") }
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var themePreference by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    val systemDark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val isDark = when (themePreference) { "dark" -> true; "light" -> false; else -> systemDark }
    val scheme = if (isDark) darkColorScheme(primary = paleBlue, background = darkBackground, surface = Color(0xFF1B2532), surfaceVariant = Color(0xFF253344))
                 else lightColorScheme(primary = blue, background = Color(0xFFF7F9FD), surface = Color.White, surfaceVariant = Color(0xFFEAF0F8))
    val categories = remember(categoryRevision) { CategoryStore.list(activity) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.aiError) { vm.aiError?.let { snack.showSnackbar(it); vm.aiError = null } }
    LaunchedEffect(feedback) { if (feedback.isNotBlank()) { snack.showSnackbar(feedback); feedback = "" } }
    LaunchedEffect(vm.pendingUrl) {
        vm.pendingUrl?.let { newUrl = it; newCaption = vm.pendingCaption; showAdd = true; vm.pendingUrl = null; vm.pendingCaption = "" }
    }
    BackHandler(selected != null) { selected = null }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selected != null) IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Retour") }
                        Text(if (selected == null) "ReelMind" else "Fiche", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (selected == null && tab != 2) IconButton(onClick = { tab = 2 }) { Icon(Icons.Default.Settings, "Paramètres") }
                    }
                }
            },
            bottomBar = {
                if (selected == null) NavigationBar {
                    listOf(Triple("Accueil", Icons.Default.Home, 0), Triple("Recherche", Icons.Default.Search, 1), Triple("Réglages", Icons.Default.Settings, 2)).forEach { (label, icon, id) ->
                        NavigationBarItem(selected = tab == id, onClick = { tab = id }, icon = { Icon(icon, null) }, label = { Text(label) })
                    }
                }
            },
            floatingActionButton = {
                if (selected == null && tab != 2) FloatingActionButton(onClick = { newUrl = ""; newCaption = ""; showAdd = true }) { Icon(Icons.Default.Add, "Ajouter un Reel") }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
                if (selected != null) {
                    val reel = vm.reels.firstOrNull { it.id == selected!!.id } ?: selected!!
                    DetailV51(reel, categories, onSave = { vm.update(it); selected = it },
                        onDelete = { vm.remove(reel); selected = null },
                        onSummarize = vm::summarize, busy = vm.processingId == reel.id,
                        onExport = { activity.shareText(exportMarkdown(reel)) }, onOpen = { activity.openUrl(reel.url) },
                        onSpeak = { activity.speak(reel.summary) }, onImport = { mime -> activity.chooseMedia(mime) { uri -> vm.importMedia(reel, uri) } },
                        onRetry = { vm.retry(reel) }, messages = vm.messages, onChatOpen = { vm.observeChat(reel.id) },
                        onAsk = { vm.askReel(reel, it) }, chatBusy = vm.chatAnswering,
                        onManageCategories = { showCategories = true })
                } else if (tab == 2) {
                    SettingsV51(vm, activity, themePreference, { value -> themePreference = value; prefs.edit().putString("theme", value).apply() }, onManageCategories = { showCategories = true })
                } else {
                    val shown = vm.reels.filter { reel ->
                        (filter == "Tout" || reel.category == filter) &&
                        listOf(reel.title, reel.summary, reel.note, reel.category, reel.sourceText, reel.originalCaption, reel.mediaText, reel.author, reel.url).any { it.contains(search.trim(), ignoreCase = true) }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                        if (tab == 0) {
                            item { SearchEntry(search, { search = it; if (it.isNotBlank()) tab = 1 }, onBrain = { showChat = true }) }
                            item { SectionTitle("Récemment enregistrés") }
                            item {
                                if (vm.reels.isEmpty()) Text("Partage ton premier Reel depuis Instagram.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(vm.reels.take(8), key = { it.id }) { reel -> RecentCard(reel, onClick = { selected = reel }) }
                                }
                            }
                            item { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { SectionTitle("Catégories") }; TextButton(onClick = { showCategories = true }) { Icon(Icons.Default.Add, null); Text("Ajouter") } } }
                            item { CategoryGrid(categories, vm.reels, onPick = { filter = it; tab = 1 }, onAdd = { showCategories = true }) }
                            item { SectionTitle("À redécouvrir") }
                            item {
                                vm.reels.filter { it.summary.isNotBlank() }.drop(3).firstOrNull()?.let { reel ->
                                    ReelCard(reel, { selected = reel }, { vm.update(reel.copy(favorite = !reel.favorite)) })
                                } ?: Text("Les fiches plus anciennes réapparaîtront ici.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item { SectionTitle("Toutes les fiches") }
                        } else {
                            item { SearchEntry(search, { search = it }, onBrain = { showChat = true }) }
                            item { SectionTitle("Rechercher dans ma bibliothèque") }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(listOf("Tout") + categories.map { it.name } + vm.reels.map { it.category }.filter { it != "À classer" && categories.none { cat -> cat.name == it } }.distinct()) { item ->
                                        FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) })
                                    }
                                }
                            }
                        }
                        if (vm.isLoading) item { CircularProgressIndicator() }
                        else if (shown.isEmpty()) item { Text("Aucun résultat.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else items(shown, key = { it.id }) { reel -> ReelCard(reel, { selected = reel }, { vm.update(reel.copy(favorite = !reel.favorite)) }) }
                    }
                }
            }
        }
        if (showCategories) CategoryEditor(categories, onDismiss = { showCategories = false }, onAdd = { item -> CategoryStore.add(activity, item); categoryRevision++ })
        if (showChat) AlertDialog(onDismissRequest = { showChat = false }, title = { Text("Question à ma bibliothèque") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Interroge les fiches déjà analysées.")
                OutlinedTextField(libraryQuestion, { libraryQuestion = it }, label = { Text("Ta question") }, modifier = Modifier.fillMaxWidth())
                if (vm.libraryAsking) CircularProgressIndicator()
                if (vm.libraryAnswer.isNotBlank()) Text(vm.libraryAnswer)
            }
        }, confirmButton = { TextButton(onClick = { vm.askLibrary(libraryQuestion) }, enabled = libraryQuestion.isNotBlank() && !vm.libraryAsking) { Text("Interroger") } }, dismissButton = { TextButton(onClick = { showChat = false }) { Text("Fermer") } })
        if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Enregistrer une publication") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(newUrl, { newUrl = it }, label = { Text("Lien Instagram") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newCaption, { newCaption = it }, label = { Text("Légende facultative") }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onClick = {
            val clean = newUrl.trim().substringBefore('?').trimEnd('/')
            if (runCatching { ExtractionCoordinator.validateInstagramUrl(clean) }.isFailure) feedback = "Lien Instagram non reconnu"
            else { vm.addShared(clean, newCaption) { feedback = if (it) "Publication enregistrée" else "Publication déjà enregistrée" }; showAdd = false }
        }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } })
    }
}

@Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }

@Composable private fun SearchEntry(value: String, onChange: (String) -> Unit, onBrain: () -> Unit) {
    OutlinedTextField(value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        placeholder = { Text("Rechercher dans mes Reels…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { IconButton(onClick = onBrain) { Icon(Icons.Default.AutoAwesome, "Interroger ma bibliothèque") } })
}

@Composable private fun RecentCard(reel: Reel, onClick: () -> Unit) {
    Column(Modifier.width(168.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            if (reel.thumbnailUrl.startsWith("https://")) AsyncImage(reel.thumbnailUrl, null, Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayCircleOutline, null, Modifier.size(38.dp)) }
        }
        Text(reel.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(reel.category, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun CategoryGrid(categories: List<ReelCategory>, reels: List<Reel>, onPick: (String) -> Unit, onAdd: () -> Unit) {
    val items = categories.take(5)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { cat ->
                    Card(onClick = { onPick(cat.name) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(cat.icon, style = MaterialTheme.typography.headlineSmall)
                            Text(cat.name, fontWeight = FontWeight.SemiBold)
                            Text("${reels.count { it.category == cat.name }} fiches", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Créer une catégorie") }
    }
}

@Composable private fun ReelCard(reel: Reel, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (reel.thumbnailUrl.startsWith("https://")) AsyncImage(reel.thumbnailUrl, null, Modifier.size(100.dp), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f)) {
                    Text(reel.category, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text(reel.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (reel.author.isNotBlank()) Text("@${reel.author.removePrefix("@")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFavorite) { Icon(if (reel.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (reel.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(reel.summary.ifBlank { when(reel.processingStatus) { "failed" -> "Échec du traitement — ouvrir pour réessayer"; "blocked" -> "Contenu indisponible — ajout manuel possible"; "extracting" -> "Extraction en cours…"; "summarizing" -> "Résumé en cours…"; else -> "À analyser" } }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onCheckedChange = onChange)
    }
}

@Composable private fun SettingsV51(vm: ReelModel, activity: MainActivity, theme: String, onTheme: (String) -> Unit, onManageCategories: () -> Unit) {
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var showKey by remember { mutableStateOf(false) }
    var audioProvider by remember { mutableStateOf(prefs.getString("ex_audio_provider", "Groq") ?: "Groq") }
    var audioModel by remember { mutableStateOf(prefs.getString("gemini_audio_model", "gemini-3.5-transcribe") ?: "gemini-3.5-transcribe") }
    var imageModel by remember { mutableStateOf(prefs.getString("gemini_image_model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite") }
    var geminiKey by remember { mutableStateOf(prefs.getString("key_Gemini", "").orEmpty()) }
    var groqKey by remember { mutableStateOf(prefs.getString("key_Groq", "").orEmpty()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Apparence")
        Text("Thème de l'application", style = MaterialTheme.typography.labelLarge)
        listOf("system" to "Automatique (Android)", "light" to "Clair", "dark" to "Sombre").forEach { (value, name) ->
            Row(Modifier.fillMaxWidth().clickable { onTheme(value) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == value, onClick = { onTheme(value) }); Text(name)
            }
        }
        HorizontalDivider()
        SectionTitle("Intelligence artificielle")
        SettingSwitch("Résumés automatiques", vm.autoProcess) { vm.saveAutoProcess(activity, it) }
        Text("Fournisseur des résumés")
        ChoiceMenu(vm.provider, listOf("Gemini", "Groq", "OpenAI", "Ollama")) { vm.saveProvider(activity, it) }
        Text("Modèle de résumé", style = MaterialTheme.typography.labelLarge)
        if (vm.provider == "Gemini") ChoiceMenu(vm.modelName, listOf("gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash", "Personnalisé")) { if (it != "Personnalisé") vm.saveModel(activity, it) }
        OutlinedTextField(vm.modelName, { vm.saveModel(activity, it) }, label = { Text("Identifiant exact du modèle") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (vm.provider == "Ollama") OutlinedTextField(vm.ollamaUrl, { vm.saveOllamaUrl(activity, it) }, label = { Text("URL HTTPS Ollama") }, modifier = Modifier.fillMaxWidth())
        else OutlinedTextField(vm.apiKey, { vm.saveApiKey(activity, it) }, label = { Text("Clé API ${vm.provider}") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Masquer" else "Voir") } })
        Text("Clés supplémentaires pour les traitements de médias", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(geminiKey, { geminiKey = it; prefs.edit().putString("key_Gemini", it).apply(); if (vm.provider == "Gemini") vm.saveApiKey(activity, it) }, label = { Text("Clé Gemini (images / audio)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(groqKey, { groqKey = it; prefs.edit().putString("key_Groq", it).apply(); if (vm.provider == "Groq") vm.saveApiKey(activity, it) }, label = { Text("Clé Groq (transcription)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        HorizontalDivider()
        ExtractionSettingsPanel(activity)
        HorizontalDivider()
        SectionTitle("Transcription et images")
        Text("Fournisseur de transcription audio")
        ChoiceMenu(audioProvider, listOf("Groq", "Gemini", "Désactivée")) { audioProvider = it; prefs.edit().putString("ex_audio_provider", it).putBoolean("ex_audio", it != "Désactivée").apply() }
        if (audioProvider == "Gemini") OutlinedTextField(audioModel, { audioModel = it; prefs.edit().putString("gemini_audio_model", it).apply() }, label = { Text("Modèle Gemini pour l'audio") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(imageModel, { imageModel = it; prefs.edit().putString("gemini_image_model", it).apply() }, label = { Text("Modèle Gemini pour les images") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Les médias importés pour analyse sont supprimés après traitement. Les données textuelles produites restent dans la fiche.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle("Organisation")
        OutlinedButton(onClick = onManageCategories, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Gérer les catégories") }
        HorizontalDivider()
        SectionTitle("Bibliothèque et outils")
        OutlinedButton(onClick = activity::requestNotifications, modifier = Modifier.fillMaxWidth()) { Text("Activer les notifications") }
        OutlinedButton(onClick = vm::requeueUnfinished, modifier = Modifier.fillMaxWidth()) { Text("Relancer les fiches sans résumé") }
        OutlinedButton(onClick = { activity.shareText(exportLibraryMarkdown(vm.reels)) }, modifier = Modifier.fillMaxWidth()) { Text("Exporter la bibliothèque (Markdown)") }
        Text("Les clés sont stockées localement dans les préférences Android, sans chiffrement applicatif supplémentaire.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun ChoiceMenu(selected: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected, modifier = Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); expanded = false }) }
        }
    }
}

@Composable private fun CategoryEditor(categories: List<ReelCategory>, onDismiss: () -> Unit, onAdd: (ReelCategory) -> Unit) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📁") }
    var color by remember { mutableStateOf(0xFF4285DBL) }
    val icons = listOf("📁", "📚", "🎯", "💡", "⭐", "⚡", "🔥", "💎", "🏆", "🎨", "🎵", "🎮", "🍕", "✈️", "💪", "💰", "📝", "🔬", "🐾", "🏠", "🚗", "🌿")
    val colors = listOf(0xFF4285DBL, 0xFF18A999L, 0xFF9163D5L, 0xFFE89140L, 0xFFD65C85L, 0xFF36A66BL)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajouter une catégorie") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(icon, style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedTextField(name, { name = it.take(35) }, modifier = Modifier.fillMaxWidth(), label = { Text("Nom de la catégorie") })
            Text("Choisir une icône")
            icons.chunked(7).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { value -> Surface(onClick = { icon = value }, shape = RoundedCornerShape(10.dp), color = if (icon == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(value, Modifier.padding(7.dp))
                } }
            } }
            Text("Couleur")
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                colors.forEach { value -> Box(Modifier.size(34.dp).background(Color(value), RoundedCornerShape(30.dp)).clickable { color = value }, contentAlignment = Alignment.Center) { if (color == value) Icon(Icons.Default.Check, null, tint = Color.White) } }
            }
            Text("${categories.size} catégories disponibles", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = { onAdd(ReelCategory(name.trim(), icon, color)); onDismiss() }, enabled = name.isNotBlank() && categories.none { it.name.equals(name.trim(), true) }) { Text("Ajouter") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable private fun DetailV51(reel: Reel, categories: List<ReelCategory>, onSave: (Reel) -> Unit, onDelete: () -> Unit,
    onSummarize: (Reel) -> Unit, busy: Boolean, onExport: () -> Unit, onOpen: () -> Unit, onSpeak: () -> Unit,
    onImport: (String) -> Unit, onRetry: () -> Unit, messages: List<ChatMessage>, onChatOpen: () -> Unit,
    onAsk: (String) -> Unit, chatBusy: Boolean, onManageCategories: () -> Unit) {
    var title by remember(reel.id, reel.title) { mutableStateOf(reel.title) }
    var category by remember(reel.id, reel.category) { mutableStateOf(reel.category) }
    var note by remember(reel.id, reel.note) { mutableStateOf(reel.note) }
    var source by remember(reel.id, reel.sourceText) { mutableStateOf(reel.sourceText) }
    var summary by remember(reel.id, reel.summary) { mutableStateOf(reel.summary) }
    var captionExpanded by remember { mutableStateOf(false) }
    var transcriptExpanded by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var chatExpanded by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var mediaMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (reel.thumbnailUrl.startsWith("https://")) AsyncImage(reel.thumbnailUrl, "Miniature", Modifier.fillMaxWidth().height(205.dp), contentScale = ContentScale.Crop)
        OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Titre") })
        Text(reel.author.ifBlank { "Instagram" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ChoiceMenu(category, listOf("À classer") + categories.map { it.name }) { category = it } }
            IconButton(onClick = onManageCategories) { Icon(Icons.Default.AddCircleOutline, "Créer une catégorie") }
        }
        Text("Résumé IA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (reel.summary.isNotBlank()) Text(reel.summary) else Text("Pas encore de résumé.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Analyse", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(when (reel.processingStatus) { "done" -> "Terminée"; "failed" -> "Échec"; "extracting" -> "Extraction…"; "summarizing" -> "En cours…"; else -> "En attente" }, style = MaterialTheme.typography.labelMedium)
        }
        if (reel.processingError.isNotBlank()) Text(reel.processingError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("Ajoute une image, un audio ou une vidéo pour compléter l'analyse. Le fichier temporaire sera supprimé après traitement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Réanalyser") }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { mediaMenu = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Média") }
                DropdownMenu(expanded = mediaMenu, onDismissRequest = { mediaMenu = false }) {
                    listOf("Image" to "image/*", "Audio" to "audio/*", "Vidéo" to "video/*").forEach { (label, mime) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { mediaMenu = false; onImport(mime) })
                    }
                }
            }
        }
        if (reel.originalCaption.isNotBlank()) {
            TextButton(onClick = { captionExpanded = !captionExpanded }) { Text("Légende originale"); Icon(if (captionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
            if (captionExpanded) Text(reel.originalCaption)
        }
        if (reel.mediaText.isNotBlank()) {
            TextButton(onClick = { transcriptExpanded = !transcriptExpanded }) { Text("Transcription"); Icon(if (transcriptExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
            if (transcriptExpanded) Text(reel.mediaText)
        }
        TextButton(onClick = { sourceExpanded = !sourceExpanded }) { Text("Texte source / édition"); Icon(if (sourceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
        if (sourceExpanded) {
            OutlinedTextField(source, { source = it }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            OutlinedTextField(summary, { summary = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Corriger le résumé") })
            Button(onClick = { onSummarize(reel.copy(title = title, category = category, sourceText = source)) }, enabled = source.isNotBlank() && !busy) { Text("Générer le résumé") }
        }
        HorizontalDivider()
        Text("Mes notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(note, { note = it }, placeholder = { Text("Ajouter une remarque personnelle…") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = { onSave(reel.copy(title = title, category = category, note = note, sourceText = source, summary = summary)) }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer les modifications") }
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Voir la publication originale") }
        if (reel.summary.isNotBlank()) OutlinedButton(onClick = onSpeak, modifier = Modifier.fillMaxWidth()) { Text("Écouter le résumé") }
        OutlinedButton(onClick = { chatExpanded = !chatExpanded; if (chatExpanded) onChatOpen() }, modifier = Modifier.fillMaxWidth()) { Text(if (chatExpanded) "Masquer la discussion" else "Discuter avec ce Reel") }
        if (chatExpanded) {
            messages.forEach { msg -> Card { Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(if (msg.role == "user") "Moi" else "ReelMind IA", fontWeight = FontWeight.Bold); Text(msg.content) } } }
            OutlinedTextField(question, { question = it }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onAsk(question); question = "" }, enabled = question.isNotBlank() && !chatBusy) { Text("Envoyer") }
        }
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Partager / exporter la fiche") }
        TextButton(onClick = { confirmDelete = true }) { Text("Supprimer la fiche") }
        Spacer(Modifier.height(20.dp))
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Supprimer cette fiche ?") }, confirmButton = { TextButton(onClick = onDelete) { Text("Supprimer") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } })
}
