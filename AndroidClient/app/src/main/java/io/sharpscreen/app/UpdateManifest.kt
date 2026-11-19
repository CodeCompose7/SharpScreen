package io.sharpscreen.app

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * `android-update.json`, attached to every GitHub Release by scripts/make_android_update.sh
 * and read from [UpdateManifest.DEFAULT_FEED_URL] (GitHub redirects /latest/ to the newest
 * non-prerelease). The APK is only installed when its size and SHA-256 match.
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val notesUrl: String?,
    val minSdk: Int,
) {
    fun toJson(): String =
        JSONObject()
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("apkUrl", apkUrl)
            .put("sha256", sha256)
            .put("size", size)
            .put("notesUrl", notesUrl)
            .put("minSdk", minSdk)
            .toString()

    companion object {
        const val DEFAULT_FEED_URL =
            "https://github.com/CodeCompose7/SharpScreen/releases/latest/download/android-update.json"

        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

        /** Parses and validates a manifest; throws [IllegalArgumentException] when it is unusable. */
        fun parse(text: String): UpdateManifest {
            val o =
                try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    throw IllegalArgumentException("not a JSON object: ${e.message}")
                }

            fun str(key: String): String =
                o.optString(key, "").trim().ifEmpty { throw IllegalArgumentException("missing $key") }

            fun long(key: String): Long {
                if (!o.has(key)) throw IllegalArgumentException("missing $key")
                return when (val v = o.get(key)) {
                    is Number -> v.toLong()
                    is String -> v.trim().toLongOrNull() ?: throw IllegalArgumentException("bad $key")
                    else -> throw IllegalArgumentException("bad $key")
                }
            }

            val versionCode = long("versionCode")
            require(versionCode > 0) { "bad versionCode" }
            val apkUrl = str("apkUrl")
            require(apkUrl.startsWith("https://") || apkUrl.startsWith("http://")) { "bad apkUrl" }
            val sha256 = str("sha256")
            require(SHA256_HEX.matches(sha256)) { "bad sha256" }
            val size = long("size")
            require(size > 0) { "bad size" }
            val notesUrl = o.optString("notesUrl", "").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val minSdk = if (o.has("minSdk")) long("minSdk").toInt() else 0
            return UpdateManifest(
                versionCode = versionCode,
                versionName = str("versionName"),
                apkUrl = apkUrl,
                sha256 = sha256.lowercase(),
                size = size,
                notesUrl = notesUrl,
                minSdk = minSdk,
            )
        }
    }
}

/** Pure decisions behind the update check, kept free of Android so they can be unit tested. */
object UpdatePolicy {
    const val RELEASE_PACKAGE = "io.sharpscreen.app"
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** After a failed automatic check, wait this long before the next attempt. */
    const val RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000

    /** The manifest describes a build newer than the installed one. */
    fun isNewer(
        manifest: UpdateManifest,
        installedVersionCode: Long,
    ): Boolean = manifest.versionCode > installedVersionCode

    /** The manifest's APK can run on this device's Android version. */
    fun supportsSdk(
        manifest: UpdateManifest,
        sdkInt: Int,
    ): Boolean = manifest.minSdk <= sdkInt

    /**
     * Whether the home screen should check by itself now: only the release package (a debug
     * build has another package name and must never be offered the release APK), only when
     * the user left automatic checks on, at most once per [AUTO_CHECK_INTERVAL_MS] after a
     * success and once per [RETRY_AFTER_FAILURE_MS] after a failure. A timestamp in the
     * future (the clock was set back) counts as due.
     */
    fun shouldAutoCheck(
        packageName: String,
        enabled: Boolean,
        nowMs: Long,
        lastSuccessMs: Long,
        lastFailureMs: Long,
    ): Boolean {
        if (packageName != RELEASE_PACKAGE || !enabled) return false
        fun due(
            last: Long,
            interval: Long,
        ) = last <= 0 || last > nowMs || nowMs - last >= interval
        return due(lastSuccessMs, AUTO_CHECK_INTERVAL_MS) && due(lastFailureMs, RETRY_AFTER_FAILURE_MS)
    }

    /** "SharpScreen 1.2.3 is available" card: hidden for the version the user dismissed. */
    fun showCard(
        manifest: UpdateManifest,
        installedVersionCode: Long,
        dismissedVersionCode: Long,
    ): Boolean = isNewer(manifest, installedVersionCode) && manifest.versionCode != dismissedVersionCode
}

/** SHA-256 checks for downloaded APKs. */
object ApkVerifier {
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The file has exactly the manifest's size and SHA-256. */
    fun matches(
        file: File,
        manifest: UpdateManifest,
    ): Boolean {
        if (!file.isFile || file.length() != manifest.size) return false
        val actual = file.inputStream().use { sha256Hex(it) }
        return actual.equals(manifest.sha256, ignoreCase = true)
    }
}
