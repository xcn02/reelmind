package fr.reelmind.app

import android.content.Intent
import android.os.Bundle
import android.content.Context
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.*
import kotlinx.coroutines.delay
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
import android.speech.tts.TextToSpeech
import java.util.Locale

@Entity(tableName = "reels", indices = [Index(value = ["url"], unique = true)])
data class Reel(@PrimaryKey(autoGenerate = true) val id: Long = 0, val url: String, val title: String = "Publication Instagram", val summary: String = "", val sourceText: String = "", val note: String = "", val category: String = "À classer", val favorite: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val processingStatus: String = "waiting", val processingError: String = "", val mediaText: String = "")

@Dao interface ReelDao {
    @Query("SELECT * FROM reels ORDER BY createdAt DESC") fun observe(): Flow<List<Reel>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(reel: Reel): Long
    @Update suspend fun update(reel: Reel)
    @Delete suspend fun delete(reel: Reel)
    @Query("SELECT * FROM reels WHERE id = :id LIMIT 1") suspend fun byId(id: Long): Reel?
}
@Entity(tableName = "chat_messages", indices = [Index(value = ["reelId"])])
data class ChatMessage(@PrimaryKey(autoGenerate = true) val id: Long = 0, val reelId: Long,
    val role: String, val content: String, val createdAt: Long = System.currentTimeMillis())

@Dao interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE reelId = :reelId ORDER BY id ASC") fun observe(reelId: Long): Flow<List<ChatMessage>>
    @Insert suspend fun insert(message: ChatMessage)
    @Query("DELETE FROM chat_messages WHERE reelId = :reelId") suspend fun clear(reelId: Long)
}
@Database(entities = [Reel::class, ChatMessage::class], version = 4, exportSchema = false)
abstract class ReelDatabase : RoomDatabase() { abstract fun dao(): ReelDao; abstract fun chatDao(): ChatDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reels ADD COLUMN sourceText TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reelId` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_reelId` ON `chat_messages` (`reelId`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reels ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'waiting'")
        db.execSQL("ALTER TABLE reels ADD COLUMN processingError TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE reels ADD COLUMN mediaText TEXT NOT NULL DEFAULT ''")
    }
}

