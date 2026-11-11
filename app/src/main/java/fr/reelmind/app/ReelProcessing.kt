package fr.reelmind.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** WorkManager persists work beyond the lifetime of the UI. No Instagram credentials are used. */
object ReelProcessing {
    fun enqueue(context: Context, id: Long) {
        val request = OneTimeWorkRequestBuilder<ReelWorker>()
            .setInputData(Data.Builder().putLong("reel_id", id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reel_$id", ExistingWorkPolicy.REPLACE, request
        )
    }
}

class ReelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong("reel_id", -1L)
        if (id < 0) return@withContext Result.failure()
        val db = Room.databaseBuilder(applicationContext, ReelDatabase::class.java, "reelmind.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        try {
            val dao = db.dao()
            var reel = dao.byId(id) ?: return@withContext Result.success()
            if (reel.sourceText.isBlank()) {
                dao.update(reel.copy(processingStatus = "extracting", processingError = ""))
                val extracted = runCatching { InstagramPublicMetadata.read(reel.url) }.getOrNull().orEmpty()
                reel = dao.byId(id) ?: return@withContext Result.success()
                if (extracted.isBlank()) {
                    dao.update(reel.copy(processingStatus = "blocked", processingError =
                        "Instagram ne fournit pas de texte public exploitable. Colle la légende ou importe un fichier."))
                    return@withContext Result.success()
                }
                reel = reel.copy(sourceText = extracted)
                dao.update(reel)
            }
            val prefs = applicationContext.getSharedPreferences("settings", 0)
            if (!prefs.getBoolean("auto_process", true)) return@withContext Result.success()
            val provider = prefs.getString("provider", "Gemini") ?: "Gemini"
            val defaultModel = when (provider) {
                "OpenAI" -> "gpt-4o-mini"
                "Groq" -> "llama-3.3-70b-versatile"
                "Ollama" -> "llama3.2"
                else -> "gemini-2.5-flash"
            }
            val model = prefs.getString("model_$provider", defaultModel) ?: defaultModel
            val key = prefs.getString("key_$provider", "") ?: ""
            val ollama = prefs.getString("ollama_url", "") ?: ""
            if (provider != "Ollama" && key.isBlank()) {
                dao.update(reel.copy(processingStatus = "blocked", processingError =
                    "Configure une clé API dans Réglages pour générer le résumé."))
                return@withContext Result.success()
            }
            dao.update(reel.copy(processingStatus = "summarizing", processingError = ""))
            try {
                val summary = AiProviders.ask(provider, model, key, ollama,
                    "Tu es ReelMind, assistant francophone. Résume uniquement les faits du texte fourni. " +
                    "Structure: titre court, résumé, 3 à 5 idées clés, 3 à 6 hashtags. " +
                    "Mentionne clairement les limites et n'invente jamais le contenu de la vidéo.",
                    "URL: ${reel.url}\nTexte source:\n${reel.sourceText.take(30000)}")
                // Fetch the latest row, to avoid overwriting user edits made while processing.
                val current = dao.byId(id) ?: return@withContext Result.success()
                val tags = Regex("#[\\p{L}0-9_-]+").findAll(summary).map { it.value }
                    .distinct().take(6).toList()
                dao.update(current.copy(summary = summary,
                    category = if (current.category == "À classer" && tags.isNotEmpty())
                        tags.joinToString(" ") else current.category,
                    processingStatus = "done", processingError = ""))
                notifyUser("Résumé prêt", "Ta fiche est prête dans ReelMind", id.toInt())
            } catch (e: Exception) {
                val current = dao.byId(id) ?: return@withContext Result.success()
                dao.update(current.copy(processingStatus = "failed", processingError =
                    (e.message ?: "Erreur de génération").take(250)))
                notifyUser("Résumé non généré", "Ouvre la fiche pour voir l'erreur", id.toInt())
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry().takeIf { runAttemptCount < 2 } ?: Result.failure()
        } finally {
            db.close()
        }
    }

    private fun notifyUser(title: String, message: String, id: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "reelmind_processing"
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(channelId, "Résumés ReelMind", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title).setContentText(message).setAutoCancel(true).build()
        nm.notify(id, notification)
    }
}

/** Limited to *public* HTML metadata; no login, cookie replay or access-control bypass. */
object InstagramPublicMetadata {
    fun read(rawUrl: String): String {
        val parsed = URL(rawUrl)
        require(parsed.protocol == "https" && parsed.host.lowercase() in
            setOf("instagram.com", "www.instagram.com")) { "Domaine non autorisé" }
        val conn = parsed.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 7000
            conn.readTimeout = 9000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "ReelMind/0.4 (Android; public metadata only)")
            if (conn.responseCode != 200) return ""
            val data = conn.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(131072)
                val count = reader.read(chars)
                if (count > 0) String(chars, 0, count) else ""
            }
            val tags = Regex("<meta\\s+[^>]*>", RegexOption.IGNORE_CASE).findAll(data)
            for (match in tags) {
                val tag = match.value
                val property = attr(tag, "property") ?: attr(tag, "name")
                if (property.equals("og:description", true) || property.equals("description", true)) {
                    val content = attr(tag, "content").orEmpty()
                    val clean = android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_LEGACY)
                        .toString().trim()
                    if (clean.length > 30 && !clean.contains("Log in to Instagram", true) &&
                        !clean.contains("See Instagram photos and videos", true) &&
                        !clean.contains("Sign up to see photos", true))
                        return clean.take(20000)
                }
            }
            return ""
        } finally { conn.disconnect() }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*([\\\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(2)
}
