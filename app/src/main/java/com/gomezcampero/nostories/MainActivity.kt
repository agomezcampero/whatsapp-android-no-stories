package com.gomezcampero.nostories

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * A button to open Accessibility settings, plus two for working out where the
 * cover landed: share the last detection report, and paint the cover
 * see-through.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.share_report).setOnClickListener { shareReport() }

        val options = DebugOptions(this)
        findViewById<Switch>(R.id.see_through).apply {
            isChecked = options.seeThrough
            setOnCheckedChangeListener { _, checked -> options.seeThrough = checked }
        }
    }

    override fun onResume() {
        super.onResume()
        status.setText(if (serviceEnabled()) R.string.status_on else R.string.status_off)
    }

    /** Hands the last detection pass to the share sheet as plain text. */
    private fun shareReport() {
        val report = Diagnostics.read(this)
        if (report.isNullOrBlank()) {
            toast(R.string.no_report)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_subject))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_report)))
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val service = ComponentName(this, StoryHiderService::class.java)
        return enabled.split(':').any {
            it == service.flattenToString() || it == service.flattenToShortString()
        }
    }
}
