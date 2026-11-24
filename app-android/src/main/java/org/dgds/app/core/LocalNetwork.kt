// SPDX-License-Identifier: Apache-2.0
package org.dgds.app.core

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {
    /** Direcciones IPv4 locales (Wi-Fi, USB tethering, etc.). */
    fun localIpv4Addresses(): List<String> =
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            emptyList()
        }
}
