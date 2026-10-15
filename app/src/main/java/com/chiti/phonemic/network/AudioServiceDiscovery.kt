package com.chiti.phonemic.network

import android.content.Context
import android.net.nsd.*
import kotlinx.coroutines.flow.*
import java.net.InetAddress

data class AudioReceiver(val name: String, val address: InetAddress, val port: Int)

/** Application-scoped NSD; callbacks may arrive after stop, so generation gates resolved results. */
class AudioServiceDiscovery(context: Context) {
    companion object { const val TYPE = "_phonemic._udp."; const val PORT = 50005 }
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mutable = MutableStateFlow<List<AudioReceiver>>(emptyList())
    val receivers = mutable.asStateFlow()
    private val message = MutableStateFlow("Not discovering")
    val status = message.asStateFlow()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var generation = 0
    fun registerService() {
        if (registration != null) return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { message.value = "Visible as ${info.serviceName}" }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { message.value = "NSD registration failed: $error" }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "PhoneMic-${android.os.Build.MODEL}"; serviceType = TYPE; port = PORT
        }, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    @Suppress("DEPRECATION")
    fun discoverServices() {
        if (discovery != null) return
        val epoch = ++generation
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { message.value = "Searching local Wi-Fi…" }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { message.value = "Discovery failed: $code" }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                mutable.value = mutable.value.filterNot { it.name == info.serviceName }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') != TYPE.trimEnd('.')) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) { message.value = "Resolve failed ($code); retry discovery" }
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val host = service.host ?: return
                        if (epoch != generation || service.port != PORT) return
                        mutable.value = mutable.value.filterNot { it.name == service.serviceName } + AudioReceiver(service.serviceName, host, service.port)
                    }
                })
            }
        }
        discovery = listener
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    fun stop() {
        generation++
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        mutable.value = emptyList(); message.value = "Discovery stopped"
    }
}
