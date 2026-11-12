package fr.reelmind.app

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.math.BigInteger

/** Best-effort metadata from public Instagram page data. No login, cookies or AI requests. */
object InstagramJsonExtractor {
    fun extract(rawUrl: String): ExtractedContent {
        val url = ExtractionCoordinator.validateInstagramUrl(rawUrl)
        val conn = URL("$url/").openConnection() as HttpURLConnection
        val html = try {
            conn.connectTimeout = 6500
            conn.readTimeout = 8500
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)")
            conn.setRequestProperty("Accept", "text/html")
            val status = conn.responseCode
            require(status == 200) { "Page Instagram HTTP $status" }
            conn.inputStream.bufferedReader().use { it.readText().take(2_000_000) }
        } finally { conn.disconnect() }
        val shortcode = url.trimEnd('/').substringAfterLast('/')
        val fromScripts = sequenceOf(
            Regex("""<script\b[^>]*data-sjs[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<script\b[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        ).flatMap { regex -> regex.findAll(html).map { it.groupValues[1] } }
        for (script in fromScripts) {
            val parsed = runCatching { JSONObject(script) }.getOrNull() ?: continue
            val found = findPost(parsed, shortcode, shortcodeId(shortcode), 0) ?: continue
            val caption = found.optJSONObject("caption")?.optString("text").orEmpty()
                .ifBlank { (found.opt("caption") as? String).orEmpty() }
                .ifBlank { found.optString("description") }
            val author = found.optJSONObject("user")?.optString("username").orEmpty()
                .ifBlank { found.optJSONObject("owner")?.optString("username").orEmpty() }
            val thumbnail = imageOf(found)
            if (caption.isNotBlank() || author.isNotBlank() || thumbnail.isNotBlank())
                return ExtractedContent(caption = caption, author = author, thumbnail = thumbnail,
                    kind = if (found.has("carousel_media")) "carousel" else "video")
        }
        // Public OpenGraph / structured metadata as a strictly limited fallback.
        val caption = meta(html, "og:description").ifBlank { meta(html, "description") }
        val image = meta(html, "og:image")
        if (caption.isNotBlank() || image.isNotBlank()) return ExtractedContent(caption = caption, thumbnail = image)
        throw IllegalStateException("Aucune métadonnée publique JSON trouvée")
    }

    private fun findPost(value: Any?, shortcode: String, mediaId: String, depth: Int): JSONObject? {
        if (depth > 24) return null
        when (value) {
            is JSONObject -> {
                val code = value.optString("code").ifBlank { value.optString("shortcode") }
                val sameId = mediaId.isNotBlank() && (value.optString("pk") == mediaId || value.optString("id") == mediaId)
                if ((code == shortcode || sameId) && (value.has("caption") || value.has("image_versions2") || value.has("user") || value.has("video_versions"))) return value
                val gated = value.optJSONObject("if_not_gated_logged_out")
                if (gated != null && (gated.optString("code") == shortcode || gated.optString("shortcode") == shortcode ||
                    mediaId.isNotBlank() && (gated.optString("pk") == mediaId || gated.optString("id") == mediaId))) return gated
                val keys = value.keys()
                while (keys.hasNext()) {
                    findPost(value.opt(keys.next()), shortcode, mediaId, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                findPost(value.opt(i), shortcode, mediaId, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun shortcodeId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var acc = BigInteger.ZERO
        for (char in shortcode) {
            val digit = alphabet.indexOf(char)
            if (digit < 0) return ""
            acc = acc.shiftLeft(6).add(BigInteger.valueOf(digit.toLong()))
        }
        return acc.toString()
    }

    private fun imageOf(post: JSONObject): String {
        val candidates = post.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: post.optJSONArray("image_versions")
        val first = candidates?.optJSONObject(0)?.optString("url").orEmpty()
        return first.ifBlank { post.optString("display_url") }
            .ifBlank { post.optString("thumbnail_url") }
    }

    private fun meta(html: String, property: String): String {
        val tags = Regex("""<meta\s+[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
        for (tag in tags) {
            val attrs = Regex("""([\w:-]+)\s*=\s*(["'])(.*?)\2""", RegexOption.IGNORE_CASE)
                .findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
            if (attrs["property"] == property || attrs["name"] == property) {
                val value = Html.fromHtml(attrs["content"].orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString().trim()
                if (!value.contains("Log in to Instagram", true) && !value.contains("See Instagram photos", true)) return value
            }
        }
        return ""
    }
}
