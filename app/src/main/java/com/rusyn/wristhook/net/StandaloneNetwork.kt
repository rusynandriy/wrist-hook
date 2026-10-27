package com.rusyn.wristhook.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gets the watch onto its own Wi-Fi or LTE when the phone isn't there.
 *
 * With the phone in Bluetooth range, Wear OS proxies traffic through the phone.
 * When that link drops, the platform is supposed to fail over to Wi-Fi/LTE, but
 * it keeps those radios off to save battery and only brings one up when an app
 * explicitly asks for it (developer.android.com/training/wearables/data/network-access).
 * Until then there's no route and no DNS, which is exactly the "Unable to resolve
 * host … No address associated with hostname" error a watch-only send hits.
 */
object StandaloneNetwork {

    /** How long to wait for Wi-Fi/LTE to come up. Radios can take a while to wake. */
    private const val ACQUIRE_TIMEOUT_MS = 30_000L

    /** True when the current default network can actually reach the internet. */
    fun defaultIsUsable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Asks Wear OS for a Wi-Fi or cellular network, runs [block] on it, then
     * releases the request so the radio can power down again. Returns null if
     * neither radio came up within the timeout (no saved Wi-Fi in range, LTE off
     * or no signal).
     */
    suspend fun <T> withWifiOrCellular(context: Context, block: suspend (Network) -> T): T? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val acquired = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                acquired.complete(network)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Multiple transports = any of them; whichever the watch can raise first.
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        cm.requestNetwork(request, callback)
        try {
            val network = withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) { acquired.await() } ?: return null
            return block(network)
        } finally {
            cm.unregisterNetworkCallback(callback)
        }
    }
}
