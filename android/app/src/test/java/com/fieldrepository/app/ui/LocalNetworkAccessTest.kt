package com.fieldrepository.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHICH DEVELOPMENT BACKENDS ANDROID 17 HIDES BEHIND A PERMISSION, AND WHICH BUILD ASKS FOR IT.
 *
 * targetSdk 37 makes a connection to a local-network address TIME OUT unless the app holds
 * ACCESS_LOCAL_NETWORK. Only a debug build pointed at a development backend ever makes one, and the
 * rule that decides when the app asks is a pure function, so it is pinned here rather than left to a
 * developer who meets a backend that "is down" on the first Android 17 emulator. Two failures matter
 * and the cases below are chosen for them: asking for a permission the production build never needs
 * (a public host, or a release build), and NOT asking for the one address every emulator uses.
 */
class LocalNetworkAccessTest {

    @Test
    fun `the emulator's host alias and the RFC 1918 ranges are the local network`() {
        assertTrue(isLocalNetworkHost("10.0.2.2"))
        assertTrue(isLocalNetworkHost("192.168.1.20"))
        assertTrue(isLocalNetworkHost("172.16.0.5"))
        assertTrue(isLocalNetworkHost("172.31.255.254"))
        assertFalse("172.32/16 is public, not 172.16/12", isLocalNetworkHost("172.32.0.1"))
        assertFalse("172.15/16 is public, not 172.16/12", isLocalNetworkHost("172.15.0.1"))
    }

    @Test
    fun `link-local, carrier NAT and mDNS names are the local network too`() {
        assertTrue(isLocalNetworkHost("169.254.10.1"))
        assertTrue(isLocalNetworkHost("100.64.0.1"))
        assertTrue(isLocalNetworkHost("100.127.255.255"))
        assertFalse("100.128/16 is past the /10", isLocalNetworkHost("100.128.0.1"))
        assertTrue(isLocalNetworkHost("devbox.local"))
    }

    @Test
    fun `IPv6 link-local and unique-local are local, in brackets or with a zone`() {
        assertTrue(isLocalNetworkHost("fe80::1"))
        assertTrue(isLocalNetworkHost("[fe80::1]"))
        assertTrue(isLocalNetworkHost("fe80::1%wlan0"))
        assertTrue(isLocalNetworkHost("febf::1"))
        assertTrue(isLocalNetworkHost("fd12:3456::1"))
        assertTrue(isLocalNetworkHost("::ffff:192.168.1.5"))
        assertFalse(isLocalNetworkHost("2001:db8::1"))
        assertFalse(isLocalNetworkHost("::ffff:8.8.8.8"))
    }

    @Test
    fun `loopback is not the local network, which is why adb reverse needs no permission`() {
        assertFalse(isLocalNetworkHost("127.0.0.1"))
        assertFalse(isLocalNetworkHost("localhost"))
        assertFalse(isLocalNetworkHost("::1"))
        assertFalse(isLocalNetworkHost("[::1]"))
    }

    @Test
    fun `production and other public hosts are never local, and names are never resolved`() {
        assertFalse(isLocalNetworkHost("d2b34i3e92al6i.cloudfront.net"))
        assertFalse(isLocalNetworkHost("8.8.8.8"))
        // Not an address at all: classified without a DNS lookup, and therefore not local.
        assertFalse(isLocalNetworkHost("devbox.lan"))
        assertFalse(isLocalNetworkHost("999.1.1.1"))
        assertFalse(isLocalNetworkHost("10.0.2"))
        assertFalse(isLocalNetworkHost(""))
    }

    @Test
    fun `only a debug build on Android 17 or later, aimed at a local address, asks`() {
        val emulator = "http://10.0.2.2:8000/api/"
        assertTrue(needsLocalNetworkPermission(debug = true, sdkInt = 37, apiBaseUrl = emulator))
        assertTrue(needsLocalNetworkPermission(debug = true, sdkInt = 38, apiBaseUrl = emulator))
        assertFalse(
            "release does not declare the permission, so it must never ask for it",
            needsLocalNetworkPermission(debug = false, sdkInt = 37, apiBaseUrl = emulator),
        )
        assertFalse(
            "Android 16 grants it implicitly to any app holding INTERNET",
            needsLocalNetworkPermission(debug = true, sdkInt = 36, apiBaseUrl = emulator),
        )
        assertFalse(
            "the compiled-in production base must never prompt",
            needsLocalNetworkPermission(true, 37, "https://d2b34i3e92al6i.cloudfront.net/api/"),
        )
        assertFalse(needsLocalNetworkPermission(true, 37, "http://127.0.0.1:8000/api/"))
        assertTrue(needsLocalNetworkPermission(true, 37, "http://[fe80::1]:8000/api/"))
        assertFalse("an unparseable base is not a reason to prompt", needsLocalNetworkPermission(true, 37, "::not a url::"))
    }
}
