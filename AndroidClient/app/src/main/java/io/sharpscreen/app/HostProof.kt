package io.sharpscreen.app

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The Mac's proof that it holds a pairing token, in its local network
 * announcement (issue #17, HostAdvertiser on the Mac): for each of its IPv4
 * addresses, HMAC-SHA256(token, "SharpScreen host v1|<ip>|<port>"), first 12
 * bytes, base64url without padding. The tablet sends its token in the clear
 * when it connects, so it only connects to addresses signed with that token.
 */
object HostProof {
    fun proof(
        token: ByteArray,
        ip: String,
        port: Int,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token, "HmacSHA256"))
        val digest = mac.doFinal("SharpScreen host v1|$ip|$port".toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(12))
    }

    /** The "a" TXT value: "ip=proof,ip=proof". */
    fun parseAddresses(value: String): List<Pair<String, String>> =
        value.split(',').mapNotNull { item ->
            val i = item.lastIndexOf('=')
            if (i <= 0 || i == item.length - 1) null else item.substring(0, i) to item.substring(i + 1)
        }

    /** Addresses in [value] that this token signed for [port], in the Mac's order. */
    fun verifiedAddresses(
        token: ByteArray,
        value: String,
        port: Int,
    ): List<String> = parseAddresses(value).filter { (ip, p) -> proof(token, ip, port) == p }.map { it.first }
}
