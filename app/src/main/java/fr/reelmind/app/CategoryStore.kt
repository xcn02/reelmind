package fr.reelmind.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ReelCategory(val name: String, val icon: String, val color: Long)

object CategoryStore {
    private val defaults = listOf(
        ReelCategory("Technologie", "💻", 0xFF4895EF),
        ReelCategory("Travail", "💼", 0xFF6478D9),
        ReelCategory("Finance", "📈", 0xFF20A987),
        ReelCategory("Sport", "💪", 0xFFF09A3E),
        ReelCategory("Voyage", "✈️", 0xFF12AAB7),
        ReelCategory("Cuisine", "🍳", 0xFFE47761),
        ReelCategory("Culture", "📚", 0xFF9870CF),
        ReelCategory("Bricolage", "🛠️", 0xFFB77B45),
        ReelCategory("Santé", "🌿", 0xFF39A668),
        ReelCategory("Shopping", "🛍️", 0xFFCB6597),
        ReelCategory("Automobile", "🚗", 0xFF648FBB),
        ReelCategory("Autre", "📁", 0xFF888DA1)
    )
    private fun readCustom(context: Context): List<ReelCategory> {
        val prefs = context.getSharedPreferences("settings", 0)
        // Legacy installations stored custom additions separately.
        val raw = prefs.getString("categories_all", null) ?: prefs.getString("categories_custom", "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                ReelCategory(obj.optString("name"), obj.optString("icon", "📁"), obj.optLong("color", 0xFF4285DB))
            }.filter { it.name.isNotBlank() }
        }.getOrDefault(emptyList())
    }
    fun list(context: Context): List<ReelCategory> {
        val prefs = context.getSharedPreferences("settings", 0)
        return if (prefs.contains("categories_all")) readCustom(context)
            else (defaults + readCustom(context)).distinctBy { it.name.lowercase() }
    }
    fun replaceAll(context: Context, categories: List<ReelCategory>) {
        val arr = JSONArray()
        categories.distinctBy { it.name.trim().lowercase() }.forEach { cat ->
            require(cat.name.isNotBlank() && cat.name.length <= 35)
            arr.put(JSONObject().put("name", cat.name.trim()).put("icon", cat.icon).put("color", cat.color))
        }
        context.getSharedPreferences("settings", 0).edit().putString("categories_all", arr.toString()).apply()
    }
    fun add(context: Context, item: ReelCategory) {
        val current = list(context)
        if (current.any { it.name.equals(item.name.trim(), true) }) return
        replaceAll(context, current + item)
    }
    fun classifyFromAnswer(answer: String, context: Context, fallbackText: String): String {
        val found = Regex("(?im)^\\s*(?:catégorie|categorie)\\s*:\\s*(.+)$").find(answer)?.groupValues?.get(1)?.trim()?.trim('*', '#', ' ', '.')
        return list(context).firstOrNull { it.name.equals(found, true) }?.name ?: guess(fallbackText, context)
    }
    fun guess(text: String, context: Context): String {
        val content = text.lowercase()
        val clues = mapOf(
            "Technologie" to listOf("technolog", "informatique", "esp32", "arduino", "android", "logiciel", "code", "programm", "ordinateur", "electron"),
            "Travail" to listOf("emploi", "carrière", "business", "entreprise", "productivité", "professionnel"),
            "Finance" to listOf("finance", "bitcoin", "crypto", "invest", "bourse", "budget", "épargne"),
            "Sport" to listOf("sport", "musculation", "judo", "fitness", "entraînement"),
            "Voyage" to listOf("voyage", "tourisme", "vacances", "destination", "travel"),
            "Cuisine" to listOf("cuisine", "recette", "cuisson", "aliment", "restaurant"),
            "Culture" to listOf("musée", "histoire", "livre", "cinéma", "artiste"),
            "Bricolage" to listOf("bricolage", "réparation", "réparer", "outils", "diy"),
            "Santé" to listOf("santé", "médecine", "nutrition", "sommeil"),
            "Shopping" to listOf("achat", "produit", "commande", "shopping"),
            "Automobile" to listOf("voiture", "automobile", "moteur", "véhicule")
        )
        val scored = clues.mapValues { (_, words) -> words.count { content.contains(it) } }
        val winner = scored.maxByOrNull { it.value }
        return if (winner != null && winner.value > 0 && list(context).any { it.name == winner.key }) winner.key else "À classer"
    }
}
