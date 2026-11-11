package fr.reelmind.app

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExtractionSettingsPanel(activity: ComponentActivity) {
    val prefs = remember { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var local by remember { mutableStateOf(prefs.getBoolean("ex_local", true)) }
    var render by remember { mutableStateOf(prefs.getBoolean("ex_render", true)) }
    var remoteFirst by remember { mutableStateOf(prefs.getString("ex_priority", "auto") == "remote") }
    var backendToken by remember { mutableStateOf(prefs.getString("ex_backend_token", "").orEmpty()) }
    var endpoint by remember { mutableStateOf(prefs.getString("ex_backend", "").orEmpty()) }
    var transcribe by remember { mutableStateOf(prefs.getBoolean("ex_audio", true)) }
    var images by remember { mutableStateOf(prefs.getString("ex_images", "gemini") ?: "gemini") }
    var categoryAuto by remember { mutableStateOf(prefs.getBoolean("ex_categories", true)) }
    var temporary by remember { mutableStateOf(prefs.getBoolean("ex_temporary", true)) }
    var updateStatus by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun saveBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    HorizontalDivider()
    Text("Extraction hybride", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("yt-dlp local", modifier = Modifier.weight(1f))
        Switch(local, onCheckedChange = { local = it; saveBoolean("ex_local", it) })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Backend Render (facultatif)", modifier = Modifier.weight(1f))
        Switch(render, onCheckedChange = { render = it; saveBoolean("ex_render", it) })
    }
    if (render) {
        OutlinedTextField(endpoint, { endpoint = it; prefs.edit().putString("ex_backend", it).apply() },
            label = { Text("URL HTTPS de Render") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(backendToken, { backendToken = it; prefs.edit().putString("ex_backend_token", it).apply() },
            label = { Text("Jeton privé du backend") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Render prioritaire", modifier = Modifier.weight(1f))
        Switch(remoteFirst, onCheckedChange = {
            remoteFirst = it; prefs.edit().putString("ex_priority", if (it) "remote" else "auto").apply()
        })
    }
    Text(if (remoteFirst) "Ordre : Render → local" else "Ordre : local → Render", style = MaterialTheme.typography.bodySmall)
    Button(enabled = local && !updating, onClick = {
        updating = true; updateStatus = "Actualisation du moteur…"
        scope.launch {
            updateStatus = runCatching { withContext(Dispatchers.IO) { LocalExtractor.update(activity.applicationContext, false) } }
                .fold({ "Mise à jour : $it" }, { "Échec : ${it.message}" })
            updating = false
        }
    }, modifier = Modifier.fillMaxWidth()) { Text(if (updating) "Mise à jour en cours" else "Mettre yt-dlp à jour") }
    if (updateStatus.isNotBlank()) Text(updateStatus, style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Traitement des médias", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Transcription audio (Groq)", modifier = Modifier.weight(1f))
        Switch(transcribe, onCheckedChange = { transcribe = it; saveBoolean("ex_audio", it) })
    }
    Text("La transcription automatique utilise la clé Groq. Sans clé Groq, seule la légende est résumée.", style = MaterialTheme.typography.bodySmall)
    Text("Images et carrousels")
    listOf("gemini" to "Gemini Vision", "off" to "Désactivé").forEach { (value, label) ->
        Row(Modifier.fillMaxWidth()) {
            RadioButton(selected = images == value, onClick = { images = value; prefs.edit().putString("ex_images", value).apply() })
            Text(label, modifier = Modifier.padding(top = 12.dp))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Supprimer les médias temporaires", modifier = Modifier.weight(1f))
        Switch(temporary, onCheckedChange = { temporary = it; saveBoolean("ex_temporary", it) })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Catégories automatiques", modifier = Modifier.weight(1f))
        Switch(categoryAuto, onCheckedChange = { categoryAuto = it; saveBoolean("ex_categories", it) })
    }
    Text("Le mode local utilise le stockage temporaire interne ; les fichiers de traitement sont supprimés après analyse.", style = MaterialTheme.typography.bodySmall)
}
