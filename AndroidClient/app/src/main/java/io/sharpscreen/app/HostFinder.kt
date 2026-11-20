package io.sharpscreen.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Finds the paired Mac when its address changed (issue #17): home, office.
 *
 * 1. The address it answered on last time.
 * 2. Other addresses it answered on before, and at the same time the local
 *    network (mDNS/DNS-SD, "_sharpscreen._tcp"), where only addresses the Mac
 *    signed with this pairing's token count (HostProof).
 *
 * A reachability check only opens and closes a TCP connection; the token is
 * sent once the caller connects for real.
 */
object HostFinder {
    private const val TAG = "HostFinder"
    const val SERVICE_TYPE = "_sharpscreen._tcp"
    private const val PROBE_TIMEOUT_MS = 600
    private const val DISCOVERY_TIMEOUT_MS = 4_000L
    private const val PREFER_DISCOVERY_MS = 1_500L

    suspend fun locate(
        context: Context,
        token: ByteArray,
        known: List<Pair<String, Int>>,
    ): Pair<String, Int>? =
        coroutineScope {
            val last = known.firstOrNull()
            if (last != null && probe(last.first, last.second)) return@coroutineScope last
            val discovery = async { discover(context, token) }
            val remembered = firstReachable(known.drop(1))
            if (remembered != null) {
                // A signed address from the network wins if it comes soon.
                val found = withTimeoutOrNull(PREFER_DISCOVERY_MS) { discovery.await() }
                discovery.cancel()
                return@coroutineScope found ?: remembered
            }
            discovery.await()
        }

    suspend fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                true
            } catch (_: Exception) {
                false
            }
        }

    /** The first of [candidates] (in order) that accepts a connection, all tried at once. */
    suspend fun firstReachable(candidates: List<Pair<String, Int>>): Pair<String, Int>? =
        coroutineScope {
            candidates.map { c -> async { if (probe(c.first, c.second)) c else null } }
                .firstNotNullOfOrNull { it.await() }
        }

    /** A reachable address of the Mac holding [token], from the local network; null after the timeout. */
    suspend fun discover(
        context: Context,
        token: ByteArray,
    ): Pair<String, Int>? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    found.trySend(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "discovery failed to start: $errorCode")
                    found.close()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit
            }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discovery unavailable", e)
            return null
        }
        try {
            return withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                for (info in found) {
                    val resolved = resolve(nsd, info) ?: continue
                    val a = resolved.attributes["a"]?.toString(Charsets.UTF_8) ?: continue
                    val signed = HostProof.verifiedAddresses(token, a, resolved.port)
                    if (signed.isEmpty()) {
                        Log.i(TAG, "\"${info.serviceName}\": not the paired Mac")
                        continue
                    }
                    val hit = firstReachable(signed.map { it to resolved.port })
                    if (hit != null) {
                        Log.i(TAG, "found the paired Mac \"${info.serviceName}\" at ${hit.first}:${hit.second}")
                        return@withTimeoutOrNull hit
                    }
                }
                null
            }
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION") // resolveService: one at a time, which is all this needs.
    private suspend fun resolve(
        nsd: NsdManager,
        info: NsdServiceInfo,
    ): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                },
            )
        }
}
