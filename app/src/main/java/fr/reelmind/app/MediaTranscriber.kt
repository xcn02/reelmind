package fr.reelmind.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Small local audio/video files can be transcribed with Gemini's multimodal API. */
object MediaTranscriber {
    fun transcribe(context: Context, uri: Uri, apiKey: String, provider: String, model: String = "gemini-3.5-transcribe"): String {
        require(provider == "Gemini") { "Sélectionne Gemini dans Réglages pour la transcription" }
        require(apiKey.isNotBlank()) { "Ajoute ta clé Gemini dans Réglages" }
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("audio/") || mime.startsWith("video/")) { "Fichier audio ou vidéo requis" }
        // Inline upload: conservative 12 MiB cap to avoid memory pressure and request limits.
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= 12 * 1024 * 1024) {
                    "Fichier trop volumineux (limite 12 Mo). Utilise un extrait plus court."
                }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalStateException("Impossible de lire ce fichier")
        require(bytes.isNotEmpty()) { "Fichier vide" }
        return transcribeBytes(bytes, mime, apiKey, model)
    }

    fun transcribeBytes(bytes: ByteArray, mime: String, apiKey: String, model: String): String {
        require(apiKey.isNotBlank()) { "Clé Gemini requise" }
        require(model.matches(Regex("[a-zA-Z0-9._-]+"))) { "Modèle Gemini invalide" }
        val inlineData = JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val parts = JSONArray()
            .put(JSONObject().put("text", "Transcris fidèlement en français le contenu parlé de cet enregistrement. " +
                "Conserve les noms propres; si une portion est inaudible, marque-la [inaudible]. " +
                "Ne décris pas des paroles qui ne sont pas présentes. Réponds seulement avec la transcription."))
            .put(JSONObject().put("inlineData", inlineData))
        val payload = JSONObject().put("contents", JSONArray()
            .put(JSONObject().put("role", "user").put("parts", parts)))
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = JSONObject(raw)
            if (code !in 200..299) throw IllegalStateException(
                json.optJSONObject("error")?.optString("message")?.take(200) ?: "HTTP $code")
            val items = json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini n'a pas renvoyé de transcription")
            return (0 until items.length()).joinToString("\n") {
                items.optJSONObject(it)?.optString("text").orEmpty()
            }.trim().ifBlank { throw IllegalStateException("Transcription vide") }
        } finally { connection.disconnect() }
    }
}

object GeminiFileAudio {
    fun transcribe(file: java.io.File, key: String, model: String): String {
        require(file.length() in 1..(12L * 1024 * 1024)) { "Audio trop volumineux pour l'import direct Gemini (12 Mo)" }
        val mime = when(file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; else -> "audio/mp4"
        }
        return MediaTranscriber.transcribeBytes(file.readBytes(), mime, key, model)
    }
}
