package io.sharpscreen.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Screens shared by the home card and Settings ▸ About for [SelfUpdater]. */
object UpdateUi {
    /** The Update button: asks for "install unknown apps" first when Android requires it. */
    fun startUpdate(activity: Activity) {
        if (SelfUpdater.streaming) {
            Toast.makeText(activity, R.string.update_error_streaming, Toast.LENGTH_LONG).show()
            return
        }
        if (SelfUpdater.canRequestInstalls()) {
            SelfUpdater.downloadAndInstall()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                SelfUpdater.markPermissionPending()
                val intent =
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                try {
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun openReleaseNotes(
        activity: Activity,
        manifest: UpdateManifest,
    ) {
        val url = manifest.notesUrl ?: MAC_APP_URL
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(activity, activity.getString(R.string.error_open_url, url), Toast.LENGTH_LONG).show()
        }
    }

    /** One line about an update in progress or a failure, or null when there is nothing to say. */
    fun progressText(
        activity: Activity,
        state: UpdateState,
    ): String? =
        when (state) {
            is UpdateState.Downloading ->
                if (state.percent < 0) {
                    activity.getString(R.string.update_downloading_start)
                } else {
                    activity.getString(R.string.update_downloading, state.percent)
                }
            is UpdateState.Installing -> activity.getString(R.string.update_installing)
            is UpdateState.Failed -> errorText(activity, state)
            else -> null
        }

    fun errorText(
        activity: Activity,
        state: UpdateState.Failed,
    ): String =
        when (state.error) {
            UpdateError.CHECK -> activity.getString(R.string.update_error_check)
            UpdateError.DOWNLOAD -> activity.getString(R.string.update_error_download)
            UpdateError.VERIFY -> activity.getString(R.string.update_error_verify)
            UpdateError.INSTALL -> activity.getString(R.string.update_error_install, state.detail ?: "—")
            UpdateError.STREAMING -> activity.getString(R.string.update_error_streaming)
            UpdateError.ANDROID_TOO_OLD -> activity.getString(R.string.update_error_android_too_old, state.detail ?: "")
            UpdateError.PERMISSION -> activity.getString(R.string.update_error_permission)
            UpdateError.CANCELLED -> activity.getString(R.string.update_cancelled)
        }

    /** Status at the right of Settings ▸ About ▸ Check for updates. */
    fun settingsStatus(
        activity: Activity,
        state: UpdateState,
    ): String =
        when (state) {
            UpdateState.Idle -> ""
            UpdateState.Checking -> activity.getString(R.string.update_checking)
            UpdateState.UpToDate -> activity.getString(R.string.update_up_to_date)
            is UpdateState.Available -> activity.getString(R.string.update_available_short, state.manifest.versionName)
            else -> progressText(activity, state).orEmpty()
        }
}
