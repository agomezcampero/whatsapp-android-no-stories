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
 * A button to open Accessibility settings, plus two for working out why the
 * cover landed where it did: share the last detection report, and forget the
 * row id the service learned so it works the row out again from scratch.
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
        findViewById<Button>(R.id.forget_row).setOnClickListener { forgetRow() }
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

    /**
     * Drops the learned id. The service keeps using a remembered id even when
     * it turns out to be the wrong node, so this is the way back out.
     */
    private fun forgetRow() {
        RowIdMemory(this).forget()
        toast(R.string.forgotten)
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
