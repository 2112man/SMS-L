package com.localsmsrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class PhysicalWifiSelectorTest {
    @Test
    fun rejectsVpnTunEvenWhenItAlsoReportsWifiTransport() {
        val candidates = listOf(
            candidate(isWifi = true, isVpn = true, isNotVpn = false, "172.19.0.1"),
            candidate(isWifi = true, isVpn = false, isNotVpn = true, "192.168.31.88")
        )

        assertEquals("192.168.31.88", PhysicalWifiSelector.selectIpv4(candidates))
    }

    @Test
    fun rejectsCellularLoopbackLinkLocalAndIpv6() {
        val candidates = listOf(
            candidate(isWifi = false, isVpn = false, isNotVpn = true, "10.20.30.40"),
            candidate(
                isWifi = true,
                isVpn = false,
                isNotVpn = true,
                "127.0.0.1",
                "169.254.10.20",
                "2001:db8::88",
                "192.168.31.88"
            )
        )

        assertEquals("192.168.31.88", PhysicalWifiSelector.selectIpv4(candidates))
    }

    @Test
    fun prioritizesNotVpnPhysicalWifiCandidate() {
        val candidates = listOf(
            candidate(isWifi = true, isVpn = false, isNotVpn = false, "192.168.50.2"),
            candidate(isWifi = true, isVpn = false, isNotVpn = true, "192.168.31.88")
        )

        assertEquals("192.168.31.88", PhysicalWifiSelector.selectIpv4(candidates))
    }

    @Test
    fun returnsNullWhenNoPhysicalWifiIpv4Exists() {
        val candidates = listOf(
            candidate(isWifi = true, isVpn = true, isNotVpn = false, "198.18.0.1"),
            candidate(isWifi = false, isVpn = false, isNotVpn = true, "10.0.0.2")
        )

        assertNull(PhysicalWifiSelector.selectIpv4(candidates))
    }

    private fun candidate(
        isWifi: Boolean,
        isVpn: Boolean,
        isNotVpn: Boolean,
        vararg addresses: String
    ) = WifiNetworkCandidate(
        isWifi = isWifi,
        isVpn = isVpn,
        isNotVpn = isNotVpn,
        addresses = addresses.map(InetAddress::getByName)
    )
}