class ReelModel : ViewModel() {
    var reels by mutableStateOf<List<Reel>>(emptyList()); private set
    var pendingUrl by mutableStateOf<String?>(null)
    var pendingCaption by mutableStateOf("")
    var libraryAnswer by mutableStateOf(""); private set
    var libraryAsking by mutableStateOf(false); private set
    var provider by mutableStateOf("Gemini")
    var modelName by mutableStateOf("gemini-2.5-flash")
    var ollamaUrl by mutableStateOf("")
    var messages by mutableStateOf<List<ChatMessage>>(emptyList()); private set
    var chatAnswering by mutableStateOf(false); private set
    private var chatJob: kotlinx.coroutines.Job? = null
    private lateinit var chatDao: ChatDao
    private lateinit var dao: ReelDao
    private var initialized = false
    private lateinit var appContext: Context
    var autoProcess by mutableStateOf(true)
    var isLoading by mutableStateOf(true); private set
    var apiKey by mutableStateOf("")
    var processingId by mutableStateOf<Long?>(null)
    var aiError by mutableStateOf<String?>(null)
    fun initialize(activity: ComponentActivity) {
        if (initialized) return
        initialized = true
        appContext = activity.applicationContext
        // Preferences are lightweight; database creation/first observation happen off the UI thread.
        val prefs = activity.getSharedPreferences("settings", 0)
        provider = prefs.getString("provider", "Gemini") ?: "Gemini"
        modelName = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        apiKey = prefs.getString("key_$provider", prefs.getString("gemini_api_key", "")) ?: ""
        ollamaUrl = prefs.getString("ollama_url", "") ?: ""
        autoProcess = prefs.getBoolean("auto_process", true)
        viewModelScope.launch {
            try {
                val databaseDao = withContext(Dispatchers.IO) {
                    Room.databaseBuilder(activity.applicationContext, ReelDatabase::class.java, "reelmind.db")
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                }
                dao = databaseDao.dao()
                chatDao = databaseDao.chatDao()
                dao.observe().collectLatest { reels = it; isLoading = false }
            } catch (e: Exception) {
                isLoading = false
                aiError = "Ouverture de la bibliothèque impossible : ${e.message ?: "erreur inconnue"}"
            }
        }
    }
    fun saveProvider(activity: ComponentActivity, value: String) {
        provider = value
        val prefs = activity.getSharedPreferences("settings", 0)
        apiKey = prefs.getString("key_$value", "") ?: ""
        modelName = prefs.getString("model_$value", defaultModel(value)) ?: defaultModel(value)
        prefs.edit().putString("provider", value).apply()
    }
    private fun defaultModel(value: String) = when(value) {
        "OpenAI" -> "gpt-4o-mini"; "Groq" -> "llama-3.3-70b-versatile"
        "Ollama" -> "llama3.2"; else -> "gemini-2.5-flash"
    }
    fun saveModel(activity: ComponentActivity, value: String) { modelName = value; activity.getSharedPreferences("settings", 0).edit().putString("model_$provider", value).apply() }
    fun saveOllamaUrl(activity: ComponentActivity, value: String) {
        ollamaUrl = value; activity.getSharedPreferences("settings", 0).edit().putString("ollama_url", value).apply()
    }
    fun saveApiKey(activity: ComponentActivity, value: String) {
        apiKey = value
        activity.getSharedPreferences("settings", 0).edit().putString("key_$provider", value).apply()
    }
    fun summarize(reel: Reel) {
        if (!::dao.isInitialized) { aiError = "Bibliothèque en cours de chargement"; return }
        if (processingId != null) return
        if (reel.sourceText.isBlank()) { aiError = "Ajoute d'abord la légende ou la transcription dans la fiche."; return }
        processingId = reel.id
        aiError = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AiProviders.ask(provider, modelName, apiKey, ollamaUrl,
                        "Tu es un assistant francophone de veille personnelle. N'invente jamais de faits. " +
                            "Réponds en français, avec un titre court, un résumé clair, 3 à 5 points clés " +
                            "et 3 à 6 hashtags thématiques. Si le texte est insuffisant, dis-le.",
                        "Source : ${reel.url}\nTexte à résumer :\n${reel.sourceText}")
                }
                val extractedTags = Regex("#[\\p{L}0-9_-]+")
                    .findAll(result).map { it.value }.distinct().take(6).toList()
                val newCategory = if (reel.category == "À classer" && extractedTags.isNotEmpty())
                    extractedTags.joinToString(" ") else reel.category
                dao.update(reel.copy(summary = result, category = newCategory, processingStatus = "done", processingError = ""))
            } catch (e: Exception) {
                aiError = "Résumé impossible : ${e.message ?: "erreur inconnue"}"
            } finally { processingId = null }
        }
    }
    fun observeChat(reelId: Long) {
        if (!::chatDao.isInitialized) return
        chatJob?.cancel()
        messages = emptyList()
        chatJob = viewModelScope.launch { chatDao.observe(reelId).collectLatest { messages = it } }
    }
    fun askReel(reel: Reel, question: String) {
        if (!::chatDao.isInitialized || chatAnswering || question.isBlank()) return
        if (reel.sourceText.isBlank() && reel.summary.isBlank()) {
            aiError = "Ajoute un texte source ou un résumé pour discuter avec ce Reel"; return
        }
        chatAnswering = true
        viewModelScope.launch {
            try {
                chatDao.insert(ChatMessage(reelId = reel.id, role = "user", content = question))
                val context = messages.takeLast(12).joinToString("\n") { "${it.role}: ${it.content}" }
                val answer = withContext(Dispatchers.IO) {
                    AiProviders.ask(provider, modelName, apiKey, ollamaUrl,
                        "Tu réponds en français à des questions sur une publication enregistrée. " +
                            "Appuie-toi uniquement sur le contenu fourni, précise les limites et n'invente rien.",
                        "Titre : ${reel.title}\nTexte : ${reel.sourceText.take(20000)}\n" +
                            "Résumé : ${reel.summary.take(10000)}\nHistorique :\n$context\nQuestion : $question")
                }
                chatDao.insert(ChatMessage(reelId = reel.id, role = "assistant", content = answer))
            } catch (e: Exception) { aiError = "Discussion : ${e.message ?: "erreur réseau"}" }
            finally { chatAnswering = false }
        }
    }
    fun askLibrary(question: String) {
        if (libraryAsking || question.isBlank()) return
        val relevant = reels.filter { it.summary.isNotBlank() || it.sourceText.isNotBlank() }
        if (relevant.isEmpty()) { libraryAnswer = "Aucune fiche analysable dans la bibliothèque."; return }
        libraryAsking = true
        libraryAnswer = ""
        viewModelScope.launch {
            try {
                // Bounded context: it is a basic library question-answer feature, not vector search.
                val context = relevant.take(25).joinToString("\n\n") {
                    "ID ${it.id} | ${it.title} | ${it.url}\n${it.summary.ifBlank { it.sourceText }.take(1100)}"
                }
                libraryAnswer = withContext(Dispatchers.IO) {
                    AiProviders.ask(provider, modelName, apiKey, ollamaUrl,
                        "Réponds en français uniquement d'après les fiches fournies. Cite les titres " +
                        "ou URL des fiches utilisées. Explique si les résultats sont insuffisants.",
                        "Fiches enregistrées (maximum 25):\n$context\n\nQuestion : $question")
                }
            } catch (e: Exception) { libraryAnswer = "Erreur : ${e.message ?: "appel IA impossible"}" }
            finally { libraryAsking = false }
        }
    }
    fun requeueUnfinished() {
        reels.filter { it.summary.isBlank() && it.processingStatus != "summarizing" }.forEach { enqueue(it.id) }
    }
    fun saveAutoProcess(activity: ComponentActivity, enabled: Boolean) {
        autoProcess = enabled
        activity.getSharedPreferences("settings", 0).edit().putBoolean("auto_process", enabled).apply()
    }
    fun enqueue(reelId: Long) {
        ReelProcessing.enqueue(appContext, reelId)
    }
    fun retry(reel: Reel) {
        viewModelScope.launch {
            if (!::dao.isInitialized) return@launch
            dao.update(reel.copy(processingStatus = "waiting", processingError = ""))
            enqueue(reel.id)
        }
    }
    fun importMedia(reel: Reel, uri: Uri) {
        if (!::dao.isInitialized || processingId != null) return
        processingId = reel.id
        aiError = null
        viewModelScope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) {
                    MediaTranscriber.transcribe(appContext, uri, apiKey, provider)
                }
                val fresh = dao.byId(reel.id) ?: return@launch
                dao.update(fresh.copy(mediaText = transcript, sourceText =
                    listOf(fresh.sourceText, transcript).filter { it.isNotBlank() }.joinToString("\n\n"),
                    processingStatus = "waiting", processingError = ""))
                if (autoProcess) enqueue(reel.id)
            } catch (e: Exception) {
                aiError = "Transcription impossible : ${e.message ?: "erreur"}"
            } finally { processingId = null }
        }
    }
    fun addShared(url: String, caption: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            while (!::dao.isInitialized && isLoading) delay(40)
            if (!::dao.isInitialized) { onResult(false); return@launch }
            val insertedId = dao.insert(Reel(url = url, sourceText = caption))
            if (insertedId > 0L && autoProcess) enqueue(insertedId)
            onResult(insertedId > 0L)
        }
    }
    fun add(url: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            // Wait briefly for the database if the app was opened from Android's share sheet.
            while (!::dao.isInitialized && isLoading) kotlinx.coroutines.delay(40)
            if (!::dao.isInitialized) { onResult(false); return@launch }
            val id = dao.insert(Reel(url = url))
            if (id > 0L && autoProcess) enqueue(id)
            onResult(id > 0L)
        }
    }
    fun update(reel: Reel) { if (::dao.isInitialized) viewModelScope.launch { dao.update(reel) } }
    fun remove(reel: Reel) { if (::dao.isInitialized) viewModelScope.launch { if (::chatDao.isInitialized) chatDao.clear(reel.id); dao.delete(reel) } }
}

