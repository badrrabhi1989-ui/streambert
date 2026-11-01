package com.truelockmc.streambert

/** Host matcher for the same BLOCKED_HOSTS patterns as the Electron session. */
object Blocklist {
    fun matches(host: String, patterns: List<String>): Boolean {
        val h = host.lowercase()
        for (raw in patterns) {
            val hostPat = raw.lowercase().removePrefix("*://").substringBefore("/")
            if (hostPat.isEmpty()) continue
            if (hostPat.startsWith("*.")) {
                val suffix = hostPat.substring(1) // ".example.com"
                if (h.endsWith(suffix)) return true
            } else if (h == hostPat || h == hostPat.removePrefix("*.")) {
                return true
            }
        }
        return false
    }
}
