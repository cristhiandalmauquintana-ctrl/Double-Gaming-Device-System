// SPDX-License-Identifier: Apache-2.0
package org.dgds.app.core

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {
    // Wi-Fi, punto de acceso, Ethernet y USB tethering. Se excluyen los datos móviles (rmnet, ccmni...).
    private val allowedPrefixes = listOf("wlan", "swlan", "ap", "eth", "usb", "rndis", "ncm")

    /** Direcciones IPv4 locales por las que la laptop puede alcanzar al teléfono. */
    fun localIpv4Addresses(): List<String> =
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && allowedPrefixes.any { p -> it.name.startsWith(p) } }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            emptyList()
        }
}
