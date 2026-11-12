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
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

@Entity(tableName = "reels", indices = [Index(value = ["url"], unique = true)])
data class Reel(@PrimaryKey(autoGenerate = true) val id: Long = 0, val url: String, val title: String = "Publication Instagram", val summary: String = "", val sourceText: String = "", val note: String = "", val category: String = "À classer", val favorite: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val processingStatus: String = "waiting", val processingError: String = "", val mediaText: String = "", val author: String = "", val thumbnailUrl: String = "", val mediaKind: String = "", val originalCaption: String = "", val manualTitle: Boolean = false, val extractionDiagnostic: String = "")

@Dao interface ReelDao {
    @Query("SELECT * FROM reels ORDER BY createdAt DESC") fun observe(): Flow<List<Reel>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(reel: Reel): Long
    @Update suspend fun update(reel: Reel)
    @Delete suspend fun delete(reel: Reel)
    @Query("SELECT * FROM reels WHERE id = :id LIMIT 1") suspend fun byId(id: Long): Reel?
    // Update only fields changed by the user; keep concurrent worker updates intact.
    @Query("UPDATE reels SET title = CASE WHEN :changeTitle THEN :title ELSE title END, " +
        "note = CASE WHEN :changeNote THEN :note ELSE note END, " +
        "category = CASE WHEN :changeCategory THEN :category ELSE category END, " +
        "sourceText = CASE WHEN :changeSource THEN :sourceText ELSE sourceText END, " +
        "manualTitle = CASE WHEN :changeTitle THEN 1 ELSE manualTitle END WHERE id = :id")
    suspend fun saveEditable(id: Long, title: String, changeTitle: Boolean, note: String, changeNote: Boolean,
        category: String, changeCategory: Boolean, sourceText: String, changeSource: Boolean)
    @Query("UPDATE reels SET summary = :summary WHERE id = :id") suspend fun updateSummary(id: Long, summary: String)
    @Query("UPDATE reels SET category = :newName WHERE category = :oldName") suspend fun renameCategory(oldName: String, newName: String)

}
@Entity(tableName = "chat_messages", indices = [Index(value = ["reelId"])])
data class ChatMessage(@PrimaryKey(autoGenerate = true) val id: Long = 0, val reelId: Long,
    val role: String, val content: String, val createdAt: Long = System.currentTimeMillis())

@Dao interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE reelId = :reelId ORDER BY id ASC") fun observe(reelId: Long): Flow<List<ChatMessage>>
    @Insert suspend fun insert(message: ChatMessage)
    @Query("DELETE FROM chat_messages WHERE reelId = :reelId") suspend fun clear(reelId: Long)
}
@Database(entities = [Reel::class, ChatMessage::class], version = 6, exportSchema = false)
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

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reels ADD COLUMN author TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE reels ADD COLUMN thumbnailUrl TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE reels ADD COLUMN mediaKind TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE reels ADD COLUMN originalCaption TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reels ADD COLUMN manualTitle INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE reels ADD COLUMN extractionDiagnostic TEXT NOT NULL DEFAULT ''")
    }
}

