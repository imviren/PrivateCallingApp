package com.p2p.core.network

import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.Inet6Address
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

data class TailscaleAddresses(
    val ipv4: String? = null,
    val ipv6: String? = null
) {
    fun summary(): String {
        return buildString {
            if (ipv4 != null) append("IPv4: $ipv4\n")
            if (ipv6 != null) append("IPv6: $ipv6")
            if (isEmpty()) append("Tailscale IP not detected. Is VPN running?")
        }
    }

    fun isEmpty() = ipv4 == null && ipv6 == null
}

@Singleton
class TailscaleDetector @Inject constructor() {

    fun getAddresses(): TailscaleAddresses {
        var ipv4: String? = null
        var ipv6: String? = null

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // On some Android builds, Tailscale uses interface named "tun*" or "tailscale*"
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.isLoopbackAddress) continue

                    val ip = addr.hostAddress ?: continue
                    if (addr is Inet4Address) {
                        // Tailscale IPv4 addresses are in CGNAT 100.64.0.0/10 range (100.64.x.x - 100.127.x.x)
                        if (isTailscaleIpv4(ip)) {
                            ipv4 = ip
                        }
                    } else if (addr is Inet6Address) {
                        // Tailscale IPv6 addresses are in fd7a:115c:a1e0::/48 range
                        if (isTailscaleIpv6(ip)) {
                            // Strip scope id if present (e.g. %tun0)
                            ipv6 = ip.substringBefore("%")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("TailscaleDetector").e(e, "Error detecting Tailscale addresses")
        }

        return TailscaleAddresses(ipv4, ipv6)
    }

    private fun isTailscaleIpv4(ip: String): Boolean {
        // Range check 100.64.0.0 to 100.127.255.255
        if (!ip.startsWith("100.")) return false
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val secondOctet = parts[1].toIntOrNull() ?: return false
        return secondOctet in 64..127
    }

    private fun isTailscaleIpv6(ip: String): Boolean {
        return ip.lowercase().startsWith("fd7a:115c:a1e0:")
    }
}