data class SharedReel(val url: String, val caption: String)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    fun speak(text: String) {
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) { tts?.language = Locale.FRENCH; tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reelmind") }
            }
        } else if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reelmind")
    }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        startActivity(Intent.createChooser(intent, "Exporter depuis ReelMind"))
    }
    fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { }
    }
    private var shareHandler: ((SharedReel) -> Unit)? = null
    private var mediaImportHandler: ((Uri) -> Unit)? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mediaImportHandler?.invoke(uri)
    }
    fun chooseMedia(onPicked: (Uri) -> Unit) {
        mediaImportHandler = onPicked
        mediaPicker.launch(arrayOf("audio/*", "video/*"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: ReelModel = viewModel()
            LaunchedEffect(vm) { vm.initialize(this@MainActivity); shareHandler = { vm.pendingUrl = it.url; vm.pendingCaption = it.caption }; readShare(intent)?.let { vm.pendingUrl = it.url; vm.pendingCaption = it.caption } }
            ReelMindScreen(vm, this)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readShare(intent)?.let { shareHandler?.invoke(it) } }
    private fun readShare(intent: Intent?): SharedReel? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val url = Regex("https?://(?:www\\.)?instagram\\.com/[^\\s]+", RegexOption.IGNORE_CASE)
            .find(text)?.value?.substringBefore('?')?.trimEnd('/') ?: return null
        val caption = text.replace(Regex("https?://[^\\s]+"), "").trim().take(20000)
        return SharedReel(url, caption)
    }

}