class ReelModel : ViewModel() {
    var reels by mutableStateOf<List<Reel>>(emptyList()); private set
    var pendingUrl by mutableStateOf<String?>(null)
    var pendingCaption by mutableStateOf("")
    var libraryAnswer by mutableStateOf(""); private set
    var libraryAsking by mutableStateOf(false); private set
    var provider by mutableStateOf("Gemini")
    var modelName by mutableStateOf("gemini-3.5-flash-lite")
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
        modelName = prefs.getString("model_$provider", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
        if (modelName == "gemini-2.5-flash" && provider == "Gemini") {
            modelName = "gemini-3.5-flash-lite"
            prefs.edit().putString("model_Gemini", modelName).apply()
        }
        apiKey = prefs.getString("key_$provider", prefs.getString("gemini_api_key", "")) ?: ""
        ollamaUrl = prefs.getString("ollama_url", "") ?: ""
        autoProcess = prefs.getBoolean("auto_process", true)
        viewModelScope.launch {
            try {
                val databaseDao = withContext(Dispatchers.IO) {
                    Room.databaseBuilder(activity.applicationContext, ReelDatabase::class.java, "reelmind.db")
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
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
        if (value == "Gemini" && modelName == "gemini-2.5-flash") {
            modelName = defaultModel(value)
            prefs.edit().putString("model_Gemini", modelName).apply()
        }
        prefs.edit().putString("provider", value).apply()
    }
    private fun defaultModel(value: String) = when(value) {
        "OpenAI" -> "gpt-4o-mini"; "Groq" -> "llama-3.3-70b-versatile"
        "Ollama" -> "llama3.2"; else -> "gemini-3.5-flash-lite"
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
                            "Réponds en français, avec une première ligne Titre: <titre court de 60 caractères max>, puis un résumé clair, 3 à 5 points clés " +
                            "et 3 à 6 hashtags thématiques. Termine par Catégorie: <nom> parmi ${CategoryStore.list(appContext).joinToString(", " ) { it.name }}. Si le texte est insuffisant, dis-le.",
                        "Source : ${reel.url}\nTexte à résumer :\n${reel.sourceText}")
                }
                val extractedTags = Regex("#[\\p{L}0-9_-]+")
                    .findAll(result).map { it.value }.distinct().take(6).toList()
                val newCategory = if (reel.category == "À classer") CategoryStore.classifyFromAnswer(result, appContext, result + " " + reel.sourceText) else reel.category
                val fresh = dao.byId(reel.id) ?: return@launch
                dao.update(fresh.copy(summary = AiTitle.summaryWithoutTitle(result),
                    title = if (fresh.manualTitle) fresh.title else AiTitle.parse(result).ifBlank { fresh.title },
                    category = if (fresh.category == "À classer") newCategory else fresh.category,
                    processingStatus = "done", processingError = ""))
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
                    if (appContext.contentResolver.getType(uri)?.startsWith("image/") == true) {
                        val file = java.io.File.createTempFile("reelmind_upload_", ".jpg", appContext.cacheDir)
                        try {
                            appContext.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                            val key = appContext.getSharedPreferences("settings", 0).getString("key_Gemini", "").orEmpty()
                            "Analyse d'image :\n" + GeminiImages.describe(listOf(file), key, appContext.getSharedPreferences("settings", 0).getString("gemini_image_model", "gemini-3.5-flash-lite").orEmpty())
                        } finally { file.delete() }
                    } else {
                        val groqKey = appContext.getSharedPreferences("settings", 0).getString("key_Groq", "").orEmpty()
                        val mime = appContext.contentResolver.getType(uri).orEmpty()
                        if (mime.startsWith("audio/") && appContext.getSharedPreferences("settings", 0).getString("ex_audio_provider", "Groq") == "Groq" && groqKey.isNotBlank()) {
                            val ext = if (mime.contains("mpeg") || mime.contains("mp3")) ".mp3" else ".m4a"
                            val file = java.io.File.createTempFile("reelmind_upload_", ext, appContext.cacheDir)
                            try {
                                appContext.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                                GroqWhisper.transcribe(file, groqKey)
                            } finally { file.delete() }
                        } else MediaTranscriber.transcribe(appContext, uri, appContext.getSharedPreferences("settings", 0).getString("key_Gemini", "").orEmpty(), "Gemini", appContext.getSharedPreferences("settings", 0).getString("gemini_audio_model", "gemini-3.5-transcribe").orEmpty())
                    }
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
            val insertedId = dao.insert(Reel(url = url, sourceText = caption, originalCaption = caption))
            if (insertedId > 0L) enqueue(insertedId)
            onResult(insertedId > 0L)
        }
    }
    fun add(url: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            // Wait briefly for the database if the app was opened from Android's share sheet.
            while (!::dao.isInitialized && isLoading) kotlinx.coroutines.delay(40)
            if (!::dao.isInitialized) { onResult(false); return@launch }
            val id = dao.insert(Reel(url = url))
            if (id > 0L) enqueue(id)
            onResult(id > 0L)
        }
    }
    fun saveEditable(reel: Reel, original: Reel) {
        if (!::dao.isInitialized) return
        viewModelScope.launch {
            dao.saveEditable(reel.id,
                reel.title, reel.title != original.title,
                reel.note, reel.note != original.note,
                reel.category, reel.category != original.category,
                reel.sourceText, reel.sourceText != original.sourceText)
        }
    }
    fun saveSummary(reel: Reel, summary: String) {
        if (!::dao.isInitialized) return
        viewModelScope.launch { dao.updateSummary(reel.id, summary) }
    }
    fun changeCategory(oldName: String, newName: String) {
        if (!::dao.isInitialized) return
        viewModelScope.launch { dao.renameCategory(oldName, newName) }
    }
    fun downloadVideo(reel: Reel, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { VideoDownload.save(appContext, reel.url) }
                    .fold(onSuccess = { "Vidéo enregistrée : $it" }, onFailure = { "Téléchargement impossible : ${it.message}" })
            }
            onResult(message)
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
    fun chooseMedia(mime: String, onPicked: (Uri) -> Unit) {
        mediaImportHandler = onPicked
        mediaPicker.launch(arrayOf(mime))
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
