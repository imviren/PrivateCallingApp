package com.p2p.core.network

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

data class DiscoveredDevice(
    val ip: String,
    val name: String
)

@Singleton
class TailscaleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tailscaleDetector: TailscaleDetector
) {

    private val client = HttpClient(OkHttp)
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val prefs = context.getSharedPreferences("ts_scanner_prefs", Context.MODE_PRIVATE)

    var customRoomName: String?
        get() = prefs.getString("custom_room", null)
        set(value) {
            prefs.edit().putString("custom_room", value).apply()
        }

    suspend fun getActiveRoom(): String? = withContext(Dispatchers.IO) {
        val custom = customRoomName
        if (!custom.isNullOrBlank()) {
            return@withContext custom
        }
        getTailscaleDomain(context)
    }

    suspend fun publishPresence() {
        val addrs = tailscaleDetector.getAddresses()
        val myIp = addrs.ipv4
        if (myIp.isNullOrBlank()) {
            Timber.tag("TailscaleScanner").w("Cannot publish presence: local Tailscale IP is null")
            return
        }

        val room = getActiveRoom()
        if (room == null) {
            Timber.tag("TailscaleScanner").w("Cannot publish presence: Active room (Tailnet domain or custom room) is null")
            return
        }

        val topic = "ts_voip_" + sha256(room)
        val deviceName = resolveHostname(myIp)
        val timestamp = System.currentTimeMillis()
        val message = "$myIp,$deviceName,$timestamp"

        Timber.tag("TailscaleScanner").d("Publishing presence to topic: %s, message: %s", topic, message)

        try {
            client.post("https://ntfy.sh/$topic") {
                setBody(message)
            }
            Timber.tag("TailscaleScanner").d("Successfully published presence to ntfy.sh")
        } catch (e: Exception) {
            Timber.tag("TailscaleScanner").w("Failed to publish presence to ntfy: %s", e.message)
        }
    }

    fun scan(deepScan: Boolean): Flow<List<DiscoveredDevice>> = flow {
        val addrs = tailscaleDetector.getAddresses()
        val baseIp = addrs.ipv4
        Timber.tag("TailscaleScanner").d("Starting Tailscale/Local scan. Local Tailscale IP: %s, Physical local IPs: %s", baseIp, addrs.localIpv4s)

        val discovered = mutableMapOf<String, DiscoveredDevice>()

        // 1. Try to fetch from ntfy.sh
        val room = getActiveRoom()
        Timber.tag("TailscaleScanner").d("Active discovery room: %s", room)
        if (room != null) {
            val topic = "ts_voip_" + sha256(room)
            Timber.tag("TailscaleScanner").d("Querying ntfy.sh topic: %s", topic)
            try {
                val responseText = client.get("https://ntfy.sh/$topic/json?poll=1").bodyAsText()
                val currentTime = System.currentTimeMillis()
                Timber.tag("TailscaleScanner").d("ntfy.sh response length: %d", responseText.length)
                
                responseText.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                        try {
                            val jsonElement = json.parseToJsonElement(line)
                            val message = jsonElement.jsonObject["message"]?.jsonPrimitive?.content
                            if (!message.isNullOrBlank()) {
                                val parts = message.split(",")
                                if (parts.size == 3) {
                                    val ip = parts[0]
                                    val name = parts[1]
                                    val timestamp = parts[2].toLongOrNull() ?: 0L
                                    // Only consider pings from the last 60 seconds
                                    if (ip != baseIp && currentTime - timestamp < 60000L) {
                                        Timber.tag("TailscaleScanner").d("Discovered active peer from ntfy.sh: %s (%s)", name, ip)
                                        discovered[ip] = DiscoveredDevice(ip, name)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore parse errors on individual lines
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("TailscaleScanner").w("Failed to scan via ntfy.sh: %s", e.message)
            }
        }

        // 2. Fallback / Parallel scan local subnets
        val ipsToProbe = mutableSetOf<String>()

        // Add Tailscale subnet candidates
        if (!baseIp.isNullOrBlank()) {
            val parts = baseIp.split(".")
            if (parts.size == 4) {
                val firstOctet = parts[0]
                val secondOctet = parts[1]
                val thirdOctet = parts[2].toIntOrNull() ?: 0

                for (fourth in 1..254) {
                    ipsToProbe.add("$firstOctet.$secondOctet.$thirdOctet.$fourth")
                }

                if (deepScan) {
                    if (thirdOctet > 0) {
                        for (fourth in 1..254) {
                            ipsToProbe.add("$firstOctet.$secondOctet.${thirdOctet - 1}.$fourth")
                        }
                    }
                    if (thirdOctet < 255) {
                        for (fourth in 1..254) {
                            ipsToProbe.add("$firstOctet.$secondOctet.${thirdOctet + 1}.$fourth")
                        }
                    }
                }
            }
        }

        // Add physical local network subnet candidates
        for (localIp in addrs.localIpv4s) {
            val localParts = localIp.split(".")
            if (localParts.size == 4) {
                val f1 = localParts[0]
                val f2 = localParts[1]
                val f3 = localParts[2].toIntOrNull() ?: 0

                for (fourth in 1..254) {
                    ipsToProbe.add("$f1.$f2.$f3.$fourth")
                }

                if (deepScan) {
                    if (f3 > 0) {
                        for (fourth in 1..254) {
                            ipsToProbe.add("$f1.$f2.${f3 - 1}.$fourth")
                        }
                    }
                    if (f3 < 255) {
                        for (fourth in 1..254) {
                            ipsToProbe.add("$f1.$f2.${f3 + 1}.$fourth")
                        }
                    }
                }
            }
        }

        val myIps = mutableSetOf<String>()
        if (!baseIp.isNullOrBlank()) myIps.add(baseIp)
        myIps.addAll(addrs.localIpv4s)

        val targets = ipsToProbe.filter { it !in myIps && !discovered.containsKey(it) }

        if (targets.isNotEmpty()) {
            Timber.tag("TailscaleScanner").d("Running local subnet scan on %d candidates", targets.size)
            val semaphore = Semaphore(100)
            coroutineScope {
                val jobs = targets.map { targetIp ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            if (probePort(targetIp, SignalingServer.PORT, timeout = 250)) {
                                val name = resolveHostname(targetIp)
                                Timber.tag("TailscaleScanner").d("Discovered active peer from local port scan: %s (%s)", name, targetIp)
                                DiscoveredDevice(targetIp, name)
                            } else {
                                null
                            }
                        }
                    }
                }

                val results = jobs.awaitAll().filterNotNull()
                results.forEach { dev ->
                    discovered[dev.ip] = dev
                }
            }
        }

        Timber.tag("TailscaleScanner").d("Scan complete. Total peers discovered: %d", discovered.size)
        emit(discovered.values.toList())
    }.flowOn(Dispatchers.IO)

    private fun probePort(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeout)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveHostname(ip: String): String {
        return try {
            // Direct query MagicDNS resolver for local target IPs first
            val hostname = queryMagicDnsPtr(ip)
            if (!hostname.isNullOrBlank()) {
                hostname.substringBefore(".")
            } else {
                val address = InetAddress.getByName(ip)
                val host = address.hostName
                if (host != ip) {
                    host.substringBefore(".")
                } else {
                    val canonical = address.canonicalHostName
                    if (canonical != ip) {
                        canonical.substringBefore(".")
                    } else {
                        "Device ($ip)"
                    }
                }
            }
        } catch (e: Exception) {
            "Device ($ip)"
        }
    }

    private fun getTailscaleDomain(context: Context): String? {
        // Method 1: Check LinkProperties search domains
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val networks = cm.allNetworks
                Timber.tag("TailscaleScanner").d("LinkProperties domain search. Active networks: %d", networks.size)
                for (network in networks) {
                    val lp = cm.getLinkProperties(network)
                    val domains = lp?.domains
                    Timber.tag("TailscaleScanner").d("Interface: %s, Domains: %s", lp?.interfaceName ?: "null", domains ?: "null")
                    if (!domains.isNullOrBlank()) {
                        val tsDomain = domains.split(Regex("[\\s,]+"))
                            .map { it.trim() }
                            .firstOrNull { it.endsWith(".ts.net") || it.contains(".tailscale.net") }
                        if (tsDomain != null) {
                            Timber.tag("TailscaleScanner").d("Found matching Tailscale search domain: %s", tsDomain)
                            return tsDomain
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("TailscaleScanner").w(e, "Failed to get domain from LinkProperties")
        }

        // Method 2: Direct UDP PTR query to MagicDNS 100.100.100.100:53
        try {
            val addrs = tailscaleDetector.getAddresses()
            val myIp = addrs.ipv4
            if (!myIp.isNullOrBlank()) {
                val resolvedName = queryMagicDnsPtr(myIp)
                Timber.tag("TailscaleScanner").d("Direct UDP MagicDNS PTR for local IP %s: %s", myIp, resolvedName)
                if (!resolvedName.isNullOrBlank() && resolvedName.contains(".")) {
                    return resolvedName.substringAfter(".")
                }
            }
        } catch (e: Exception) {
            Timber.tag("TailscaleScanner").w(e, "Failed direct UDP MagicDNS lookup")
        }

        // Method 3: Standard reverse DNS lookup on local Tailscale IP (JVM fallback)
        try {
            val addrs = tailscaleDetector.getAddresses()
            val myIp = addrs.ipv4
            if (!myIp.isNullOrBlank()) {
                val host = InetAddress.getByName(myIp).hostName
                Timber.tag("TailscaleScanner").d("Fallback reverse DNS lookup for local IP %s: %s", myIp, host)
                if (host != myIp && host.contains(".")) {
                    return host.substringAfter(".")
                }
            }
        } catch (e: Exception) {
            Timber.tag("TailscaleScanner").w(e, "Failed reverse DNS lookup")
        }

        return null
    }

    private fun queryMagicDnsPtr(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val reverseName = "${parts[3]}.${parts[2]}.${parts[1]}.${parts[0]}.in-addr.arpa"
        
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1000
                val payload = ByteArray(512)
                var idx = 0
                
                // Transaction ID
                payload[idx++] = 0x12.toByte()
                payload[idx++] = 0x34.toByte()
                
                // Flags (Standard Query)
                payload[idx++] = 0x01.toByte()
                payload[idx++] = 0x00.toByte()
                
                // Questions count (1)
                payload[idx++] = 0x00.toByte()
                payload[idx++] = 0x01.toByte()
                
                // Answer, Authority, Additional RRs (0)
                payload[idx++] = 0x00.toByte()
                payload[idx++] = 0x00.toByte()
                payload[idx++] = 0x00.toByte()
                payload[idx++] = 0x00.toByte()
                payload[idx++] = 0x00.toByte()
                payload[idx++] = 0x00.toByte()
                
                // Question Name labels
                val labels = reverseName.split(".")
                for (label in labels) {
                    val bytes = label.toByteArray()
                    payload[idx++] = bytes.size.toByte()
                    System.arraycopy(bytes, 0, payload, idx, bytes.size)
                    idx += bytes.size
                }
                payload[idx++] = 0x00.toByte() // End of name
                
                // Query Type: PTR (12)
                payload[idx++] = 0x00.toByte()
                payload[idx++] = 0x0c.toByte()
                
                // Query Class: IN (1)
                payload[idx++] = 0x00.toByte()
                payload[idx++] = 0x01.toByte()
                
                val serverAddr = InetAddress.getByName("100.100.100.100")
                val request = DatagramPacket(payload, idx, serverAddr, 53)
                socket.send(request)
                
                val responseBuffer = ByteArray(1024)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                
                return parseDnsResponse(responseBuffer, response.length)
            }
        } catch (e: Exception) {
            Timber.tag("TailscaleScanner").w("Direct MagicDNS query failed for %s: %s", ip, e.message)
        }
        return null
    }

    private fun parseDnsResponse(buf: ByteArray, len: Int): String? {
        try {
            var idx = 12 // Skip header
            idx = skipDnsName(buf, idx, len) // Skip Question Name
            idx += 4 // Skip Type and Class (2 + 2)
            
            if (idx >= len) return null
            
            // Answer Section
            idx = skipDnsName(buf, idx, len) // Skip answer name
            if (idx + 10 > len) return null
            
            val type = ((buf[idx].toInt() and 0xFF) shl 8) or (buf[idx+1].toInt() and 0xFF)
            idx += 8 // Skip Type (2), Class (2), TTL (4)
            
            val rdLength = ((buf[idx].toInt() and 0xFF) shl 8) or (buf[idx+1].toInt() and 0xFF)
            idx += 2
            
            if (idx + rdLength > len) return null
            
            if (type == 12) { // PTR record
                return decodeDnsName(buf, idx, rdLength)
            }
        } catch (e: Exception) {
            Timber.tag("TailscaleScanner").w(e, "Failed to parse DNS response")
        }
        return null
    }

    private fun skipDnsName(buf: ByteArray, start: Int, len: Int): Int {
        var idx = start
        while (idx < len) {
            val b = buf[idx].toInt() and 0xFF
            if (b == 0) {
                idx++
                break
            }
            if ((b and 0xC0) == 0xC0) { // Pointer
                idx += 2
                break
            }
            idx += 1 + b
        }
        return idx
    }

    private fun decodeDnsName(buf: ByteArray, start: Int, rdLength: Int): String? {
        val sb = java.lang.StringBuilder()
        var idx = start
        val end = start + rdLength
        while (idx < end) {
            val b = buf[idx].toInt() and 0xFF
            if (b == 0) break
            if ((b and 0xC0) == 0xC0) {
                val offset = (((b and 0x3F) shl 8) or (buf[idx+1].toInt() and 0xFF))
                val pointedName = decodeDnsNamePointer(buf, offset)
                if (pointedName != null) {
                    if (sb.isNotEmpty()) sb.append(".")
                    sb.append(pointedName)
                }
                break
            }
            if (sb.isNotEmpty()) sb.append(".")
            sb.append(String(buf, idx + 1, b))
            idx += 1 + b
        }
        return sb.toString()
    }

    private fun decodeDnsNamePointer(buf: ByteArray, start: Int): String? {
        val sb = java.lang.StringBuilder()
        var idx = start
        while (idx < buf.size) {
            val b = buf[idx].toInt() and 0xFF
            if (b == 0) break
            if ((b and 0xC0) == 0xC0) {
                val offset = (((b and 0x3F) shl 8) or (buf[idx+1].toInt() and 0xFF))
                val pointedName = decodeDnsNamePointer(buf, offset)
                if (pointedName != null) {
                    if (sb.isNotEmpty()) sb.append(".")
                    sb.append(pointedName)
                }
                break
            }
            if (sb.isNotEmpty()) sb.append(".")
            sb.append(String(buf, idx + 1, b))
            idx += 1 + b
        }
        return sb.toString()
    }

    private fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
