package io.sharpscreen.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import io.sharpscreen.app.databinding.ActivitySettingsBinding

/**
 * App settings, opened from the gear on the home panel: language, the USB-mode
 * host/port, and About. Not the in-stream display settings dialog (MainActivity).
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesManager(this)

        binding.backButton.setOnClickListener { finish() }
        setupLanguage()
        setupUsbEndpoint()
        setupAbout()
    }

    // ==================== General: language ====================

    private fun setupLanguage() {
        val rows =
            mapOf(
                AppLanguage.SYSTEM to (binding.languageSystem to binding.languageSystemCheck),
                AppLanguage.ENGLISH to (binding.languageEnglish to binding.languageEnglishCheck),
                AppLanguage.KOREAN to (binding.languageKorean to binding.languageKoreanCheck),
            )

        fun showSelected(selected: AppLanguage) {
            rows.forEach { (language, row) ->
                val isSelected = language == selected
                row.second.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                row.first.isSelected = isSelected
            }
        }

        showSelected(currentLanguage())
        rows.forEach { (language, row) ->
            row.first.setOnClickListener {
                if (language == currentLanguage()) return@setOnClickListener
                showSelected(language)
                // Applies immediately: AppCompat recreates the open activities in the new
                // language and stores the choice (autoStoreLocales below API 33).
                AppCompatDelegate.setApplicationLocales(
                    if (language == AppLanguage.SYSTEM) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(language.tag)
                    },
                )
            }
        }
    }

    private fun currentLanguage(): AppLanguage = AppLanguage.fromLanguage(AppCompatDelegate.getApplicationLocales()[0]?.language)

    // ==================== Connection: USB host / port ====================

    private fun setupUsbEndpoint() {
        fun showStored() {
            binding.usbHostInput.setText(prefs.usbHost)
            binding.usbPortInput.setText(prefs.usbPort.toString())
            binding.usbPortLayout.error = null
        }
        showStored()

        // Saved as typed; MainActivity reads them when Connect is pressed.
        binding.usbHostInput.doAfterTextChanged { text ->
            prefs.usbHost = text?.toString()?.trim().orEmpty()
        }
        binding.usbPortInput.doAfterTextChanged { text ->
            val port = PreferencesManager.parsePort(text?.toString().orEmpty())
            if (port != null) {
                prefs.usbPort = port
                binding.usbPortLayout.error = null
            } else {
                // Keep the last valid port stored; just flag the field.
                binding.usbPortLayout.error = getString(R.string.settings_usb_port_invalid)
            }
        }
        binding.usbResetButton.setOnClickListener {
            prefs.usbHost = PreferencesManager.DEFAULT_USB_HOST
            prefs.usbPort = PreferencesManager.DEFAULT_USB_PORT
            showStored()
        }
    }

    override fun onPause() {
        super.onPause()
        // An emptied host field means "use the default".
        if (prefs.usbHost.isBlank()) prefs.usbHost = PreferencesManager.DEFAULT_USB_HOST
    }

    // ==================== About ====================

    private fun setupAbout() {
        binding.versionText.text =
            try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                null
            } ?: "—"
        binding.reportProblemRow.setOnClickListener { openUrl(REPORT_PROBLEM_URL) }
        binding.githubRow.setOnClickListener { openUrl(GITHUB_URL) }
        binding.macAppRow.setOnClickListener { openUrl(MAC_APP_URL) }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.error_open_url, url), Toast.LENGTH_LONG).show()
        }
    }
}
