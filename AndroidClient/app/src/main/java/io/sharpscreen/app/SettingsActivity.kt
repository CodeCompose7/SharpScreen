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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.sharpscreen.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

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
        SelfUpdater.init(this)

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
                AppLanguage.JAPANESE to (binding.languageJapanese to binding.languageJapaneseCheck),
                AppLanguage.GERMAN to (binding.languageGerman to binding.languageGermanCheck),
                AppLanguage.FRENCH to (binding.languageFrench to binding.languageFrenchCheck),
                AppLanguage.RUSSIAN to (binding.languageRussian to binding.languageRussianCheck),
                AppLanguage.CHINESE_SIMPLIFIED to (binding.languageChineseSimplified to binding.languageChineseSimplifiedCheck),
                AppLanguage.SPANISH to (binding.languageSpanish to binding.languageSpanishCheck),
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

    private fun currentLanguage(): AppLanguage = AppLanguage.fromLocale(AppCompatDelegate.getApplicationLocales()[0])

    // ==================== Connection: USB host / port ====================

    private fun setupUsbEndpoint() {
        fun showStored() {
            binding.usbHostInput.setText(prefs.usbHost)
            binding.usbPortInput.setText(prefs.usbPort.toString())
            binding.usbPortLayout.error = null
        }
        showStored()

        // Validated as typed, saved when the screen is left (onPause), so half-typed
        // values like the "5" on the way to "54321" are never stored.
        binding.usbPortInput.doAfterTextChanged { text ->
            val valid = PreferencesManager.parsePort(text?.toString().orEmpty()) != null
            binding.usbPortLayout.error = if (valid) null else getString(R.string.settings_usb_port_invalid)
        }
        binding.usbResetButton.setOnClickListener {
            prefs.usbHost = PreferencesManager.DEFAULT_USB_HOST
            prefs.usbPort = PreferencesManager.DEFAULT_USB_PORT
            showStored()
        }
    }

    /** Saves the USB fields; MainActivity reads them when Connect is pressed. */
    private fun saveUsbEndpoint() {
        // An emptied host field means "use the default".
        prefs.usbHost =
            binding.usbHostInput.text
                ?.toString()
                ?.trim()
                .orEmpty()
                .ifEmpty { PreferencesManager.DEFAULT_USB_HOST }
        // An invalid port keeps the previously saved one.
        PreferencesManager.parsePort(binding.usbPortInput.text?.toString().orEmpty())?.let { prefs.usbPort = it }
    }

    override fun onPause() {
        super.onPause()
        saveUsbEndpoint()
        SelfUpdater.onActivityPaused(this)
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
        setupUpdates()
    }

    /** Check for updates (status at the end; tap again to install) and Check automatically. */
    private fun setupUpdates() {
        binding.autoUpdateSwitch.isChecked = SelfUpdater.autoCheckEnabled
        binding.autoUpdateSwitch.setOnCheckedChangeListener { _, checked -> SelfUpdater.autoCheckEnabled = checked }
        binding.autoUpdateRow.setOnClickListener { binding.autoUpdateSwitch.toggle() }
        binding.checkUpdatesRow.setOnClickListener {
            when (val s = SelfUpdater.state.value) {
                UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> Unit
                is UpdateState.Available -> UpdateUi.startUpdate(this)
                is UpdateState.Failed ->
                    // A failed download or install retries; a failed check checks again.
                    if (s.manifest != null && s.error != UpdateError.ANDROID_TOO_OLD) UpdateUi.startUpdate(this) else SelfUpdater.checkNow()
                else -> SelfUpdater.checkNow()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SelfUpdater.state.collect { state ->
                    binding.checkUpdatesStatus.text = UpdateUi.settingsStatus(this@SettingsActivity, state)
                    val offer = state is UpdateState.Available || (state is UpdateState.Failed && state.manifest != null)
                    binding.checkUpdatesStatus.setTextColor(getColor(if (offer) R.color.ss_accent else R.color.ss_text_secondary))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SelfUpdater.onActivityResumed(this)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.error_open_url, url), Toast.LENGTH_LONG).show()
        }
    }
}
