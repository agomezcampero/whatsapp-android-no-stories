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

    fun write(context: Context, header: List<String>, candidates: List<String>) {
        val text = buildString {
            appendLine("No Stories detection report")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine()
            header.forEach(::appendLine)
            appendLine()
            if (candidates.isEmpty()) {
                appendLine("no candidates passed the row shape test")
            } else {
                candidates.take(MAX_LINES).forEach(::appendLine)
                if (candidates.size > MAX_LINES) {
                    appendLine("... and ${candidates.size - MAX_LINES} more")
                }
            }
        }
        runCatching { File(context.filesDir, FILE).writeText(text) }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE).takeIf(File::exists)?.readText()
    }.getOrNull()
}
