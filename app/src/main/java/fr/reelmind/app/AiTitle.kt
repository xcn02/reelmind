package fr.reelmind.app

/** Parse an explicitly labelled AI title, without treating arbitrary answer text as a title. */
object AiTitle {
    fun parse(answer: String): String = Regex("(?im)^\\s*(?:Titre|Title)\\s*:\\s*(.+)$")
        .find(answer)?.groupValues?.get(1)?.trim()?.trim('"', '\'', '*')?.take(100).orEmpty()
    fun summaryWithoutTitle(answer: String): String = answer.replaceFirst(
        Regex("(?im)^\\s*(?:Titre|Title)\\s*:\\s*.+(?:\\r?\\n)?"), "").trim()
}
