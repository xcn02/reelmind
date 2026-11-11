package fr.reelmind.app

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.content.ActivityNotFoundException
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

@Entity(tableName = "reels", indices = [Index(value = ["url"], unique = true)])
data class Reel(@PrimaryKey(autoGenerate = true) val id: Long = 0, val url: String, val title: String = "Publication Instagram", val summary: String = "", val sourceText: String = "", val note: String = "", val category: String = "À classer", val favorite: Boolean = false, val createdAt: Long = System.currentTimeMillis())

@Dao interface ReelDao {
    @Query("SELECT * FROM reels ORDER BY createdAt DESC") fun observe(): Flow<List<Reel>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(reel: Reel): Long
    @Update suspend fun update(reel: Reel)
    @Delete suspend fun delete(reel: Reel)
}
@Database(entities = [Reel::class], version = 2, exportSchema = false)
abstract class ReelDatabase : RoomDatabase() { abstract fun dao(): ReelDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reels ADD COLUMN sourceText TEXT NOT NULL DEFAULT ''")
    }
}

class ReelModel : ViewModel() {
    var reels by mutableStateOf<List<Reel>>(emptyList()); private set
    var pendingUrl by mutableStateOf<String?>(null)
    var provider by mutableStateOf("Gemini")
    var modelName by mutableStateOf("gemini-2.5-flash")
    private lateinit var dao: ReelDao
    private var initialized = false
    var isLoading by mutableStateOf(true); private set
    var apiKey by mutableStateOf("")
    var processingId by mutableStateOf<Long?>(null)
    var aiError by mutableStateOf<String?>(null)
    fun initialize(activity: ComponentActivity) {
        if (initialized) return
        initialized = true
        // Preferences are lightweight; database creation/first observation happen off the UI thread.
        val prefs = activity.getSharedPreferences("settings", 0)
        provider = prefs.getString("provider", "Gemini") ?: "Gemini"
        modelName = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        apiKey = prefs.getString("gemini_api_key", "") ?: ""
        viewModelScope.launch {
            try {
                val databaseDao = withContext(Dispatchers.IO) {
                    Room.databaseBuilder(activity.applicationContext, ReelDatabase::class.java, "reelmind.db")
                        .addMigrations(MIGRATION_1_2).build().dao()
                }
                dao = databaseDao
                dao.observe().collectLatest { reels = it; isLoading = false }
            } catch (e: Exception) {
                isLoading = false
                aiError = "Ouverture de la bibliothèque impossible : ${e.message ?: "erreur inconnue"}"
            }
        }
    }
    fun saveProvider(activity: ComponentActivity, value: String) { provider = value; activity.getSharedPreferences("settings", 0).edit().putString("provider", value).apply() }
    fun saveModel(activity: ComponentActivity, value: String) { modelName = value; activity.getSharedPreferences("settings", 0).edit().putString("model", value).apply() }
    fun saveApiKey(activity: ComponentActivity, value: String) {
        apiKey = value
        activity.getSharedPreferences("settings", 0).edit().putString("gemini_api_key", value).apply()
    }
    fun summarize(reel: Reel) {
        if (!::dao.isInitialized) { aiError = "Bibliothèque en cours de chargement"; return }
        if (processingId != null) return
        if (provider != "Gemini") { aiError = "Seul Gemini est connecté dans cette version."; return }
        if (apiKey.isBlank()) { aiError = "Renseigne ta clé Gemini dans Réglages."; return }
        if (reel.sourceText.isBlank()) { aiError = "Ajoute d'abord la légende ou la transcription dans la fiche."; return }
        processingId = reel.id
        aiError = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { GeminiClient.summarize(apiKey, modelName, reel.sourceText) }
                dao.update(reel.copy(summary = result))
            } catch (e: Exception) {
                aiError = "Résumé impossible : ${e.message ?: "erreur inconnue"}"
            } finally { processingId = null }
        }
    }
    fun add(url: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            // Wait briefly for the database if the app was opened from Android's share sheet.
            while (!::dao.isInitialized && isLoading) kotlinx.coroutines.delay(40)
            if (!::dao.isInitialized) { onResult(false); return@launch }
            onResult(dao.insert(Reel(url = url)) != -1L)
        }
    }
    fun update(reel: Reel) { if (::dao.isInitialized) viewModelScope.launch { dao.update(reel) } }
    fun remove(reel: Reel) { if (::dao.isInitialized) viewModelScope.launch { dao.delete(reel) } }
}

