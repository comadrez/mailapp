package com.comadrez.mailapp

private val LINK_RE = Regex("""https?://[^\s<>"'\])}]+""")
private val KEYWORD_RE = Regex("""(?i)(code|otp|pin\b|passcode|verification|verify|security|token|confirm)""")
private val CODE_RE = Regex("""(?<![\w@.\-])(\d{3}[- ]\d{3}|\d{4,8})(?![\w@]|[.,]\d)""")

/** Character ranges of web links inside [text]. Trailing punctuation is left out of the link. */
fun findLinkRanges(text: String): List<IntRange> =
    LINK_RE.findAll(text).mapNotNull { m ->
        var end = m.range.last
        while (end > m.range.first && text[end] in ".,;:!?") end--
        if (end - m.range.first < 8) null else m.range.first..end
    }.toList()

/**
 * Finds likely verification codes: 4-8 digits (or 123-456 style) with a word like
 * "code", "verify" or "OTP" shortly before it. Returned digits only, at most 3.
 */
fun findCodes(text: String): List<String> {
    val out = LinkedHashSet<String>()
    for (m in CODE_RE.findAll(text)) {
        val from = maxOf(0, m.range.first - 60)
        val window = text.substring(from, m.range.first)
        if (KEYWORD_RE.containsMatchIn(window)) {
            out.add(m.value.filter { it.isDigit() })
            if (out.size >= 3) break
        }
    }
    return out.toList()
}
