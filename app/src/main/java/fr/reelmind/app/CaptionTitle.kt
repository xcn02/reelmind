package fr.reelmind.app

/** Deterministic title from Instagram caption; no network / AI call. */
object CaptionTitle {
    fun fromCaption(caption: String): String {
        val cleaned = caption.replace(Regex("""https?://\S+"""), " ")
            .replace(Regex("""(?m)^\s*[@#][\p{L}\p{N}_.-]+\s*$"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
            .replace(Regex("""^(?:[@#][\p{L}\p{N}_.-]+\s+)+"""), "").trim()
        if (cleaned.isEmpty()) return "Reel Instagram"
        val sentence = Regex("""[.!?。！？](?=\s|$)""").find(cleaned)?.let { cleaned.take(it.range.last + 1) } ?: cleaned
        if (sentence.length <= 100) return sentence
        return sentence.take(100).substringBeforeLast(' ', sentence.take(100)).trimEnd(' ', ',', ';', ':')
            .ifBlank { "Reel Instagram" }
    }
}
