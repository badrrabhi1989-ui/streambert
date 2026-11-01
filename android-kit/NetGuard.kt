package com.truelockmc.streambert

import java.net.InetAddress
import java.net.URI

/** Blocks loopback, link-local, and private targets. Same idea as the desktop SSRF guard. */
object NetGuard {
    fun isPublicHttpUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val host = uri.host ?: return false
        return isPublicHost(host)
    }

    fun isPublicHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost")) return false
        if (h == "player.streambert.local") return false
        val addrs = try {
            InetAddress.getAllByName(h)
        } catch (_: Exception) {
            return false
        }
        if (addrs.isEmpty()) return false
        return addrs.all { isPublicAddress(it) }
    }

    private fun isPublicAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) {
            return false
        }
        val b = addr.address
        if (b.size == 4) {
            val a = b[0].toInt() and 0xff
            val c = b[1].toInt() and 0xff
            if (a == 0 || a >= 224) return false
            if (a == 100 && c in 64..127) return false
            if (a == 169 && c == 254) return false
        }
        return true
    }
}
