package io.sharpscreen.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

class PairedHostStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("paired_host", Context.MODE_PRIVATE)

    data class Entry(val host: String, val port: Int, val token: ByteArray, val macName: String) {
        override fun equals(other: Any?): Boolean {
            if (other !is Entry) return false
            return host == other.host && port == other.port && macName == other.macName &&
                token.contentEquals(other.token)
        }

        override fun hashCode(): Int =
            ((host.hashCode() * 31 + port) * 31 + macName.hashCode()) * 31 + token.contentHashCode()
    }

    fun save(entry: Entry) {
        // Another Mac (another token): its addresses are not this one's.
        val sameMac = load()?.token?.contentEquals(entry.token) == true
        prefs.edit()
            .apply { if (!sameMac) remove("known_hosts") }
            .putString("host", entry.host)
            .putInt("port", entry.port)
            .putString("token_b64", Base64.encodeToString(entry.token, Base64.NO_WRAP or Base64.NO_PADDING))
            .putString("mac_name", entry.macName)
            .apply()
    }

    fun load(): Entry? {
        val host = prefs.getString("host", null) ?: return null
        val port = prefs.getInt("port", -1).takeIf { it > 0 } ?: return null
        val tokenB64 = prefs.getString("token_b64", null) ?: return null
        val token =
            try {
                Base64.decode(tokenB64, Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (e: IllegalArgumentException) {
                return null
            }
        if (token.size != 32) return null
        val macName = prefs.getString("mac_name", null) ?: "Mac"
        return Entry(host, port, token, macName)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Addresses ("host:port") this Mac answered on before, most recent first
     * (issue #17): home, office, ... so moving between them needs no new
     * pairing. Includes the current host.
     */
    fun knownAddresses(): List<Pair<String, Int>> {
        val current = load()
        return (listOfNotNull(current?.let { it.host to it.port }) + rememberedAddresses()).distinct()
    }

    /** Only addresses the Mac answered on (rememberAddress), most recent first. */
    private fun rememberedAddresses(): List<Pair<String, Int>> =
        prefs.getString("known_hosts", null)
            ?.split('\n')
            ?.mapNotNull { parseAddress(it) }
            .orEmpty()

    /** The Mac answered on [host]:[port]: make it the current address and remember it. */
    fun rememberAddress(
        host: String,
        port: Int,
    ) {
        val entry = load() ?: return
        val list = (listOf(host to port) + rememberedAddresses()).distinct().take(MAX_KNOWN)
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("known_hosts", list.joinToString("\n") { "${it.first}:${it.second}" })
            .apply()
        if (entry.host != host || entry.port != port) {
            android.util.Log.i("PairedHostStorage", "Mac now at $host:$port (was ${entry.host}:${entry.port})")
        }
    }

    private fun parseAddress(s: String): Pair<String, Int>? {
        val i = s.lastIndexOf(':')
        if (i <= 0) return null
        val port = s.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return s.substring(0, i) to port
    }

    companion object {
        const val MAX_KNOWN = 5
    }
}
