package app.codeg.android.core.mesh

import app.codeg.android.core.datastore.ServerProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

/** Restricted input -> TOML. Never log this output: it contains a network password. */
object MeshConfig {
    fun validate(profile: ServerProfile, password: String) {
        val settings = requireNotNull(profile.mesh)
        require(settings.networkName.isNotBlank() && settings.networkName.length <= 256) {
            "Enter an EasyTier network name."
        }
        require(password.isNotBlank() && password.length <= 4096) { "Enter the EasyTier network password." }
        val destination = URI(profile.baseUrl)
        require(destination.scheme == "http" && ipv4(destination.host.orEmpty()) &&
            destination.userInfo == null && destination.query == null && destination.fragment == null &&
            destination.path.orEmpty() in setOf("", "/") && destination.port in 1..65535) {
            "EasyTier needs an HTTP server URL with a mesh IPv4 address and port."
        }
        require(settings.ipv4.isEmpty() || ipv4(settings.ipv4)) { "Enter a valid virtual IPv4 address, or leave it empty for DHCP." }
        require(settings.peers.size in 1..32) { "Enter between 1 and 32 EasyTier peers." }
        settings.peers.forEach { peer ->
            val uri = runCatching { URI(peer) }.getOrNull()
            require(uri != null && uri.scheme in setOf("tcp", "udp") && !uri.host.isNullOrEmpty() &&
                uri.port in 1..65535 && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                uri.path.orEmpty() in setOf("", "/") && peer.none(Char::isWhitespace)) {
                "Peers must be tcp://host:port or udp://host:port, one per line."
            }
        }
    }

    fun render(profile: ServerProfile, password: String, instance: String, port: Int): String {
        validate(profile, password)
        require(port in 1..65535)
        val settings = requireNotNull(profile.mesh)
        val destination = URI(profile.baseUrl)
        fun quote(value: String) = Json.encodeToString(value)
        return buildString {
            appendLine("instance_name = ${quote(instance)}")
            appendLine("hostname = \"codeg-android\"")
            appendLine("dhcp = ${settings.ipv4.isEmpty()}")
            if (settings.ipv4.isNotEmpty()) appendLine("ipv4 = ${quote(settings.ipv4)}")
            appendLine("listeners = []")
            appendLine("[network_identity]")
            appendLine("network_name = ${quote(settings.networkName)}")
            appendLine("network_secret = ${quote(password)}")
            for (peer in settings.peers) {
                appendLine("[[peer]]")
                appendLine("uri = ${quote(peer)}")
            }
            appendLine("[flags]")
            appendLine("no_tun = true")
            appendLine("use_smoltcp = true")
            appendLine("bind_device = false")
            appendLine("private_mode = true")
            appendLine("latency_first = true")
            appendLine("[[port_forward]]")
            appendLine("bind_addr = \"127.0.0.1:$port\"")
            appendLine("dst_addr = \"${destination.host}:${destination.port}\"")
            appendLine("proto = \"tcp\"")
        }
    }

    private fun ipv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) || it.toInt() !in 0..255 }) return false
        return parts.first().toInt() !in setOf(0, 127) && parts.first().toInt() < 224
    }
}
