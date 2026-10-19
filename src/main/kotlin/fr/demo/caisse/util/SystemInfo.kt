package fr.demo.caisse.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object SystemInfo {
    const val VERSION = "1.3.2"

    fun hostname(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("inconnu")

    fun adressesIp(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
    }.getOrDefault(emptyList())
}
