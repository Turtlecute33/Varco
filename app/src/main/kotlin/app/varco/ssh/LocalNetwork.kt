package app.varco.ssh

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android 17 blocks the local network for apps that target SDK 37 until the user allows "Nearby devices"
 * (`ACCESS_LOCAL_NETWORK`). A blocked TCP connection only times out. So Varco asks before it connects to a
 * host that looks local, and the time-out message names the permission (DESIGN §Local network).
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Always true on Android 16 and lower: there, the Internet permission includes the local network. */
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * A guess from the text only, because the UI thread must not do a DNS lookup: a private or link-local IP
     * address, a name without a dot, or a name in a local-use domain. A public name with a LAN address is not
     * found here; the time-out message covers it.
     */
    fun looksLocal(host: String): Boolean {
        val h = host.trim().removeSurrounding("[", "]").substringBefore('%').lowercase().removeSuffix(".")
        if (h.isEmpty() || h == "localhost") return false
        if (IP_LITERAL.matches(h)) {
            // A literal never causes a lookup. IPv4 needs four parts: getByName would read "10.1" as 10.0.0.1.
            if (':' !in h && h.count { it == '.' } != 3) return false
            val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
            return address.isSiteLocalAddress || address.isLinkLocalAddress || address.isUniqueLocal()
        }
        return '.' !in h || LOCAL_DOMAINS.any { h.endsWith(it) }
    }

    /** fc00::/7 (RFC 4193). */
    private fun InetAddress.isUniqueLocal() = this is Inet6Address && (address[0].toInt() and 0xFE) == 0xFC

    private val IP_LITERAL = Regex("[0-9.]+|[0-9a-f:.]*:[0-9a-f:.]*")

    /** mDNS, common router domains, RFC 8375 and the ICANN private-use TLD. */
    private val LOCAL_DOMAINS = listOf(".local", ".lan", ".home.arpa", ".internal")
}
