package fr.reelmind.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

@Entity(tableName = "reels", indices = [Index(value = ["url"], unique = true)])
data class Reel(@PrimaryKey(autoGenerate = true) val id: Long = 0, val url: String, val title: String = "Publication Instagram", val summary: String = "", val note: String = "", val category: String = "À classer", val favorite: Boolean = false, val createdAt: Long = System.currentTimeMillis())

@Dao interface ReelDao {
    @Query("SELECT * FROM reels ORDER BY createdAt DESC") fun observe(): Flow<List<Reel>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(reel: Reel): Long
    @Update suspend fun update(reel: Reel)
    @Delete suspend fun delete(reel: Reel)
}
@Database(entities = [Reel::class], version = 1, exportSchema = false)
abstract class ReelDatabase : RoomDatabase() { abstract fun dao(): ReelDao }

class ReelModel : ViewModel() {
    var reels by mutableStateOf<List<Reel>>(emptyList()); private set
    var pendingUrl by mutableStateOf<String?>(null)
    var provider by mutableStateOf("Gemini")
    var modelName by mutableStateOf("gemini-2.5-flash")
    lateinit var dao: ReelDao
    fun initialize(activity: ComponentActivity) {
        dao = Room.databaseBuilder(activity.applicationContext, ReelDatabase::class.java, "reelmind.db").build().dao()
        androidx.lifecycle.viewModelScope.launch { dao.observe().collectLatest { reels = it } }
        val prefs = activity.getSharedPreferences("settings", 0)
        provider = prefs.getString("provider", "Gemini") ?: "Gemini"
        modelName = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
    }
    fun saveProvider(activity: ComponentActivity, value: String) { provider = value; activity.getSharedPreferences("settings", 0).edit().putString("provider", value).apply() }
    fun saveModel(activity: ComponentActivity, value: String) { modelName = value; activity.getSharedPreferences("settings", 0).edit().putString("model", value).apply() }
    fun add(url: String, onResult: (Boolean) -> Unit) {
        androidx.lifecycle.viewModelScope.launch { onResult(dao.insert(Reel(url = url)) != -1L) }
    }
    fun update(reel: Reel) { androidx.lifecycle.viewModelScope.launch { dao.update(reel) } }
    fun remove(reel: Reel) { androidx.lifecycle.viewModelScope.launch { dao.delete(reel) } }
}

class MainActivity : ComponentActivity() {
    private var shareHandler: ((String) -> Unit)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: ReelModel = viewModel()
            LaunchedEffect(vm) { vm.initialize(this@MainActivity); shareHandler = { vm.pendingUrl = it }; readShare(intent)?.let { vm.pendingUrl = it } }
            ReelMindScreen(vm, this)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readShare(intent)?.let { shareHandler?.invoke(it) } }
    private fun readShare(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("https?://(?:www\\.)?instagram\\.com/[^\\s?]+", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('/')
    }
}

private val purple = Color(0xFFB59BFF)
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReelMindScreen(vm: ReelModel, activity: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Reel?>(null) }
    var newUrl by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(feedback) { if (feedback.isNotEmpty()) { snack.showSnackbar(feedback); feedback = "" } }
    LaunchedEffect(vm.pendingUrl) { if (vm.pendingUrl != null) { newUrl = vm.pendingUrl!!; showAdd = true; vm.pendingUrl = null } }
    BackHandler(selected != null) { selected = null }
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, background = Color(0xFF111117), surface = Color(0xFF1C1B24))) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = { TopAppBar(title = { Text(if (selected == null) "ReelMind" else "Fiche enregistrée", fontWeight = FontWeight.Bold) }, navigationIcon = { if (selected != null) IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Retour") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111117))) }, bottomBar = { if (selected == null) NavigationBar { listOf(Triple("Bibliothèque", Icons.Default.CollectionsBookmark, 0), Triple("Favoris", Icons.Default.Favorite, 1), Triple("Réglages", Icons.Default.Settings, 2)).forEach { (name, icon, index) -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, name) }, label = { Text(name) }) } } }, floatingActionButton = { if (selected == null && tab != 2) FloatingActionButton(onClick = { newUrl = ""; showAdd = true }) { Icon(Icons.Default.Add, "Ajouter") } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (selected != null) {
                    val current = vm.reels.find { it.id == selected!!.id } ?: selected!!
                    Detail(current, onSave = { vm.update(it); selected = it }, onDelete = { vm.remove(current); selected = null })
                } else if (tab == 2) {
                    Text("Moteur IA", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Préférences enregistrées localement. La connexion aux API sera ajoutée dans une prochaine version.")
                    Spacer(Modifier.height(16.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(vm.provider, {}, readOnly = true, label = { Text("Fournisseur") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { listOf("Gemini", "Groq", "OpenAI", "Ollama").forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { vm.saveProvider(activity, item); expanded = false }) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(vm.modelName, { vm.saveModel(activity, it) }, label = { Text("Identifiant du modèle") }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(if (tab == 0) "Ta bibliothèque intelligente" else "Tes favoris", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(search, { search = it }, label = { Text("Rechercher un titre, une note, un tag…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    val shown = vm.reels.filter { (tab != 1 || it.favorite) && (it.title.contains(search, true) || it.summary.contains(search, true) || it.note.contains(search, true) || it.category.contains(search, true) || it.url.contains(search, true)) }
                    if (shown.isEmpty()) Text("Aucune publication. Partage un lien Instagram vers ReelMind ou appuie sur +.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(shown, key = { it.id }) { reel -> Card(onClick = { selected = reel }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(reel.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2); IconButton(onClick = { vm.update(reel.copy(favorite = !reel.favorite)) }) { Icon(if (reel.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (reel.favorite) purple else MaterialTheme.colorScheme.onSurfaceVariant) } }; Text(reel.category, color = purple); Spacer(Modifier.height(6.dp)); Text(reel.summary.ifBlank { "Enregistré · Résumé en attente" }, maxLines = 3, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(6.dp)); Text(reel.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } } } }
                }
            }
        }
        if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Enregistrer une publication") }, text = { Column { Text("Colle ou partage un lien Instagram. L'extraction automatique arrivera dans une prochaine version."); OutlinedTextField(newUrl, { newUrl = it }, label = { Text("URL Instagram") }, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { val clean = newUrl.trim().substringBefore('?').trimEnd('/'); if (!clean.startsWith("https://www.instagram.com/") && !clean.startsWith("https://instagram.com/")) { feedback = "Lien Instagram non reconnu" } else { vm.add(clean) { added -> feedback = if (added) "Publication enregistrée" else "Déjà enregistrée" }; showAdd = false } }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } })
    }
}
@Composable fun Detail(reel: Reel, onSave: (Reel) -> Unit, onDelete: () -> Unit) {
    var title by remember(reel.id) { mutableStateOf(reel.title) }
    var category by remember(reel.id) { mutableStateOf(reel.category) }
    var summary by remember(reel.id) { mutableStateOf(reel.summary) }
    var note by remember(reel.id) { mutableStateOf(reel.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(reel.url, color = purple)
        OutlinedTextField(title, { title = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(category, { category = it }, label = { Text("Catégorie / tags") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(summary, { summary = it }, label = { Text("Résumé (saisie manuelle pour V0.1)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Mes notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(reel.copy(title = title, category = category, summary = summary, note = note)) }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer les modifications") }
        TextButton(onClick = { confirmDelete = true }) { Text("Supprimer cette fiche") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Supprimer cette fiche ?") }, confirmButton = { TextButton(onClick = onDelete) { Text("Supprimer") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } })
}
