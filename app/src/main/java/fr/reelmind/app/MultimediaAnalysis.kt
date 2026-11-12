package fr.reelmind.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Audio only: avoid uploading a whole video to the model. */
object GroqWhisper {
    fun transcribe(file: File, key: String): String {
        require(file.length() in 1..(24L * 1024 * 1024)) { "Audio vide ou trop volumineux (24 Mo max)" }
        val boundary = "----ReelMind${UUID.randomUUID()}"
        val conn = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                fun part(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                part("model", "whisper-large-v3-turbo")
                part("response_format", "json")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.${file.extension.lowercase()}\"\r\nContent-Type: audio/${if (file.extension.lowercase() == "mp3") "mpeg" else "mp4"}\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = conn.responseCode
            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Groq HTTP $status : ${raw.take(180)}")
            return JSONObject(raw).optString("text").ifBlank { throw IllegalStateException("Transcription vide") }
        } finally { conn.disconnect() }
    }
}

/** Carousel/screenshots, downsampled by the server or Android caller before calling Gemini. */
object GeminiImages {
    fun describe(images: List<File>, apiKey: String, model: String = "gemini-3.5-flash-lite"): String {
        require(apiKey.isNotBlank()) { "Clé Gemini nécessaire" }
        val parts = JSONArray().put(JSONObject().put("text", "Analyse ces images dans l'ordre. En français, extrais tout texte utile, puis explique les diagrammes, étapes et informations visuelles. N'invente rien. Ne répète pas la légende."))
        for (file in images.take(8)) {
            require(file.length() in 1..(4L * 1024 * 1024)) { "Image trop volumineuse (4 Mo maximum)" }
            val bytes = file.readBytes()
            val mime = if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", mime)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
        }
        val request = JSONObject().put("contents", JSONArray().put(JSONObject()
            .put("role", "user").put("parts", parts)))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 120000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(request.toString().toByteArray()) }
            val status = conn.responseCode
            val response = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Gemini HTTP $status : ${response.take(160)}")
            val output = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini : aucune analyse")
            return (0 until output.length()).joinToString("\n") { output.optJSONObject(it)?.optString("text").orEmpty() }
        } finally { conn.disconnect() }
    }
}
