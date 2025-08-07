// 文件: app/src/main/java/com/example/sensorwalk/connectivity/ConnectionManager.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 修复编译错误：补全了NsdServiceInfo的类型设置；修复运行时错误：正确实例化了Heartbeat数据包。
 * 2025-07-30 - Gemini-AI - [V2] 修复Heartbeat逻辑，确保其能被正确发送；调整HttpClient配置以支持长时间连接。
 */

package com.example.sensorwalk.connectivity

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ConnectionManager"
private const val SERVICE_TYPE = "_gait-analysis._tcp."
private const val SERVICE_NAME_PREFIX = "GaitAnalysisHost"
private const val RECONNECT_DELAY_MS = 3000L
private const val MAX_RECONNECT_ATTEMPTS = 10
private const val HEARTBEAT_INTERVAL_MS = 10_000L
private const val WEBSOCKET_TIMEOUT_MS = 30_000L

class ConnectionManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val wakeLock: PowerManager.WakeLock by lazy {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorWalk::NetworkWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    private val wifiManager by lazy { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private var multicastLock: WifiManager.MulticastLock? = null

    private var serverEngine: NettyApplicationEngine? = null
    private val hostSessions = ConcurrentHashMap<WebSocketSession, Unit>()
    private var clientSocketSession: DefaultClientWebSocketSession? = null
    private val isDiscovering = AtomicBoolean(false)
    private val isResolving = AtomicBoolean(false)
    @Volatile private var userInitiatedDisconnect = false
    @Volatile private var currentDiscoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var currentRegistrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var currentSessionServiceName: String? = null

    private var hostJob: Job? = null
    private var clientJob: Job? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var sendProcessorJob: Job? = null

    private val sendQueue = Channel<DataPacket>(Channel.UNLIMITED)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    val receivedDataPackets = MutableSharedFlow<DataPacket>(extraBufferCapacity = 64)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState = _connectionState.asStateFlow()

