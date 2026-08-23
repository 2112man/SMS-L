package com.localsmsrelay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

data class WifiNetworkCandidate(
    val isWifi: Boolean,
    val isVpn: Boolean,
    val isNotVpn: Boolean,
    val addresses: List<InetAddress>
)

object PhysicalWifiSelector {
    fun selectIpv4(candidates: List<WifiNetworkCandidate>): String? = candidates
        .asSequence()
        .filter { it.isWifi && !it.isVpn }
        .sortedByDescending { it.isNotVpn }
        .flatMap { it.addresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
}

object WifiIpResolver {
    @Suppress("DEPRECATION")
    fun currentWifiIpv4(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            WifiNetworkCandidate(
                isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                isNotVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                addresses = properties.linkAddresses.map { it.address }
            )
        }
        return PhysicalWifiSelector.selectIpv4(candidates)
    }
}
