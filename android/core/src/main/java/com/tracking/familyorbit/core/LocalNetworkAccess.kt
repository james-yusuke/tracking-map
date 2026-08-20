package com.tracking.familyorbit.core

import android.os.Build
import java.net.URL

object LocalNetworkAccess {
    fun isRequiredFor(baseUrl: String): Boolean {
        if (Build.VERSION.SDK_INT < 37) return false

        val host = runCatching { URL(baseUrl).host.lowercase() }.getOrDefault("")
        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return true

        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
