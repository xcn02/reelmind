package fr.reelmind.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.withContext
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A no-navigation share target: store now, then return to Instagram. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val found = Regex("https?://(?:www\\.)?instagram\\.com/(?:reel|reels|p|tv)/[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE)
            .find(text)?.value
        if (found == null) {
            Toast.makeText(this, "Aucun lien Instagram valide", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val url = runCatching { ExtractionCoordinator.validateInstagramUrl(found) }.getOrNull()
        if (url == null) { finish(); return }
        val caption = text.replace(Regex("https?://[^\\s]+"), "").trim().take(20000)
        val context = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = Room.databaseBuilder(context, ReelDatabase::class.java, "reelmind.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
                try {
                    val id = db.dao().insert(Reel(url = url, sourceText = caption, originalCaption = caption))
                    if (id > 0) ReelProcessing.enqueue(context, id)
                } finally { db.close() }
                withContext(Dispatchers.Main) { Toast.makeText(this@ShareReceiverActivity, "Enregistré dans ReelMind", Toast.LENGTH_SHORT).show(); finish() }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@ShareReceiverActivity, "Enregistrement impossible", Toast.LENGTH_SHORT).show(); finish() }
            }
        }
    }
}
