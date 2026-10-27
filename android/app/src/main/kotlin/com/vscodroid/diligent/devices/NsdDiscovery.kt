package com.vscodroid.diligent.devices

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.ditarector.diligent.sync.DiligentLink
import com.ditarector.diligent.sync.device.DeviceId
import com.ditarector.diligent.sync.device.ServiceInfo
import com.ditarector.diligent.sync.port.DiscoveredPeer
import com.ditarector.diligent.sync.port.Discovery
import com.ditarector.diligent.sync.port.DiscoveryListener
import com.ditarector.diligent.sync.port.Endpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * DNS-SD for diligent-link (SPEC A.2) through `NsdManager`: registers
 * `_diligent._tcp` with the TXT record [ServiceInfo.toTxt] builds, and
 * browses for the same type, resolving each service to an [Endpoint].
 *
 * [port] is the port the transport is listening on; set it before
 * [advertise]. NsdManager needs no permission beyond INTERNET.
 */
class NsdDiscovery(context: Context) : Discovery {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val callbacks = Executors.newSingleThreadExecutor()

    @Volatile var port: Int = DiligentLink.DEFAULT_PORT

    private var registration: NsdManager.RegistrationListener? = null
    private var browsing: NsdManager.DiscoveryListener? = null
    private val idsByName = ConcurrentHashMap<String, DeviceId>()

    @Synchronized
    override fun advertise(info: ServiceInfo) {
        stopAdvertising()
        val service = NsdServiceInfo().apply {
            serviceName = info.name
            serviceType = DiligentLink.SERVICE_TYPE
            setPort(this@NsdDiscovery.port)
            info.toTxt().forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd?.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    override fun stopAdvertising() {
        val listener = registration ?: return
        registration = null
        runCatching { nsd?.unregisterService(listener) }
    }

    @Synchronized
    override fun browse(listener: DiscoveryListener) {
        stopBrowsing()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = resolve(serviceInfo, listener)
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                idsByName.remove(serviceInfo.serviceName)?.let(listener::onLost)
            }
        }
        browsing = discovery
        nsd?.discoverServices(DiligentLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    @Synchronized
    override fun stopBrowsing() {
        val discovery = browsing ?: return
        browsing = null
        runCatching { nsd?.stopServiceDiscovery(discovery) }
    }

    private fun resolve(found: NsdServiceInfo, listener: DiscoveryListener) {
        val manager = nsd ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.hostAddresses.firstOrNull()?.hostAddress ?: return
                    report(serviceInfo, host, listener)
                    runCatching { manager.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            runCatching { manager.registerServiceInfoCallback(found, callbacks, callback) }
        } else {
            @Suppress("DEPRECATION")
            manager.resolveService(
                found,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = serviceInfo.host?.hostAddress ?: return
                        report(serviceInfo, host, listener)
                    }
                },
            )
        }
    }

    private fun report(service: NsdServiceInfo, host: String, listener: DiscoveryListener) {
        val txt = service.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
        val info = ServiceInfo.fromTxt(txt) ?: return
        if (service.port !in 1..65535) return
        idsByName[service.serviceName] = info.deviceId
        listener.onFound(DiscoveredPeer(info, Endpoint(host, service.port)))
    }
}
