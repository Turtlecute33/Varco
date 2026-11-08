package app.varco.ssh

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkTest {
    @Test
    fun privateAndLinkLocalAddressesAreLocal() {
        for (host in listOf("192.168.1.20", "10.0.0.5", "172.16.4.1", "169.254.3.3", "fe80::1", "fe80::1%wlan0", "fd12:3456::1", "[fd00::22]")) {
            assertTrue(LocalNetwork.looksLocal(host), host)
        }
    }

    @Test
    fun publicAndLoopbackAddressesAreNotLocal() {
        for (host in listOf("203.0.113.7", "172.32.0.1", "100.64.0.7", "2001:db8::1", "127.0.0.1", "::1", "10.1")) {
            assertFalse(LocalNetwork.looksLocal(host), host)
        }
    }

    @Test
    fun localNamesAreLocal() {
        for (host in listOf("nas", "server.lan", "Pi.Local", "box.home.arpa", "git.internal", "server.lan.")) {
            assertTrue(LocalNetwork.looksLocal(host), host)
        }
    }

    @Test
    fun publicNamesAndLocalhostAreNotLocal() {
        for (host in listOf("ssh.example.com", "localhost", "lan.example.org", "")) {
            assertFalse(LocalNetwork.looksLocal(host), host)
        }
    }
}
