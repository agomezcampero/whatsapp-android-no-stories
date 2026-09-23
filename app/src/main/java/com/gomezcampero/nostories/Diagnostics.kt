package com.gomezcampero.nostories

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last detection pass, written where the app's own screen can read it back
 * and hand it to the share sheet.
 *
 * This exists because the shape rules in [StatusRowLocator] were written
 * without ever seeing WhatsApp's real layout. The report says what the scan
 * considered and why it accepted or rejected each candidate, which is the
 * difference between tuning the constants and guessing at them.
 */
object Diagnostics {

    private const val FILE = "detection-report.txt"
    private const val MAX_LINES = 120

    /**
     * Reports are kept per screen, because the last scan before you switch
     * apps is whatever screen you happened to be on - and the one worth
     * reading is the Chats list, which by then has been scrolled past.
     */
    private const val KEEP_SCREENS = 3
    private const val REWRITE_AFTER_MS = 2000L

    private val screens = LinkedHashMap<String, String>()
    private var lastWriteAt = 0L

    fun write(
        context: Context,
        header: List<String>,
        candidates: List<String>,
        others: List<String> = emptyList(),
    ) {
        val signature = (candidates + others)
            .joinToString("|") { it.substringBefore(" class=") }
            .ifEmpty { "empty" }

        val text = buildString {
            appendLine("No Stories detection report")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine()
            header.forEach(::appendLine)
            appendLine()
            appendLine("ROW SHAPED (reached the tile checks)")
            if (candidates.isEmpty()) {
                appendLine("  none")
            } else {
                candidates.take(MAX_LINES).forEach(::appendLine)
                if (candidates.size > MAX_LINES) {
                    appendLine("... and ${candidates.size - MAX_LINES} more")
                }
            }

            appendLine()
            appendLine("EVERYTHING ELSE IN THE TOP BAND")
            others.take(MAX_LINES).forEach(::appendLine)
            if (others.size > MAX_LINES) appendLine("... and ${others.size - MAX_LINES} more")
        }
        synchronized(screens) {
            val known = screens.containsKey(signature)
            screens.remove(signature)
            screens[signature] = text
            while (screens.size > KEEP_SCREENS) {
                screens.remove(screens.keys.first())
            }

            val now = System.currentTimeMillis()
            if (known && now - lastWriteAt < REWRITE_AFTER_MS) return
            lastWriteAt = now

            val all = screens.values.reversed().joinToString("\n\n${"=".repeat(60)}\n\n")
            runCatching { File(context.filesDir, FILE).writeText(all) }
        }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE).takeIf(File::exists)?.readText()
    }.getOrNull()
}
