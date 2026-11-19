package io.sharpscreen.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

private fun selfDiag(msg: String) = DiagLog.log("SU", msg)

/** Where an update stands; drives the home card and Settings ▸ About. */
sealed class UpdateState {
    /** Nothing known (no check yet, or an automatic check found nothing to say). */
    object Idle : UpdateState()

    object Checking : UpdateState()

    object UpToDate : UpdateState()

    data class Available(
        val manifest: UpdateManifest,
    ) : UpdateState()

    /** [percent] is 0..100, or -1 before the size is known. */
    data class Downloading(
        val manifest: UpdateManifest,
        val percent: Int,
    ) : UpdateState()

    data class Installing(
        val manifest: UpdateManifest,
    ) : UpdateState()

    /** [manifest] is null when the check itself failed. */
    data class Failed(
        val manifest: UpdateManifest?,
        val error: UpdateError,
        val detail: String? = null,
    ) : UpdateState()

    val manifestOrNull: UpdateManifest?
        get() =
            when (this) {
                is Available -> manifest
                is Downloading -> manifest
                is Installing -> manifest
                is Failed -> manifest
                else -> null
            }
}

enum class UpdateError { CHECK, DOWNLOAD, VERIFY, INSTALL, STREAMING, ANDROID_TOO_OLD, PERMISSION, CANCELLED }

/**
 * Self-update for APKs installed from GitHub Releases (the Android side of the Mac app's
 * Sparkle feed): reads [UpdateManifest] from the release feed, downloads the APK to the
 * cache, checks its size and SHA-256, and installs it with a [PackageInstaller] session.
 * Android itself refuses an APK not signed with the installed app's key, and a lower
 * versionCode, so a wrong feed can at worst fail to install.
 *
 * Process-wide: MainActivity and SettingsActivity observe [state]; [AppUpdateReceiver]
 * reports the installer's answer back here.
 */
@SuppressLint("StaticFieldLeak") // only the application context is kept
object SelfUpdater {
    private const val PREFS = "self_update"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_LAST_SUCCESS = "last_success_ms"
    private const val KEY_LAST_FAILURE = "last_failure_ms"
    private const val KEY_MANIFEST = "manifest"
    private const val KEY_DISMISSED = "dismissed_version_code"
    private const val KEY_PERMISSION_PENDING = "permission_pending"
    private const val KEY_INSTALL_STARTED = "install_started_ms"
    private const val KEY_UPDATED_TO = "updated_to"
    const val ACTION_INSTALL_STATUS = "io.sharpscreen.app.action.UPDATE_INSTALL_STATUS"
    private const val DOWNLOAD_DIR = "updates"
    private const val USER_AGENT = "SharpScreen-Android"

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Bumped when something outside [state] (the dismissed version) changes what to show. */
    val refreshTick = MutableStateFlow(0)

    /** Per launch: `--es diag.updateFeedURL <url>` on MainActivity (see onCreate). */
    @Volatile var feedUrlOverride: String? = null

    /** Set by MainActivity while a stream or the demo is on screen: no checks or installs then. */
    @Volatile var streaming: Boolean = false