private val purple = Color(0xFFB59BFF)
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReelMindScreen(vm: ReelModel, activity: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("Tout") }
    var selected by remember { mutableStateOf<Reel?>(null) }
    var newUrl by remember { mutableStateOf("") }
    var newCaption by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var showLibraryChat by remember { mutableStateOf(false) }
    var libraryQuestion by remember { mutableStateOf("") }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.aiError) { vm.aiError?.let { snack.showSnackbar(it); vm.aiError = null } }
    LaunchedEffect(feedback) { if (feedback.isNotEmpty()) { snack.showSnackbar(feedback); feedback = "" } }
    LaunchedEffect(vm.pendingUrl) { if (vm.pendingUrl != null) { newUrl = vm.pendingUrl!!; newCaption = vm.pendingCaption; showAdd = true; vm.pendingUrl = null; vm.pendingCaption = "" } }
    BackHandler(selected != null) { selected = null }
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, background = Color(0xFF111117), surface = Color(0xFF1C1B24))) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = { TopAppBar(title = { Text(if (selected == null) "ReelMind" else "Fiche enregistrée", fontWeight = FontWeight.Bold) }, navigationIcon = { if (selected != null) IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Retour") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111117))) }, bottomBar = { if (selected == null) NavigationBar { listOf(Triple("Bibliothèque", Icons.Default.CollectionsBookmark, 0), Triple("Favoris", Icons.Default.Favorite, 1), Triple("Réglages", Icons.Default.Settings, 2)).forEach { (name, icon, index) -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, name) }, label = { Text(name) }) } } }, floatingActionButton = { if (selected == null && tab != 2) FloatingActionButton(onClick = { newUrl = ""; newCaption = ""; showAdd = true }) { Icon(Icons.Default.Add, "Ajouter") } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (selected != null) {
                    val current = vm.reels.find { it.id == selected!!.id } ?: selected!!
                    Detail(current, onSave = { vm.update(it); selected = it }, onDelete = { vm.remove(current); selected = null },
                        onSummarize = { vm.summarize(it) }, isProcessing = vm.processingId == current.id,
                        onExport = { activity.shareText(exportMarkdown(current)) }, onOpen = { activity.openUrl(current.url) },
                        onSpeak = { activity.speak(current.summary) }, onImportMedia = { activity.chooseMedia { uri -> vm.importMedia(current, uri) } },
                        onRetry = { vm.retry(current) }, messages = vm.messages,
                        onChatOpen = { vm.observeChat(current.id) }, onAsk = { vm.askReel(current, it) }, chatAnswering = vm.chatAnswering)
                } else if (tab == 2) {
                    Text("Moteur IA", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Le partage déclenche une tentative d’extraction publique. Si Instagram bloque l’accès, importe la légende ou un fichier audio/vidéo. Aucun contournement de connexion.")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Traitement automatique", modifier = Modifier.weight(1f))
                        Switch(checked = vm.autoProcess, onCheckedChange = { vm.saveAutoProcess(activity, it) })
                    }
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
                    if (vm.provider != "Ollama") OutlinedTextField(vm.apiKey, { vm.saveApiKey(activity, it) }, label = { Text("Clé API ${vm.provider}") },
                        visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Masquer" else "Voir") } },
                        modifier = Modifier.fillMaxWidth())
                    if (vm.provider == "Ollama") {
                        OutlinedTextField(vm.ollamaUrl, { vm.saveOllamaUrl(activity, it) },
                            label = { Text("Adresse HTTPS du serveur Ollama") }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("La transcription de fichiers utilise actuellement Gemini et nécessite une clé Gemini sélectionnée. Les fichiers audio/vidéo sont envoyés à Gemini seulement sur action explicite.", style = MaterialTheme.typography.bodySmall)
                    Text("Les clés API sont stockées localement sans chiffrement dédié. Ne les partage pas. " +
                        "Les appels IA envoient le texte de tes fiches au fournisseur choisi.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { activity.requestNotifications() }, modifier = Modifier.fillMaxWidth()) { Text("Activer les notifications") }
                    OutlinedButton(onClick = { vm.requeueUnfinished() }, modifier = Modifier.fillMaxWidth()) { Text("Retenter les fiches sans résumé") }
                    OutlinedButton(onClick = { activity.shareText(exportLibraryMarkdown(vm.reels)) }, modifier = Modifier.fillMaxWidth()) { Text("Exporter toute la bibliothèque (Markdown)") }
                } else {
                    Text(if (tab == 0) "Ta bibliothèque intelligente" else "Tes favoris", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showLibraryChat = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Poser une question sur ma bibliothèque")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(search, { search = it }, label = { Text("Rechercher un titre, une note, un tag…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    val categories = vm.reels.map { it.category }.filter { it.isNotBlank() }
                        .distinct().take(12)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("Tout") + categories) { cat ->
                            FilterChip(selected = categoryFilter == cat, onClick = { categoryFilter = cat },
                                label = { Text(cat, maxLines = 1) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val shown = vm.reels.filter {
                        (tab != 1 || it.favorite) && (categoryFilter == "Tout" || it.category == categoryFilter) &&
                            (it.title.contains(search, true) || it.summary.contains(search, true) ||
                            it.note.contains(search, true) || it.category.contains(search, true) ||
                            it.sourceText.contains(search, true) || it.url.contains(search, true))
                    }
                    if (vm.isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Chargement de la bibliothèque…")
                        }
                    } else if (shown.isEmpty()) Text("Aucune publication. Partage un lien Instagram vers ReelMind ou appuie sur +.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(shown, key = { it.id }) { reel -> Card(onClick = { selected = reel }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(reel.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2); IconButton(onClick = { vm.update(reel.copy(favorite = !reel.favorite)) }) { Icon(if (reel.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (reel.favorite) purple else MaterialTheme.colorScheme.onSurfaceVariant) } }; Text(reel.category, color = purple); Spacer(Modifier.height(6.dp)); Text(reel.summary.ifBlank { when(reel.processingStatus) { "blocked" -> "Contenu inaccessible : ajouter un texte ou fichier"; "extracting" -> "Récupération en cours…"; "summarizing" -> "Résumé en cours…"; "failed" -> "Échec du traitement"; else -> "Enregistré · Résumé en attente" } }, maxLines = 3, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(6.dp)); Text(reel.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } } } }
                }
            }
        }
        if (showLibraryChat) AlertDialog(onDismissRequest = { showLibraryChat = false },
            title = { Text("Question à la bibliothèque") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Interroge jusqu'à 25 fiches avec texte ou résumé. Leur contenu est envoyé au fournisseur IA choisi.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(libraryQuestion, { libraryQuestion = it }, label = { Text("Ta question") }, modifier = Modifier.fillMaxWidth())
                if (vm.libraryAsking) CircularProgressIndicator(Modifier.size(24.dp))
                if (vm.libraryAnswer.isNotBlank()) Text(vm.libraryAnswer, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
            } },
            confirmButton = { TextButton(onClick = { vm.askLibrary(libraryQuestion) }, enabled = libraryQuestion.isNotBlank() && !vm.libraryAsking) { Text("Interroger") } },
            dismissButton = { TextButton(onClick = { showLibraryChat = false }) { Text("Fermer") } })
        if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Enregistrer une publication") }, text = { Column { Text("Partage un lien Instagram : ReelMind tentera une extraction publique. Si elle échoue, ajoute la légende ou un fichier média dans la fiche."); OutlinedTextField(newUrl, { newUrl = it }, label = { Text("URL Instagram") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(newCaption, { newCaption = it }, label = { Text("Légende (facultatif)") }, modifier = Modifier.fillMaxWidth(), minLines = 2) } }, confirmButton = { TextButton(onClick = { val clean = newUrl.trim().substringBefore('?').trimEnd('/'); if (!clean.startsWith("https://www.instagram.com/") && !clean.startsWith("https://instagram.com/")) { feedback = "Lien Instagram non reconnu" } else { vm.addShared(clean, newCaption) { added -> feedback = if (added) "Publication enregistrée" else "Déjà enregistrée" }; showAdd = false } }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } })
    }
}
@Composable fun Detail(reel: Reel, onSave: (Reel) -> Unit, onDelete: () -> Unit,
    onSummarize: (Reel) -> Unit, isProcessing: Boolean, onExport: () -> Unit, onOpen: () -> Unit,
    onSpeak: () -> Unit, onImportMedia: () -> Unit, onRetry: () -> Unit, messages: List<ChatMessage>, onChatOpen: () -> Unit,
    onAsk: (String) -> Unit, chatAnswering: Boolean) {
    var title by remember(reel.id) { mutableStateOf(reel.title) }
    var category by remember(reel.id) { mutableStateOf(reel.category) }
    var summary by remember(reel.id, reel.summary) { mutableStateOf(reel.summary) }
    var sourceText by remember(reel.id, reel.sourceText) { mutableStateOf(reel.sourceText) }
    var note by remember(reel.id) { mutableStateOf(reel.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    var chatExpanded by remember(reel.id) { mutableStateOf(false) }
    var question by remember(reel.id) { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(reel.url, color = purple)
        val stateText = when(reel.processingStatus) {
            "done" -> "Résumé prêt"
            "extracting" -> "Récupération du contenu…"
            "summarizing" -> "Analyse IA en cours…"
            "blocked" -> "Contenu non accessible : texte ou fichier nécessaire"
            "failed" -> "Traitement interrompu"
            else -> "À traiter"
        }
        Text(stateText, color = if (reel.processingStatus == "done") Color(0xFF85CFB0) else purple)
        if (reel.processingError.isNotBlank()) Text(reel.processingError, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry) { Text("Relancer") }
            OutlinedButton(onClick = onImportMedia) { Text("Importer audio/vidéo") }
        }
        if (reel.mediaText.isNotBlank()) Text("Transcription enregistrée (${reel.mediaText.length} caractères)", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onOpen) { Text("Ouvrir dans Instagram / navigateur") }
        OutlinedTextField(title, { title = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(category, { category = it }, label = { Text("Catégorie / tags") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourceText, { sourceText = it }, label = { Text("Texte source : légende ou transcription") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val updated = reel.copy(title = title, category = category, summary = summary, sourceText = sourceText, note = note)
            onSave(updated)
            onSummarize(updated)
        }, enabled = !isProcessing && sourceText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (isProcessing) "Résumé en cours…" else "Générer le résumé IA")
        }
        OutlinedTextField(summary, { summary = it }, label = { Text("Résumé") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Mes notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(reel.copy(title = title, category = category, summary = summary, sourceText = sourceText, note = note)) }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer les modifications") }
        if (reel.summary.isNotBlank()) {
            OutlinedButton(onClick = onSpeak, modifier = Modifier.fillMaxWidth()) { Text("Écouter le résumé (voix Android)") }
        }
        OutlinedButton(onClick = { chatExpanded = !chatExpanded; if (chatExpanded) onChatOpen() },
            modifier = Modifier.fillMaxWidth()) { Text(if (chatExpanded) "Masquer la discussion" else "Discuter avec ce Reel") }
        if (chatExpanded) {
            Text("Questions/réponses conservées localement", style = MaterialTheme.typography.labelMedium)
            messages.forEach { msg ->
                Surface(color = if (msg.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (msg.role == "user") "Vous" else "ReelMind IA", fontWeight = FontWeight.Bold)
                        Text(msg.content)
                    }
                }
            }
            OutlinedTextField(question, { question = it }, label = { Text("Poser une question sur ce Reel") },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(onClick = { onAsk(question.trim()); question = "" },
                enabled = question.isNotBlank() && !chatAnswering, modifier = Modifier.fillMaxWidth()) {
                Text(if (chatAnswering) "Réponse en cours…" else "Envoyer la question")
            }
        }
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
