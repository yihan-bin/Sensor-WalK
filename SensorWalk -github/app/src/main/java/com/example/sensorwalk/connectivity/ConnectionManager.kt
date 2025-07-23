// 文件: app/src/main/java/com/example/sensorwalk/connectivity/ConnectionManager.kt

package com.example.sensorwalk.connectivity

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.websocket.*
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ConnectionManager"
private const val SERVICE_TYPE = "_gait-analysis._tcp."
private const val SERVICE_NAME = "GaitAnalysisHost"
private const val RECONNECT_DELAY_MS = 5000L

class ConnectionManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

    private var serverEngine: NettyApplicationEngine? = null
    private val hostSessions = ConcurrentHashMap<WebSocketSession, Unit>()
    private var clientSocketSession: DefaultClientWebSocketSession? = null

    private var hostJob: Job? = null
    private var clientJob: Job? = null
    private var reconnectJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ClientWebSockets) {
                pingInterval = 10_000
                maxFrameSize = Long.MAX_VALUE
            }
            install(ClientContentNegotiation) {
                json(json)
            }
            engine {
                requestTimeout = 0 // Let WebSockets plugin handle timeouts
            }
        }
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState = _connectionState.asStateFlow()

    val receivedDataPackets = MutableSharedFlow<DataPacket>(extraBufferCapacity = 64)

    @Volatile
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startServer() {
        if (_connectionState.value !is ConnectionState.Idle && _connectionState.value !is ConnectionState.Error) return
        _connectionState.value = ConnectionState.StartingServer
        hostJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                serverEngine = embeddedServer(Netty, port = 0, host = "0.0.0.0") {
                    install(ServerWebSockets) {
                        pingPeriod = Duration.ofSeconds(15)
                        timeout = Duration.ofSeconds(30)
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }
                    install(ServerContentNegotiation) {
                        json(json)
                    }
                    routing {
                        webSocket("/gait") {
                            _connectionState.value = ConnectionState.Connected(isHost = true)
                            hostSessions[this] = Unit
                            Log.d(TAG, "Client connected. Total sessions: ${hostSessions.size}")
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        val packet = json.decodeFromString<DataPacket>(text)
                                        receivedDataPackets.tryEmit(packet)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Host WebSocket error: ${e.message}")
                            } finally {
                                hostSessions.remove(this)
                                Log.d(TAG, "Client disconnected. Remaining sessions: ${hostSessions.size}")
                                if (hostSessions.isEmpty() && _connectionState.value is ConnectionState.Connected) {
                                    _connectionState.value = ConnectionState.Disconnected("Client disconnected")
                                    coroutineScope.launch {
                                        delay(100)
                                        if (_connectionState.value !is ConnectionState.Idle) {
                                            _connectionState.value = ConnectionState.WaitingForClient("Waiting for new client...")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.start(wait = false)

                val port = serverEngine!!.resolvedConnectors().first().port
                registerNsdService(port)
                _connectionState.value = ConnectionState.WaitingForClient("Waiting for client connection...")
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
                _connectionState.value = ConnectionState.Error("Server start failed: ${e.message}")
            }
        }
    }

    fun startDiscovery() {
        if (_connectionState.value !is ConnectionState.Idle && _connectionState.value !is ConnectionState.Error) return
        _connectionState.value = ConnectionState.Discovering("Searching for host...")
        stopDiscovery()
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                // ★★★ 崩溃修复: 关键修改！每次发现服务都创建一个新的 ResolveListener 实例 ★★★
                // 这从根本上解决了 "listener already in use" 的崩溃问题。
                if (service.serviceType == SERVICE_TYPE) {
                    Log.d(TAG, "Service found: ${service.serviceName}. Creating new resolver.")
                    // ★★★ 解决 Deprecation 警告：使用不带 Executor 的旧版 API ★★★
                    nsdManager.resolveService(service, createResolveListener())
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) { Log.d(TAG, "Service lost: ${service.serviceName}") }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _connectionState.value = ConnectionState.Error("Discovery start failed, code: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun createResolveListener(): NsdManager.ResolveListener {
        // 这个变量确保每个 listener 实例只被成功使用一次
        var alreadyResolved = false

        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}, code: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // ★★★ 健壮性修复：防止同一个 listener 被意外触发多次 ★★★
                if (alreadyResolved) {
                    Log.w(TAG, "Service ${serviceInfo.serviceName} already resolved by this listener. Ignoring.")
                    return
                }
                alreadyResolved = true

                // 成功解析后，应立即停止发现，避免不必要的网络活动和潜在的UI冲突
                stopDiscovery()

                val hostAddress: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                } else {
                    @Suppress("DEPRECATION")
                    serviceInfo.host?.hostAddress
                }

                if (hostAddress != null) {
                    val port: Int = serviceInfo.port
                    Log.d(TAG, "Resolve successful: $hostAddress:$port")
                    connectToServer(hostAddress, port)
                } else {
                    Log.e(TAG, "Could not resolve host address for service: ${serviceInfo.serviceName}")
                    _connectionState.value = ConnectionState.Error("Failed to resolve host address")
                }
            }
        }
    }


    private fun connectToServer(host: String, port: Int) {
        reconnectJob?.cancel()
        clientJob?.cancel()
        clientJob = coroutineScope.launch(Dispatchers.IO) {
            _connectionState.value = ConnectionState.Connecting("Connecting to host: $host")
            try {
                client.webSocket(host = host, port = port, path = "/gait") {
                    _connectionState.value = ConnectionState.Connected(isHost = false)
                    clientSocketSession = this
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val packet = json.decodeFromString<DataPacket>(frame.readText())
                            receivedDataPackets.tryEmit(packet)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed or interrupted", e)
            } finally {
                clientSocketSession = null
                if (_connectionState.value !is ConnectionState.Idle) {
                    _connectionState.value = ConnectionState.Disconnected("Disconnected from host")
                    scheduleReconnect(host, port)
                }
            }
        }
    }

    private fun scheduleReconnect(host: String, port: Int) {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope.launch {
            delay(RECONNECT_DELAY_MS)
            if (_connectionState.value is ConnectionState.Disconnected) {
                Log.i(TAG, "Attempting auto-reconnect...")
                connectToServer(host, port)
            }
        }
    }

    suspend fun sendData(packet: DataPacket) {
        val session: WebSocketSession? = if ((_connectionState.value as? ConnectionState.Connected)?.isHost == true) {
            hostSessions.keys.firstOrNull()
        } else {
            clientSocketSession
        }

        if (session?.isActive != true) {
            Log.w(TAG, "Failed to send data: session not active. State: ${_connectionState.value}")
            return
        }

        try {
            val jsonString = json.encodeToString(packet)
            session.send(Frame.Text(jsonString))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) { Log.e(TAG, "Error stopping discovery", e) }
            discoveryListener = null
        }
    }

    fun disconnect() {
        Log.i(TAG, "User requested disconnect.")
        _connectionState.value = ConnectionState.Idle

        reconnectJob?.cancel()
        clientJob?.cancel()
        hostJob?.cancel()

        coroutineScope.launch(Dispatchers.IO) {
            serverEngine?.stop(100, 200)
            serverEngine = null

            clientSocketSession?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnected"))
            clientSocketSession = null

            hostSessions.keys.forEach {
                try {
                    it.close(CloseReason(CloseReason.Codes.NORMAL, "Host disconnected"))
                } catch (e: Exception) { /* ignore */ }
            }
            hostSessions.clear()
        }

        val listenerToUnregister = registrationListener
        if (listenerToUnregister != null) {
            try {
                nsdManager.unregisterService(listenerToUnregister)
            } catch(e: Exception) {
                Log.e(TAG, "Error unregistering service", e)
            }
            registrationListener = null
        }
        stopDiscovery()
    }

    private fun registerNsdService(port: Int) {
        val currentListener = registrationListener
        if (currentListener != null) {
            try { nsdManager.unregisterService(currentListener) } catch (e: Exception) { /* ignore */ }
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = SERVICE_NAME
            this.serviceType = SERVICE_TYPE
            this.port = port
        }

        val newListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { Log.d(TAG, "Service registered: $si") }
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) { _connectionState.value = ConnectionState.Error("Service registration failed, code: $code") }
            override fun onServiceUnregistered(si: NsdServiceInfo) { Log.d(TAG, "Service unregistered: $si") }
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        this.registrationListener = newListener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, newListener)
    }
}