object GeminiClient {
    fun summarize(key: String, model: String, content: String): String {
        require(model.matches(Regex("[a-zA-Z0-9._-]+"))) { "Identifiant du modèle invalide" }
        val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("x-goog-api-key", key)
            val prompt = """Tu es un assistant de veille. Résume en français le contenu suivant, sans inventer d'informations. Structure : un titre court, un résumé de 5 à 8 phrases, 3 à 5 points clés, puis 3 à 6 tags précédés de #. Si la source est insuffisante, indique-le. Contenu :
$content"""
            val body = JSONObject().put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))
            )).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "Réponse vide"
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code")
                throw IllegalStateException(message.take(250))
            }
            val candidates = JSONObject(response).optJSONArray("candidates")
                ?: throw IllegalStateException("Aucune réponse du modèle")
            val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val text = (0 until parts.length()).joinToString("\n") { parts.getJSONObject(it).optString("text") }.trim()
            require(text.isNotEmpty()) { "Le modèle a renvoyé un résumé vide" }
            return text
        } finally { connection.disconnect() }
    }
}

class MainActivity : ComponentActivity() {
    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        startActivity(Intent.createChooser(intent, "Exporter depuis ReelMind"))
    }
    fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { }
    }
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

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.aiError) { vm.aiError?.let { snack.showSnackbar(it); vm.aiError = null } }
    LaunchedEffect(feedback) { if (feedback.isNotEmpty()) { snack.showSnackbar(feedback); feedback = "" } }
    LaunchedEffect(vm.pendingUrl) { if (vm.pendingUrl != null) { newUrl = vm.pendingUrl!!; showAdd = true; vm.pendingUrl = null } }
    BackHandler(selected != null) { selected = null }
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, background = Color(0xFF111117), surface = Color(0xFF1C1B24))) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = { TopAppBar(title = { Text(if (selected == null) "ReelMind" else "Fiche enregistrée", fontWeight = FontWeight.Bold) }, navigationIcon = { if (selected != null) IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Retour") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111117))) }, bottomBar = { if (selected == null) NavigationBar { listOf(Triple("Bibliothèque", Icons.Default.CollectionsBookmark, 0), Triple("Favoris", Icons.Default.Favorite, 1), Triple("Réglages", Icons.Default.Settings, 2)).forEach { (name, icon, index) -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, name) }, label = { Text(name) }) } } }, floatingActionButton = { if (selected == null && tab != 2) FloatingActionButton(onClick = { newUrl = ""; showAdd = true }) { Icon(Icons.Default.Add, "Ajouter") } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (selected != null) {
                    val current = vm.reels.find { it.id == selected!!.id } ?: selected!!
                    Detail(current, onSave = { vm.update(it); selected = it }, onDelete = { vm.remove(current); selected = null },
                        onSummarize = { vm.summarize(it) }, isProcessing = vm.processingId == current.id,
                        onExport = { activity.shareText(exportMarkdown(current)) }, onOpen = { activity.openUrl(current.url) })
                } else if (tab == 2) {
                    Text("Moteur IA", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Gemini peut résumer une légende ou une transcription que tu colles dans une fiche. L'extraction automatique Instagram n'est pas encore disponible.")
                    Spacer(Modifier.height(16.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(vm.provider, {}, readOnly = true, label = { Text("Fournisseur") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { listOf("Gemini", "Groq", "OpenAI", "Ollama").forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { vm.saveProvider(activity, item); expanded = false }) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(vm.modelName, { vm.saveModel(activity, it) }, label = { Text("Identifiant du modèle") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    var showKey by remember { mutableStateOf(false) }
                    OutlinedTextField(vm.apiKey, { vm.saveApiKey(activity, it) }, label = { Text("Clé API Gemini") },
                        visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Masquer" else "Voir") } },
                        modifier = Modifier.fillMaxWidth())
                    Text("Attention : la clé est conservée dans les préférences locales de l'application, sans chiffrement dédié. Ne partage pas ton APK avec une clé intégrée.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { activity.shareText(exportLibraryMarkdown(vm.reels)) }, modifier = Modifier.fillMaxWidth()) { Text("Exporter toute la bibliothèque (Markdown)") }
                } else {
                    Text(if (tab == 0) "Ta bibliothèque intelligente" else "Tes favoris", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(search, { search = it }, label = { Text("Rechercher un titre, une note, un tag…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    val shown = vm.reels.filter { (tab != 1 || it.favorite) && (it.title.contains(search, true) || it.summary.contains(search, true) || it.note.contains(search, true) || it.category.contains(search, true) || it.url.contains(search, true)) }
                    if (vm.isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Chargement de la bibliothèque…")
                        }
                    } else if (shown.isEmpty()) Text("Aucune publication. Partage un lien Instagram vers ReelMind ou appuie sur +.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(shown, key = { it.id }) { reel -> Card(onClick = { selected = reel }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(reel.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2); IconButton(onClick = { vm.update(reel.copy(favorite = !reel.favorite)) }) { Icon(if (reel.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (reel.favorite) purple else MaterialTheme.colorScheme.onSurfaceVariant) } }; Text(reel.category, color = purple); Spacer(Modifier.height(6.dp)); Text(reel.summary.ifBlank { "Enregistré · Résumé en attente" }, maxLines = 3, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(6.dp)); Text(reel.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } } } }
                }
            }
        }
        if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Enregistrer une publication") }, text = { Column { Text("Colle ou partage un lien Instagram. Ajoute ensuite le texte de la publication dans sa fiche pour obtenir un résumé IA."); OutlinedTextField(newUrl, { newUrl = it }, label = { Text("URL Instagram") }, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton(onClick = { val clean = newUrl.trim().substringBefore('?').trimEnd('/'); if (!clean.startsWith("https://www.instagram.com/") && !clean.startsWith("https://instagram.com/")) { feedback = "Lien Instagram non reconnu" } else { vm.add(clean) { added -> feedback = if (added) "Publication enregistrée" else "Déjà enregistrée" }; showAdd = false } }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } })
    }
}
@Composable fun Detail(reel: Reel, onSave: (Reel) -> Unit, onDelete: () -> Unit,
    onSummarize: (Reel) -> Unit, isProcessing: Boolean, onExport: () -> Unit, onOpen: () -> Unit) {
    var title by remember(reel.id) { mutableStateOf(reel.title) }
    var category by remember(reel.id) { mutableStateOf(reel.category) }
    var summary by remember(reel.id, reel.summary) { mutableStateOf(reel.summary) }
    var sourceText by remember(reel.id, reel.sourceText) { mutableStateOf(reel.sourceText) }
    var note by remember(reel.id) { mutableStateOf(reel.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(reel.url, color = purple)
        TextButton(onClick = onOpen) { Text("Ouvrir dans Instagram / navigateur") }
        OutlinedTextField(title, { title = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(category, { category = it }, label = { Text("Catégorie / tags") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourceText, { sourceText = it }, label = { Text("Texte source : légende ou transcription") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val updated = reel.copy(title = title, category = category, summary = summary, sourceText = sourceText, note = note)
            onSave(updated)
            onSummarize(updated)
        }, enabled = !isProcessing && sourceText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (isProcessing) "Résumé en cours…" else "Générer un résumé IA (Gemini)")
        }
        OutlinedTextField(summary, { summary = it }, label = { Text("Résumé") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Mes notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(reel.copy(title = title, category = category, summary = summary, sourceText = sourceText, note = note)) }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer les modifications") }
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Partager / exporter la fiche (Markdown)") }
        TextButton(onClick = { confirmDelete = true }) { Text("Supprimer cette fiche") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Supprimer cette fiche ?") }, confirmButton = { TextButton(onClick = onDelete) { Text("Supprimer") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } })
}

fun exportMarkdown(reel: Reel): String = buildString {
    appendLine("# ${reel.title}")
    appendLine()
    appendLine("Source : ${reel.url}")
    appendLine("Catégorie : ${reel.category}")
    appendLine()
    appendLine("## Résumé")
    appendLine(reel.summary.ifBlank { "Pas encore de résumé." })
    appendLine()
    appendLine("## Texte source")
    appendLine(reel.sourceText)
    appendLine()
    appendLine("## Notes")
    appendLine(reel.note)
}

fun exportLibraryMarkdown(reels: List<Reel>): String =
    "# Bibliothèque ReelMind\n\n" + reels.joinToString("\n---\n\n") { exportMarkdown(it) }
