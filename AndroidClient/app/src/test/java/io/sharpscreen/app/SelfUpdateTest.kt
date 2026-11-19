package io.sharpscreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class SelfUpdateTest {
    private val sha = "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447"

    private val json =
        """
        {"versionCode": 302, "versionName": "0.3.2",
         "apkUrl": "https://github.com/CodeCompose7/SharpScreen/releases/download/v0.3.2/SharpScreen-0.3.2.apk",
         "sha256": "$sha", "size": 12345678,
         "notesUrl": "https://github.com/CodeCompose7/SharpScreen/releases/tag/v0.3.2", "minSdk": 26}
        """.trimIndent()

    private fun manifest(
        versionCode: Long = 302,
        sha256: String = sha,
        size: Long = 12345678,
    ) = UpdateManifest(versionCode, "0.3.2", "https://example.com/a.apk", sha256, size, null, 26)

    // ==================== Manifest parsing ====================

    @Test
    fun parsesTheReleaseManifest() {
        val m = UpdateManifest.parse(json)
        assertEquals(302L, m.versionCode)
        assertEquals("0.3.2", m.versionName)
        assertEquals("https://github.com/CodeCompose7/SharpScreen/releases/download/v0.3.2/SharpScreen-0.3.2.apk", m.apkUrl)
        assertEquals(sha, m.sha256)
        assertEquals(12345678L, m.size)
        assertEquals("https://github.com/CodeCompose7/SharpScreen/releases/tag/v0.3.2", m.notesUrl)
        assertEquals(26, m.minSdk)
    }

    @Test
    fun roundTripsThroughToJson() {
        val m = UpdateManifest.parse(json)
        assertEquals(m, UpdateManifest.parse(m.toJson()))
    }

    @Test
    fun toleratesOptionalFieldsAndUppercaseHash() {
        val m =
            UpdateManifest.parse(
                """{"versionCode": "10203", "versionName": "1.2.3", "apkUrl": "http://127.0.0.1:8766/a.apk",
                   "sha256": "${sha.uppercase()}", "size": 1, "extra": true}""",
            )
        assertEquals(10203L, m.versionCode)
        assertEquals(sha, m.sha256) // stored lowercase
        assertNull(m.notesUrl)
        assertEquals(0, m.minSdk)
    }

    @Test
    fun rejectsBrokenManifests() {
        fun with(change: JSONObject.() -> Unit) = JSONObject(json).apply(change).toString()
        val broken =
            listOf(
                "",
                "not json",
                "[]",
                with { put("versionCode", 0) },
                with { remove("versionCode") },
                with { put("versionCode", "abc") },
                with { put("versionName", "") },
                with { remove("apkUrl") },
                with { put("apkUrl", "file:///sdcard/a.apk") },
                with { put("sha256", "abc") },
                with { put("sha256", sha.dropLast(1) + "g") },
                with { put("size", 0) },
                with { remove("size") },
            )
        for (text in broken) {
            try {
                UpdateManifest.parse(text)
                fail("accepted: $text")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    // ==================== Version comparison ====================

    @Test
    fun offersOnlyNewerVersionCodes() {
        assertTrue(UpdatePolicy.isNewer(manifest(302), 301))
        assertFalse(UpdatePolicy.isNewer(manifest(302), 302))
        assertFalse(UpdatePolicy.isNewer(manifest(302), 400))
        // 0.10.0 (1000) is newer than 0.9.9 (909): compared as numbers, not strings.
        assertTrue(UpdatePolicy.isNewer(manifest(1000), 909))
    }

    @Test
    fun respectsMinSdk() {
        assertTrue(UpdatePolicy.supportsSdk(manifest(), 26))
        assertTrue(UpdatePolicy.supportsSdk(manifest(), 36))
        assertFalse(UpdatePolicy.supportsSdk(manifest(), 25))
    }

    @Test
    fun cardHidesTheDismissedVersionOnly() {
        assertTrue(UpdatePolicy.showCard(manifest(302), 301, 0))
        assertFalse(UpdatePolicy.showCard(manifest(302), 301, 302))
        assertTrue(UpdatePolicy.showCard(manifest(303), 301, 302)) // a later release shows again
        assertFalse(UpdatePolicy.showCard(manifest(302), 302, 0))
    }

    // ==================== SHA-256 verification ====================

    @Test
    fun sha256OfKnownInput() {
        assertEquals(sha, ApkVerifier.sha256Hex(ByteArrayInputStream("hello world\n".toByteArray())))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApkVerifier.sha256Hex(ByteArrayInputStream(ByteArray(0))),
        )
    }

    @Test
    fun verifiesSizeAndHash() {
        val f = File.createTempFile("selfupdate", ".apk")
        try {
            f.writeText("hello world\n")
            assertTrue(ApkVerifier.matches(f, manifest(sha256 = sha, size = 12)))
            assertTrue(ApkVerifier.matches(f, manifest(sha256 = sha.uppercase(), size = 12)))
            assertFalse(ApkVerifier.matches(f, manifest(sha256 = sha, size = 13))) // size differs
            assertFalse(ApkVerifier.matches(f, manifest(sha256 = sha.replace('3', '4'), size = 12))) // hash differs
            f.writeText("hello world!")
            assertFalse(ApkVerifier.matches(f, manifest(sha256 = sha, size = 12))) // same size, other bytes
            assertFalse(ApkVerifier.matches(File(f.path + ".missing"), manifest(sha256 = sha, size = 12)))
        } finally {
            f.delete()
        }
    }

    // ==================== 24 h throttle ====================

    private val day = UpdatePolicy.AUTO_CHECK_INTERVAL_MS
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L
    private val release = UpdatePolicy.RELEASE_PACKAGE

    @Test
    fun autoCheckAtMostOncePerDay() {
        assertTrue(UpdatePolicy.shouldAutoCheck(release, true, now, 0, 0)) // never checked
        assertFalse(UpdatePolicy.shouldAutoCheck(release, true, now, now - hour, 0))
        assertFalse(UpdatePolicy.shouldAutoCheck(release, true, now, now - day + 1, 0))
        assertTrue(UpdatePolicy.shouldAutoCheck(release, true, now, now - day, 0))
        assertTrue(UpdatePolicy.shouldAutoCheck(release, true, now, now + hour, 0)) // clock set back
    }

    @Test
    fun autoCheckRetriesAnHourAfterAFailure() {
        assertFalse(UpdatePolicy.shouldAutoCheck(release, true, now, now - 2 * day, now - 10 * 60_000))
        assertTrue(UpdatePolicy.shouldAutoCheck(release, true, now, now - 2 * day, now - hour))
    }

    @Test
    fun autoCheckOnlyForTheReleasePackageWhenEnabled() {
        assertFalse(UpdatePolicy.shouldAutoCheck(release, false, now, 0, 0))
        assertFalse(UpdatePolicy.shouldAutoCheck("io.sharpscreen.app.debug", true, now, 0, 0))
    }
}
