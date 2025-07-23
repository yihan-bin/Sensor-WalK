package com.example.sensorwalk.connectivity

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object StartingServer : ConnectionState()
    data class WaitingForClient(val message: String) : ConnectionState()
    data class Discovering(val message: String) : ConnectionState()
    data class Connecting(val message: String) : ConnectionState()
    data class Connected(val isHost: Boolean) : ConnectionState()
    data class Disconnected(val reason: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
