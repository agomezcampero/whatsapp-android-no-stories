package com.gomezcampero.nostories

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/** One button: open Accessibility settings. Plus a line saying whether it's on. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        status.setText(if (serviceEnabled()) R.string.status_on else R.string.status_off)
    }

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
