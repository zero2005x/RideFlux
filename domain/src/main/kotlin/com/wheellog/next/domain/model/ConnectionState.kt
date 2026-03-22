package com.wheellog.next.domain.model

/** BLE connection lifecycle states. */
enum class ConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
}