    private var reconnectAttempts = 0

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ClientWebSockets) {
                pingInterval = HEARTBEAT_INTERVAL_MS
                maxFrameSize = Long.MAX_VALUE
            }
            install(ClientContentNegotiation) { json(json) }
            engine {
                requestTimeout = 60_000L
                endpoint {
                    connectTimeout = 30_000L
                    socketTimeout = 30_000L
                }
            }
        }
    }

    init {
        startSendProcessor()
    }

    fun startServer() {
        if (_connectionState.value !is ConnectionState.Idle && _connectionState.value !is ConnectionState.Error) {
            Log.w(TAG, "主机已在启动或运行中，忽略本次请求。当前状态: ${_connectionState.value}")
            return
        }
        Log.i(TAG, ">>> [HOST] 正在启动主机服务...")
        userInitiatedDisconnect = false
        _connectionState.value = ConnectionState.StartingServer
        hostJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                serverEngine = embeddedServer(Netty, port = 0, host = "0.0.0.0") {
                    configureServer()
                }.start(wait = false)

                val port = serverEngine!!.resolvedConnectors().first().port
                Log.i(TAG, "[HOST] Ktor 服务器已在 0.0.0.0:$port 上启动。")

                currentSessionServiceName = "$SERVICE_NAME_PREFIX-${UUID.randomUUID().toString().take(4).uppercase()}"
                Log.i(TAG, "[HOST] 本次会话的唯一服务名为: $currentSessionServiceName")
                registerNsdService(port, currentSessionServiceName!!)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "[HOST] 启动主机失败", e)
                    _connectionState.value = ConnectionState.Error("主机启动失败: ${e.message}")
                } else {
                    Log.i(TAG, "[HOST] 主机启动被取消。")
                }
            }
        }
    }

    fun startDiscovery() {
        if (isDiscovering.compareAndSet(false, true)) {
            Log.i(TAG, ">>> [CLIENT] 正在开始发现主机...")
            userInitiatedDisconnect = false
            reconnectAttempts = 0
            _connectionState.value = ConnectionState.Discovering("搜索主机中...")
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    acquireMulticastLock()
                    val listener = createDiscoveryListener()
                    currentDiscoveryListener = listener
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (e: Exception) {
                    Log.e(TAG, "[CLIENT] 启动服务发现失败", e)
                    stopDiscoveryInternal()
                    _connectionState.value = ConnectionState.Error("搜索失败: ${e.message}")
                }
            }
        } else {
            Log.w(TAG, "[CLIENT] 发现服务已在运行，忽略本次请求。")
        }
    }

    suspend fun sendData(packet: DataPacket) {
        sendQueue.send(packet)
    }

    fun disconnect() {
        Log.i(TAG, ">>> [CORE] 用户主动断开连接，开始全面清理...")
        userInitiatedDisconnect = true
        _connectionState.value = ConnectionState.Idle

        coroutineScope.launch(Dispatchers.IO) {
            reconnectJob?.cancel("User disconnect")
            heartbeatJob?.cancel("User disconnect")
            clientJob?.cancel("User disconnect")
            hostJob?.cancel("User disconnect")
            Log.d(TAG, "[CORE] 所有协程任务已取消。")

            clientSocketSession?.close(CloseReason(CloseReason.Codes.NORMAL, "用户断开"))
            hostSessions.keys.forEach { it.close(CloseReason(CloseReason.Codes.NORMAL, "主机关闭")) }
            Log.d(TAG, "[CORE] WebSocket 会话已关闭。")

            serverEngine?.stop(1000, 2000)
            serverEngine = null
            Log.d(TAG, "[CORE] Ktor 服务器已停止。")

            stopNsdRegistration()
            stopDiscoveryInternal()

            clientSocketSession = null
            hostSessions.clear()
            isDiscovering.set(false)
            isResolving.set(false)
            currentSessionServiceName = null

            Log.i(TAG, "[CORE] 断开连接完成，资源已清理。")
        }
    }

    private fun Application.configureServer() {
        install(ServerWebSockets) {
            pingPeriod = java.time.Duration.ofMillis(HEARTBEAT_INTERVAL_MS)
            timeout = java.time.Duration.ofMillis(WEBSOCKET_TIMEOUT_MS)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        install(ServerContentNegotiation) { json(json) }
        routing {
            webSocket("/gait") {
                handleHostSession(this)
            }
        }
    }

    private suspend fun handleHostSession(session: DefaultWebSocketServerSession) {
        Log.i(TAG, "[HOST] 客户端已连接: ${session.call.request.local.remoteHost}")
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.Connected(isHost = true)
        hostSessions[session] = Unit
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    try {
                        val packet = json.decodeFromString<DataPacket>(frame.readText())
                        Log.d(TAG, "[HOST] 收到数据包: ${packet::class.simpleName}")
                        receivedDataPackets.tryEmit(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "[HOST] 解析客户端数据包失败", e)
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.w(TAG, "[HOST] 主机与客户端会话异常", e)
            }
        } finally {
            hostSessions.remove(session)
            Log.i(TAG, "[HOST] 客户端断开连接: ${session.call.request.local.remoteHost}")
            if (hostSessions.isEmpty() && !userInitiatedDisconnect) {
                _connectionState.value = ConnectionState.WaitingForClient("等待客户端重新连接...")
            }
        }
    }

    private fun startSendProcessor() {
        sendProcessorJob?.cancel()
        sendProcessorJob = coroutineScope.launch(Dispatchers.IO) {
            for (packet in sendQueue) {
                try {
                    sendSinglePacket(packet)
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "[CORE] 发送队列处理器无法发送数据包: ${packet::class.simpleName}", e)
                    }
                }
            }
        }
    }

    private suspend fun sendSinglePacket(packet: DataPacket) {
        val isHost = (_connectionState.value as? ConnectionState.Connected)?.isHost == true
        val session = if (isHost) {
            hostSessions.keys.firstOrNull { it.isActive }
        } else {
            clientSocketSession?.takeIf { it.isActive }
        }

        if (session == null) {
            Log.w(TAG, "[CORE] 无可用会话，无法发送数据: ${packet::class.simpleName}")
            return
        }

        val role = if (isHost) "HOST" else "CLIENT"
        withContext(NonCancellable) {
            try {
                if (!wakeLock.isHeld) wakeLock.acquire(10_000L)
                val jsonString = json.encodeToString(packet)
                withTimeout(WEBSOCKET_TIMEOUT_MS) {
                    session.send(Frame.Text(jsonString))
                }
                Log.d(TAG, "[$role] 数据包发送成功: ${packet::class.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "[$role] 发送数据包时异常", e)
                if (e !is CancellationException) {
                    if (isHost) {
                        try {
                            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Send failed"))
                        } catch (ignored: Exception) { /* ignore */ }
                    } else {
                        (session as? DefaultClientWebSocketSession)?.cancel(CancellationException("Send failed", e))
                    }
                } else {
                    throw e
                }
            } finally {
                if (wakeLock.isHeld) try { wakeLock.release() } catch (e: Exception) { Log.e(TAG, "释放WakeLock失败", e) }
            }
        }
    }

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) { Log.d(TAG, "[CLIENT] NSD服务发现已启动。") }
        override fun onServiceFound(service: NsdServiceInfo) {
            Log.i(TAG, "[CLIENT] 发现服务: ${service.serviceName}, 类型: ${service.serviceType}")
            if (service.serviceType == SERVICE_TYPE && service.serviceName?.startsWith(SERVICE_NAME_PREFIX) == true) {
                if (isResolving.compareAndSet(false, true)) {
                    Log.i(TAG, "[CLIENT] 服务匹配，开始解析: ${service.serviceName}")
                    resolveService(service)
                } else {
                    Log.d(TAG, "[CLIENT] 已有服务正在解析中，忽略此次发现: ${service.serviceName}")
                }
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) { Log.w(TAG, "[CLIENT] 主机服务丢失: ${service.serviceName}") }
        override fun onDiscoveryStopped(serviceType: String) { Log.d(TAG, "[CLIENT] NSD服务发现已停止。") }
        override fun onStartDiscoveryFailed(st: String, err: Int) {
            Log.e(TAG, "[CLIENT] 启动发现失败，错误码: $err")
            stopDiscoveryInternal()
            _connectionState.value = ConnectionState.Error("搜索失败，错误码: $err")
        }
        override fun onStopDiscoveryFailed(st: String, err: Int) { Log.e(TAG, "[CLIENT] 停止发现失败，错误码: $err") }
    }

    private fun resolveService(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                Log.e(TAG, "[CLIENT] 解析服务失败，错误码: $code for ${si.serviceName}")
                isResolving.set(false)
            }

            override fun onServiceResolved(si: NsdServiceInfo) {
                Log.i(TAG, "[CLIENT] 服务解析成功: ${si.serviceName}")
                val hostAddress = si.host
                val port = si.port
                Log.i(TAG, "[CLIENT] 解析结果 - Host: ${hostAddress?.hostAddress}, Port: $port")

                if (hostAddress?.hostAddress?.isNotEmpty() == true && port > 0) {
                    Log.i(TAG, "[CLIENT] 准备连接到主机: ${hostAddress.hostAddress}:$port")
                    connectToServer(hostAddress.hostAddress!!, port)
                } else {
                    Log.e(TAG, "[CLIENT] 解析成功但未获取到有效的主机地址或端口。")
                    isResolving.set(false)
                }
            }
        }
        nsdManager.resolveService(service, resolveListener)
    }

    private fun connectToServer(host: String, port: Int) {
        if (clientJob?.isActive == true) {
            Log.d(TAG, "[CLIENT] 连接任务已在进行中，忽略本次请求")
            return
        }
        reconnectJob?.cancel()
        clientJob = coroutineScope.launch(Dispatchers.IO) {
            _connectionState.value = ConnectionState.Connecting("正在连接主机: $host:$port")
            var wasConnected = false
            try {
                client.webSocket(host = host, port = port, path = "/gait") {
                    stopDiscoveryInternal()
                    wasConnected = true
                    clientSocketSession = this
                    reconnectAttempts = 0
                    _connectionState.value = ConnectionState.Connected(isHost = false)
                    Log.i(TAG, "[CLIENT] 已成功连接到主机。")
                    startHeartbeat()
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            try {
                                val packet = json.decodeFromString<DataPacket>(frame.readText())
                                Log.d(TAG, "[CLIENT] 收到数据包: ${packet::class.simpleName}")
                                receivedDataPackets.tryEmit(packet)
                            } catch (e: Exception) {
                                Log.e(TAG, "[CLIENT] 解析主机数据失败", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) Log.w(TAG, "[CLIENT] 连接到 $host:$port 失败", e)
            } finally {
                clientSocketSession = null
                heartbeatJob?.cancel()
                if (!userInitiatedDisconnect && _connectionState.value !is ConnectionState.Idle) {
                    if (wasConnected) {
                        Log.w(TAG, "[CLIENT] 与主机的连接中断。")
                        _connectionState.value = ConnectionState.Disconnected("连接中断")
                        scheduleReconnect(host, port)
                    } else {
                        Log.w(TAG, "[CLIENT] 首次连接失败。释放解析锁，让服务发现继续寻找下一个可用服务。")
                        isResolving.set(false)
                    }
                }
            }
        }
    }

    private fun scheduleReconnect(host: String, port: Int) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS || userInitiatedDisconnect) {
            Log.e(TAG, "[CLIENT] 重连次数已达上限或用户已断开，停止重连。")
            if (!userInitiatedDisconnect) {
                _connectionState.value = ConnectionState.Error("重连失败，请手动重试")
            }
            return
        }

        reconnectJob = coroutineScope.launch {
            reconnectAttempts++
            val delay = RECONNECT_DELAY_MS * reconnectAttempts
            Log.i(TAG, "[CLIENT] 将在 ${delay/1000}s 后进行第 ${reconnectAttempts} 次重连...")
            _connectionState.value = ConnectionState.Disconnected("将在 ${delay/1000}s 后重连...")
            delay(delay)
            if (!userInitiatedDisconnect) {
                Log.i(TAG, "[CLIENT] 开始第 ${reconnectAttempts} 次重连...")
                connectToServer(host, port)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = coroutineScope.launch {
            while (isActive && clientSocketSession?.isActive == true) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    // 心跳包用于保持连接，其collectionId可以为0
                    sendData(DataPacket.Heartbeat())
                } catch (e: Exception) {
                    if (e !is CancellationException) Log.w(TAG, "[CLIENT] 心跳发送失败", e)
                    break
                }
            }
        }
    }

    private fun stopDiscoveryInternal() {
        if (isDiscovering.getAndSet(false)) {
            currentDiscoveryListener?.let {
                try {
                    nsdManager.stopServiceDiscovery(it)
                    Log.i(TAG, "[CLIENT] NSD 发现监听器已停止。")
                } catch (e: Exception) {
                    Log.e(TAG, "[CLIENT] 停止服务发现时出错", e)
                }
            }
            currentDiscoveryListener = null
        }
        releaseMulticastLock()
        isResolving.set(false)
    }

    private fun stopNsdRegistration() {
        currentRegistrationListener?.let {
            try {
                nsdManager.unregisterService(it)
                Log.i(TAG, "[HOST] NSD 注册监听器已注销: ${currentSessionServiceName}")
            } catch (e: Exception) {
                Log.e(TAG, "[HOST] 注销NSD服务时出错", e)
            }
        }
        currentRegistrationListener = null
    }

    private fun registerNsdService(port: Int, serviceName: String) {
        stopNsdRegistration()
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                Log.i(TAG, "[HOST] NSD服务注册成功: ${si.serviceName}")
                currentSessionServiceName = si.serviceName
                _connectionState.value = ConnectionState.WaitingForClient("等待从机连接...")
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) {
                Log.e(TAG, "[HOST] NSD服务注册失败，错误码: $err")
                _connectionState.value = ConnectionState.Error("主机服务注册失败，错误码: $err")
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {
                Log.i(TAG, "[HOST] NSD服务已注销: ${si.serviceName}")
            }
            override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {
                Log.e(TAG, "[HOST] NSD服务注销失败，错误码: $err")
            }
        }

        currentRegistrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "[HOST] 注册NSD服务时抛出异常", e)
            _connectionState.value = ConnectionState.Error("注册服务异常: ${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("SensorWalkMulticastLock")
            multicastLock?.setReferenceCounted(true)
        }
        if (multicastLock?.isHeld == false) {
            multicastLock?.acquire()
            Log.d(TAG, "Multicast lock acquired.")
        }
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
            Log.d(TAG, "Multicast lock released.")
        }
    }
}
