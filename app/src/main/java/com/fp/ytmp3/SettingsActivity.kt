package com.fp.ytmp3

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("conf", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val pattern = findViewById<EditText>(R.id.setPattern)
        pattern.setText(Config.renamePattern(prefs))

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            Config.saveRenamePattern(prefs, pattern.text.toString())
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnResetSettings).setOnClickListener {
            Config.reset(prefs)
            pattern.setText(Config.renamePattern(prefs))
            Toast.makeText(this, getString(R.string.settings_reset_done), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnBatteryIgnore).setOnClickListener {
            requestBatteryExemption()
        }
        findViewById<Button>(R.id.btnBatteryAutostart).setOnClickListener {
            openAutostart()
        }
    }

    /** Demande a Android d'exclure l'appli de l'optimisation batterie. */
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, getString(R.string.settings_battery_ok), Toast.LENGTH_SHORT).show()
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(direct)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.settings_battery_unavailable), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Ouvre l'ecran autostart MIUI, avec repli sur les details de l'appli. */
    private fun openAutostart() {
        val miui = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        for (intent in listOf(miui, fallback)) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, getString(R.string.settings_battery_unavailable), Toast.LENGTH_SHORT).show()
    }
}
