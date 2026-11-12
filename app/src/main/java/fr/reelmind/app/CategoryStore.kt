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
    fun list(context: Context): List<ReelCategory> {
        val json = context.getSharedPreferences("settings", 0).getString("categories_custom", "[]").orEmpty()
        val custom = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val v = arr.optJSONObject(i) ?: return@mapNotNull null
                ReelCategory(v.optString("name"), v.optString("icon", "📁"), v.optLong("color", 0xFF4285DB))
            }.filter { it.name.isNotBlank() }
        }.getOrDefault(emptyList())
        return (defaults + custom).distinctBy { it.name.lowercase() }
    }
    fun add(context: Context, item: ReelCategory) {
        val current = list(context)
        require(item.name.isNotBlank() && item.name.length <= 35)
        if (current.any { it.name.equals(item.name, true) }) return
        val prefs = context.getSharedPreferences("settings", 0)
        val arr = JSONArray(prefs.getString("categories_custom", "[]"))
        arr.put(JSONObject().put("name", item.name.trim()).put("icon", item.icon).put("color", item.color))
        prefs.edit().putString("categories_custom", arr.toString()).apply()
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
