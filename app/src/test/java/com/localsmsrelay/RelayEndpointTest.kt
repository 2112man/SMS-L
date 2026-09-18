package com.localsmsrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEndpointTest {
    @Test
    fun bareHostnameGetsWssSchemeAndWsPath() {
        assertEquals(
            "wss://sms-l-relay.abc.workers.dev/ws",
            RelayEndpoint.normalize("sms-l-relay.abc.workers.dev")
        )
    }

    @Test
    fun httpsIsUpgradedToWss() {
        assertEquals("wss://relay.example.com/ws", RelayEndpoint.normalize("https://relay.example.com"))
    }

    @Test
    fun trailingSlashBecomesWsPath() {
        assertEquals("wss://relay.example.com/ws", RelayEndpoint.normalize("https://relay.example.com/"))
    }

    @Test
    fun explicitWsPathIsPreserved() {
        assertEquals("wss://relay.example.com/ws", RelayEndpoint.normalize("wss://relay.example.com/ws"))
        assertEquals("ws://127.0.0.1:8787/ws", RelayEndpoint.normalize("ws://127.0.0.1:8787/ws"))
    }

    @Test
    fun httpStaysOnPlainWsForLocalDevelopment() {
        assertEquals("ws://127.0.0.1:8787/ws", RelayEndpoint.normalize("http://127.0.0.1:8787"))
    }

    @Test
    fun customPathIsKept() {
        assertEquals(
            "wss://relay.example.com/api/socket",
            RelayEndpoint.normalize("https://relay.example.com/api/socket")
        )
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals("wss://relay.example.com/ws", RelayEndpoint.normalize("  relay.example.com  "))
    }

    @Test
    fun rejectsEmptyOrUnsupportedInput() {
        assertNull(RelayEndpoint.normalize(""))
        assertNull(RelayEndpoint.normalize("   "))
        assertNull(RelayEndpoint.normalize("ftp://relay.example.com"))
        assertNull(RelayEndpoint.normalize("https://"))
        assertFalse(RelayEndpoint.isUsable("not a url at all"))
    }

    @Test
    fun usableReflectsNormalizationResult() {
        assertTrue(RelayEndpoint.isUsable("relay.example.com"))
        assertTrue(RelayEndpoint.isUsable("wss://relay.example.com"))
    }
}
