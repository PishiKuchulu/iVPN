package autoconnect.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress

/** The Android-side half of the libbox VpnService bridge: the VpnService
 *  itself (TUN creation + socket protection) is supplied via [TunProvider]
 *  so this class stays testable and the service owns the Android lifecycle. */
/** Aliases so the VPN service can implement [TunProvider] and handle
 *  TUN options without naming libbox types itself (AUD-014: libbox
 *  references live only in this file and LibboxCore). */
typealias LibboxTunOptions = TunOptions
typealias LibboxRoutePrefixIterator = io.nekohasekai.libbox.RoutePrefixIterator

interface TunProvider {
    /** Create the TUN interface from libbox's options; return its fd. */
    fun openTun(options: TunOptions): Int

    /** VpnService.protect() for the underlying sockets of the core. */
    fun protect(fd: Int): Boolean

    /** libbox asked for the whole service to stop (CommandServerHandler). */
    fun onServiceStopRequest()
}

class ListStringIterator(private val items: List<String>) : StringIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun len(): Int = items.size
    override fun next(): String = items[index++]
}

class ListNetworkInterfaceIterator(
    private val items: List<io.nekohasekai.libbox.NetworkInterface>,
) : NetworkInterfaceIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun next(): io.nekohasekai.libbox.NetworkInterface = items[index++]
}

/**
 * Implementation of libbox's PlatformInterface (API surface verified
 * symbol-by-symbol against the pinned AAR with javap — see BUILD-REPORT).
 *
 * Honest scope for the MVP: TUN, interface enumeration/monitor, Wi-Fi
 * state, connection owner (API 29+), notifications are real. Platform
 * shell, bridge and local-DNS transport are NOT implemented (the hardened
 * profile never uses them: DNS is remote DoH inside the tunnel) and throw
 * or return null accordingly.
 */
class AndroidPlatformInterface(
    private val context: Context,
    private val tunProvider: TunProvider,
) : PlatformInterface {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var myInterfaceName: String = ""

    private var monitorCallback: ConnectivityManager.NetworkCallback? = null

    // ------------------------------------------------------------ TUN

    override fun openTun(options: TunOptions): Int = tunProvider.openTun(options)

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!tunProvider.protect(fd)) {
            throw Exception("VpnService.protect() failed for fd $fd")
        }
    }

    override fun registerMyInterface(name: String) {
        myInterfaceName = name
    }

    // ------------------------------------------------------- interfaces

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val dnsServers = try {
            connectivity.getLinkProperties(connectivity.activeNetwork)
                ?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val metered = try {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        } catch (_: Exception) {
            false
        }
        val nis = try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (ni in nis) {
            try {
                if (!ni.isUp) continue
                // Never offer the core its own TUN interface as an
                // outbound candidate (AUD-017) — the same exclusion the
                // default-interface monitor below already applies.
                if (ni.name == myInterfaceName) continue
                val item = io.nekohasekai.libbox.NetworkInterface()
                item.index = ni.index
                item.mtu = ni.mtu
                item.name = ni.name
                item.addresses = ListStringIterator(
                    ni.interfaceAddresses.mapNotNull { ia ->
                        val host = ia.address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                        "$host/${ia.networkPrefixLength}"
                    }
                )
                var flags = 0
                if (ni.isUp) flags = flags or 0x1
                if (ni.isLoopback) flags = flags or 0x8
                if (ni.isPointToPoint) flags = flags or 0x10
                if (ni.supportsMulticast()) flags = flags or 0x1000
                item.flags = flags
                item.type = when {
                    ni.name.startsWith("wlan") -> Libbox.InterfaceTypeWIFI
                    ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") ||
                        ni.name.startsWith("pdp") -> Libbox.InterfaceTypeCellular
                    ni.name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                item.dnsServer = ListStringIterator(dnsServers)
                item.gateway = ListStringIterator(emptyList())
                item.metered = metered
                result.add(item)
            } catch (_: Exception) {
                // One broken interface must not break enumeration.
            }
        }
        return ListNetworkInterfaceIterator(result)
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyDefault(listener, network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                notifyDefault(listener, network)
        }
        monitorCallback = callback
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            connectivity.activeNetwork?.let { notifyDefault(listener, it) }
        } catch (e: Exception) {
            AppLog.log("platform", "interface monitor failed: ${e.message}")
        }
    }

    private fun notifyDefault(listener: InterfaceUpdateListener, network: Network) {
        try {
            val lp = connectivity.getLinkProperties(network) ?: return
            val name = lp.interfaceName ?: return
            if (name == myInterfaceName) return
            val index = try {
                java.net.NetworkInterface.getByName(name)?.index ?: -1
            } catch (_: Exception) {
                -1
            }
            val caps = connectivity.getNetworkCapabilities(network)
            val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            listener.updateDefaultInterface(name, index, expensive, false)
        } catch (e: Exception) {
            AppLog.log("platform", "default interface notify failed: ${e.message}")
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        try {
            monitorCallback?.let { connectivity.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        monitorCallback = null
    }

    // ------------------------------------------------------------- misc

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < 29) throw Exception("connection owner needs API 29+")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        val owner = ConnectionOwner()
        owner.userId = uid
        owner.userName = ""
        owner.processPath = ""
        return owner
    }

    override fun readWIFIState(): WIFIState? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            Libbox.newWIFIState(info?.ssid?.trim('"') ?: "", info?.bssid ?: "")
        } catch (_: Exception) {
            null
        }
    }

    override fun clearDNSCache() {
        // Android exposes no public DNS-cache flush; nothing to do.
    }

    override fun localDNSTransport(): LocalDNSTransport? {
        // The hardened template routes DNS as remote DoH inside the tunnel;
        // no local (native) DNS transport is offered to the core.
        return null
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun sendNotification(notification: Notification) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val built = android.app.Notification.Builder(context, AutoConnectVpnService.CHANNEL_ID)
                .setContentTitle(notification.title ?: "AutoConnect")
                .setContentText(notification.body ?: "")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            val id = if (notification.typeID != 0) notification.typeID else (notification.identifier ?: "").hashCode()
            nm.notify(id, built)
        } catch (e: Exception) {
            AppLog.log("platform", "sendNotification failed: ${e.message}")
        }
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(if (typeID != 0) typeID else identifier.hashCode())
        } catch (_: Exception) {
        }
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {
        // Not needed by the hardened profile (no LAN/neighbor features).
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {
    }

    // ------------------------------------------- not used by this product

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession {
        throw Exception("platform bridge is not supported by AutoConnect")
    }

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw Exception("platform shell is not supported by AutoConnect")
    }

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession {
        throw Exception("platform shell is not supported by AutoConnect")
    }

    override fun lookupUser(username: String): PlatformUser {
        throw Exception("user lookup is not supported by AutoConnect")
    }

    override fun lookupSFTPServer(): String {
        throw Exception("SFTP is not supported by AutoConnect")
    }

    override fun readSystemSSHHostKey(): String {
        throw Exception("SSH is not supported by AutoConnect")
    }

    override fun tailscaleHostname(): String = ""
}
