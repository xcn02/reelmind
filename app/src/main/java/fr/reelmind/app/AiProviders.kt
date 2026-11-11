package fr.reelmind.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTPS client: API keys are sent in headers, never embedded in the APK. */
object AiProviders {
    private fun request(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 65000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val data = runCatching { JSONObject(raw) }.getOrElse {
                throw IllegalStateException("Réponse invalide (HTTP $status)")
            }
            if (status !in 200..299) {
                throw IllegalStateException(data.optJSONObject("error")?.optString("message")
                    ?.take(220)?.ifBlank { null } ?: "Erreur du fournisseur (HTTP $status)")
            }
            return data
        } finally { conn.disconnect() }
    }

    fun ask(provider: String, model: String, key: String, ollamaUrl: String, system: String, user: String): String {
        require(model.matches(Regex("[a-zA-Z0-9._:/-]+"))) { "Nom du modèle invalide" }
        val response = when (provider) {
            "Gemini" -> {
                require(key.isNotBlank()) { "Ajoute une clé Gemini dans Réglages" }
                val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))))
                val data = request("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    mapOf("x-goog-api-key" to key), body)
                val parts = data.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Réponse Gemini vide ou filtrée")
                (0 until parts.length()).joinToString("\n") { parts.optJSONObject(it)?.optString("text").orEmpty() }
            }
            "OpenAI", "Groq" -> {
                require(key.isNotBlank()) { "Ajoute la clé API $provider dans Réglages" }
                val endpoint = if (provider == "OpenAI") "https://api.openai.com/v1/chat/completions"
                    else "https://api.groq.com/openai/v1/chat/completions"
                val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
                val data = request(endpoint, mapOf("Authorization" to "Bearer $key"),
                    JSONObject().put("model", model).put("messages", messages))
                data.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            }
            "Ollama" -> {
                // No remote servers and no cleartext by default. Configure an HTTPS endpoint reachable from Android.
                require(ollamaUrl.startsWith("https://")) { "Ollama : indique une URL HTTPS accessible depuis le téléphone" }
                val base = ollamaUrl.trimEnd('/')
                val data = request("$base/api/chat", emptyMap(), JSONObject().put("model", model)
                    .put("stream", false).put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))))
                data.optJSONObject("message")?.optString("content").orEmpty()
            }
            else -> throw IllegalArgumentException("Fournisseur inconnu")
        }
        return response.trim().ifBlank { throw IllegalStateException("Le modèle a renvoyé une réponse vide") }
    }
}