    /** The resumed activity, used to show the installer's confirmation screen. */
    private var resumed: WeakReference<Activity>? = null
    private var pendingConfirmation: Intent? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Bring back the last offer (until checked again) unless it is now installed.
        val stored = prefs.getString(KEY_MANIFEST, null)?.let { runCatching { UpdateManifest.parse(it) }.getOrNull() }
        if (stored != null && UpdatePolicy.isNewer(stored, installedVersionCode()) && UpdatePolicy.supportsSdk(stored, Build.VERSION.SDK_INT)) {
            _state.value = UpdateState.Available(stored)
        } else if (stored != null) {
            prefs.edit().remove(KEY_MANIFEST).apply()
        }
        scope.launch { cleanDownloads(keep = (state.value as? UpdateState.Available)?.manifest) }
    }

    // ==================== Settings ====================

    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    val feedUrl: String get() = feedUrlOverride ?: UpdateManifest.DEFAULT_FEED_URL

    fun installedVersionCode(): Long =
        try {
            PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
        } catch (_: Exception) {
            0
        }

    fun installedVersionName(): String =
        try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }

    /** Show the home card for this state (not for a version the user dismissed). */
    fun cardVisible(s: UpdateState = state.value): Boolean {
        val m = s.manifestOrNull ?: return false
        return UpdatePolicy.showCard(m, installedVersionCode(), prefs.getLong(KEY_DISMISSED, 0))
    }

    fun dismiss(manifest: UpdateManifest) {
        prefs.edit().putLong(KEY_DISMISSED, manifest.versionCode).apply()
        refreshTick.value++
    }

    // ==================== Checking ====================

    /** From the home screen whenever it is shown idle; does nothing unless due. */
    fun maybeAutoCheck() {
        if (streaming || job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (installedFromPlay()) return // Play updates those (AppUpdater)
        if (!UpdatePolicy.shouldAutoCheck(
                app.packageName,
                autoCheckEnabled,
                now,
                prefs.getLong(KEY_LAST_SUCCESS, 0),
                prefs.getLong(KEY_LAST_FAILURE, 0),
            )
        ) {
            return
        }
        check(manual = false)
    }

    /** Settings ▸ Check for updates: works in any build, ignores the 24 h limit. */
    fun checkNow() {
        if (job?.isActive == true) return
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        val before = _state.value
        if (manual) _state.value = UpdateState.Checking
        job =
            scope.launch {
                val url = feedUrl
                selfDiag("check (${if (manual) "manual" else "auto"}) $url")
                try {
                    val manifest = UpdateManifest.parse(fetchText(url))
                    prefs.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).apply()
                    val installed = installedVersionCode()
                    selfDiag("feed: ${manifest.versionName} (${manifest.versionCode}), installed $installed")
                    when {
                        !UpdatePolicy.isNewer(manifest, installed) -> {
                            prefs.edit().remove(KEY_MANIFEST).apply()
                            _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                            cleanDownloads(keep = null)
                        }
                        !UpdatePolicy.supportsSdk(manifest, Build.VERSION.SDK_INT) -> {
                            prefs.edit().remove(KEY_MANIFEST).apply()
                            _state.value = if (manual) UpdateState.Failed(null, UpdateError.ANDROID_TOO_OLD, manifest.versionName) else UpdateState.Idle
                        }
                        else -> {
                            val edit = prefs.edit().putString(KEY_MANIFEST, manifest.toJson())
                            // Asking again brings back a card the user dismissed.
                            if (manual) edit.remove(KEY_DISMISSED)
                            edit.apply()
                            _state.value = UpdateState.Available(manifest)
                            refreshTick.value++
                            cleanDownloads(keep = manifest)
                        }
                    }
                } catch (e: Exception) {
                    selfDiag("check failed: $e")
                    prefs.edit().putLong(KEY_LAST_FAILURE, System.currentTimeMillis()).apply()
                    // An automatic check fails quietly and keeps whatever was shown.
                    _state.value = if (manual) UpdateState.Failed(null, UpdateError.CHECK, e.message) else before
                }
            }
    }

    // ==================== Download and install ====================

    /** True when an install may start: not streaming, Android lets this app install packages. */
    fun canRequestInstalls(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** The user must allow "install unknown apps"; resume with [onActivityResumed]. */
    fun markPermissionPending() {
        prefs.edit().putBoolean(KEY_PERMISSION_PENDING, true).apply()
    }

    /** Download (or reuse a verified download), verify, install. Call after the permission check. */
    fun downloadAndInstall() {
        val manifest = _state.value.manifestOrNull ?: storedManifest() ?: return
        if (job?.isActive == true) return
        if (streaming) {
            _state.value = UpdateState.Failed(manifest, UpdateError.STREAMING)
            return
        }
        _state.value = UpdateState.Downloading(manifest, -1)
        job =
            scope.launch {
                val apk = apkFile(manifest)
                try {
                    if (ApkVerifier.matches(apk, manifest)) {
                        selfDiag("reusing verified ${apk.name}")
                    } else {
                        download(manifest, apk)
                    }
                } catch (e: VerificationException) {
                    selfDiag("verify failed: ${e.message}")
                    _state.value = UpdateState.Failed(manifest, UpdateError.VERIFY, e.message)
                    return@launch
                } catch (e: Exception) {
                    selfDiag("download failed: $e")
                    _state.value = UpdateState.Failed(manifest, UpdateError.DOWNLOAD, e.message)
                    return@launch
                }
                if (streaming) {
                    // Kept: the next Update press installs it without downloading again.
                    _state.value = UpdateState.Failed(manifest, UpdateError.STREAMING)
                    return@launch
                }
                _state.value = UpdateState.Installing(manifest)
                try {
                    install(apk)
                } catch (e: Exception) {
                    selfDiag("install failed to start: $e")
                    _state.value = UpdateState.Failed(manifest, UpdateError.INSTALL, e.message)
                }
            }
    }

    private class VerificationException(
        message: String,
    ) : IOException(message)

    private fun storedManifest(): UpdateManifest? = prefs.getString(KEY_MANIFEST, null)?.let { runCatching { UpdateManifest.parse(it) }.getOrNull() }

    private fun downloadDir(): File = File(app.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }

    private fun apkFile(manifest: UpdateManifest) = File(downloadDir(), "SharpScreen-${manifest.versionCode}.apk")

    /** Deletes every download but [keep]'s APK (finished or partial). */
    private fun cleanDownloads(keep: UpdateManifest?) {
        val keepNames = keep?.let { setOf(apkFile(it).name, apkFile(it).name + ".part") }.orEmpty()
        File(app.cacheDir, DOWNLOAD_DIR).listFiles()?.forEach { f ->
            if (f.name !in keepNames) {
                selfDiag("removing old download ${f.name}")
                f.delete()
            }
        }
    }

    private fun download(
        manifest: UpdateManifest,
        target: File,
    ) {
        val part = File(target.path + ".part")
        part.delete()
        target.delete()
        selfDiag("downloading ${manifest.apkUrl}")
        open(manifest.apkUrl, readTimeoutMs = 30_000).use { (conn, input) ->
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: manifest.size
            if (total != manifest.size) throw VerificationException("size ${conn.contentLengthLong} != ${manifest.size}")
            var done = 0L
            var lastPercent = -1
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done > manifest.size) throw VerificationException("larger than ${manifest.size} bytes")
                    val percent = (done * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _state.value = UpdateState.Downloading(manifest, percent)
                    }
                }
            }
        }
        if (!ApkVerifier.matches(part, manifest)) {
            part.delete()
            throw VerificationException("size or SHA-256 does not match the manifest")
        }
        if (!part.renameTo(target)) throw IOException("rename failed")
        selfDiag("downloaded and verified ${target.name} (${target.length()} bytes)")
    }

    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: an app updating itself may skip the confirmation screen.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val intent =
                Intent(app, AppUpdateReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .setPackage(app.packageName)
            val flags =
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            prefs.edit().putLong(KEY_INSTALL_STARTED, System.currentTimeMillis()).apply()
            selfDiag("committing install session $sessionId")
            session.commit(pending.intentSender)
        }
    }

    // ==================== Installer callbacks (AppUpdateReceiver) ====================

    internal fun onInstallStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        selfDiag("install status $status ${message.orEmpty()}")
        val manifest = _state.value.manifestOrNull ?: storedManifest()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    } ?: return
                val activity = resumed?.get()
                if (activity != null && !activity.isFinishing) {
                    activity.startActivity(confirm)
                } else {
                    // Shown when SharpScreen comes back to the front.
                    pendingConfirmation = confirm
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Usually unseen: Android restarts the app as the new version.
                selfDiag("installed")
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                prefs.edit().remove(KEY_INSTALL_STARTED).apply()
                _state.value = UpdateState.Failed(manifest, UpdateError.CANCELLED)
            }
            else -> {
                prefs.edit().remove(KEY_INSTALL_STARTED).apply()
                _state.value = UpdateState.Failed(manifest, UpdateError.INSTALL, message ?: "status $status")
            }
        }
    }

    /**
     * The new version is installed (MY_PACKAGE_REPLACED, in the new process). Clears the
     * downloads and, when the update was started from the app a moment ago, reopens it.
     */
    internal fun onPackageReplaced(context: Context) {
        init(context)
        cleanDownloads(keep = null)
        val started = prefs.getLong(KEY_INSTALL_STARTED, 0)
        val recent = started > 0 && System.currentTimeMillis() - started in 0..15 * 60_000L
        prefs.edit()
            .remove(KEY_INSTALL_STARTED)
            .remove(KEY_MANIFEST)
            .putString(KEY_UPDATED_TO, if (recent) installedVersionName() else null)
            .apply()
        selfDiag("package replaced; now ${installedVersionName()}, started from app: $recent")
        if (recent) {
            // Like the launcher icon: brings back the existing task (some Android builds have
            // already reopened it) instead of stacking a second MainActivity. Android may
            // refuse this from the background; the app then simply stays closed.
            try {
                val launch =
                    app.packageManager.getLaunchIntentForPackage(app.packageName)
                        ?: Intent(app, MainActivity::class.java)
                app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            } catch (e: Exception) {
                selfDiag("relaunch failed: $e")
            }
        }
    }

    /** The version this app was just updated to from inside the app (once), for a toast. */
    fun takeUpdatedTo(): String? {
        val v = prefs.getString(KEY_UPDATED_TO, null) ?: return null
        prefs.edit().remove(KEY_UPDATED_TO).apply()
        return v
    }

    // ==================== Activity hooks ====================

    /**
     * Call from onResume: shows a confirmation screen the installer asked for while the app
     * was in the background, and continues the update when the user comes back from the
     * "install unknown apps" screen.
     */
    fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        pendingConfirmation?.let {
            pendingConfirmation = null
            activity.startActivity(it)
        }
        if (prefs.getBoolean(KEY_PERMISSION_PENDING, false)) {
            prefs.edit().remove(KEY_PERMISSION_PENDING).apply()
            if (canRequestInstalls()) {
                downloadAndInstall()
            } else {
                val m = _state.value.manifestOrNull ?: storedManifest()
                _state.value = UpdateState.Failed(m, UpdateError.PERMISSION)
            }
        }
    }

    fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    // ==================== HTTP ====================

    private fun installedFromPlay(): Boolean =
        try {
            val installer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    app.packageManager.getInstallerPackageName(app.packageName)
                }
            installer == "com.android.vending"
        } catch (_: Exception) {
            false
        }

    private fun fetchText(url: String): String =
        open(url, readTimeoutMs = 15_000).use { (_, input) ->
            val bytes = input.readNBytesCompat(256 * 1024)
            String(bytes, Charsets.UTF_8)
        }

    private class Response(
        val conn: HttpURLConnection,
        val input: InputStream,
    ) : AutoCloseable {
        operator fun component1() = conn

        operator fun component2() = input

        override fun close() {
            runCatching { input.close() }
            conn.disconnect()
        }
    }

    /**
     * GET with timeouts, following redirects by hand: GitHub's release links redirect to
     * objects.githubusercontent.com, and HttpURLConnection will not follow a protocol change.
     */
    private fun open(
        startUrl: String,
        readTimeoutMs: Int,
    ): Response {
        var url = URL(startUrl)
        repeat(6) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "$USER_AGENT/${installedVersionName()}")
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrEmpty()) throw IOException("HTTP $code without Location")
                url = URL(url, location)
                if (url.protocol != "https" && url.protocol != "http") throw IOException("bad redirect $url")
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            return Response(conn, conn.inputStream)
        }
        throw IOException("too many redirects")
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("feed larger than $limit bytes")
        }
        return out.toByteArray()
    }
}

/** Install status from the PackageInstaller session, and MY_PACKAGE_REPLACED after an update. */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        SelfUpdater.init(context)
        when (intent.action) {
            SelfUpdater.ACTION_INSTALL_STATUS -> SelfUpdater.onInstallStatus(intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> SelfUpdater.onPackageReplaced(context)
        }
    }
}
