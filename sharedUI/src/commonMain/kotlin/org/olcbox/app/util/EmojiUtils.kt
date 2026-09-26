package org.olcbox.app.util

/**
 * Parses a raw string to extract a leading emoji and the remaining text.
 * If no leading emoji is found, returns a default emoji and the original text.
 */
fun parseEmojiAndName(rawName: String, defaultEmoji: String = ""): Pair<String, String> {
    if (rawName.isBlank()) return defaultEmoji to ""

    val it = rawName.iterator()
    if (!it.hasNext()) return defaultEmoji to rawName

    val firstChar = it.next()

    // A country flag is two regional-indicator code points (four UTF-16 chars).
    // Taking only the first surrogate pair leaves half a flag in the name.
    if (rawName.length >= 4 && rawName[0].isRegionalIndicatorStart() && rawName[1].isRegionalIndicatorEnd() &&
        rawName[2].isRegionalIndicatorStart() && rawName[3].isRegionalIndicatorEnd()
    ) {
        val flag = rawName.substring(0, 4)
        return flag to rawName.substring(4).trim()
    }

    // Check for emoji ranges or surrogate pairs
    return if (firstChar.isHighSurrogate() || firstChar.code in 0x2000..0x32FF || firstChar.code > 0x1F000) {
        val emoji = if (firstChar.isHighSurrogate() && it.hasNext()) {
            "$firstChar${it.next()}"
        } else {
            firstChar.toString()
        }
        emoji to rawName.substring(emoji.length).trim()
    } else {
        defaultEmoji to rawName
    }
}

private fun Char.isRegionalIndicatorStart(): Boolean = this == '\uD83C'
private fun Char.isRegionalIndicatorEnd(): Boolean = this in '\uDDE6'..'\uDDFF'
