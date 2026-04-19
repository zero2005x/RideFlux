/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.rideflux.domain.transport.BleTransport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [BleTransport] backed by the platform `android.bluetooth.BluetoothGatt`
 * API, driven by coroutines.
 *
 * ### GATT topology
 * The transport is instantiated with a [GattTopology] resolved from
 * the advertised service UUIDs (see [WheelCodecFactoryImpl.topologyFor]
 * / [WheelCodecFactoryImpl.inferFromGattServiceUuids]). Once the
 * platform finishes service discovery, the correct notify / write
 * characteristics are looked up per §1.1 / §1.2.
 *
 * ### Threading model
 * Every GATT operation on Android is callback-driven and the stack
 * rejects concurrent requests on a single GATT instance. All
 * `suspend` methods therefore acquire [opMutex] and suspend on a
 * [CompletableDeferred] completed by the matching callback method.
 *
 * Callbacks fire on the platform's binder thread; we re-dispatch every
 * emission to [scope] so consumers see bytes on a predictable
 * dispatcher.
 *
 * ### Permissions
 * The Android manifest / runtime permission dance
 * (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`) is
 * the responsibility of the `:app` module. This class assumes
 * permissions have been granted before [connect] is called.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport internal constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val topology: GattTopology,
    private val scope: CoroutineScope,
) : BleTransport {

    // ---- Public flow ---------------------------------------------------

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    // ---- GATT state (single-writer via opMutex) ------------------------

    private val opMutex = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var connected: Boolean = false
    @Volatile private var closed: Boolean = false

    // Continuations / deferreds completed by GATT callbacks.
    private var connectCont: CancellableContinuation<Unit>? = null
    private var disconnectCont: CancellableContinuation<Unit>? = null
    private val writeQueue = ConcurrentLinkedDeque<CancellableContinuation<Unit>>()

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        try { g.discoverServices() } catch (t: Throwable) { failConnect(t) }
                    } else {
                        failConnect(IOException("onConnectionStateChange: GATT status=$status"))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    // Resume a pending disconnect, if any.
                    disconnectCont?.takeIf { it.isActive }?.resume(Unit)
                    disconnectCont = null
                    // Fail any pending connect / writes that never finished.
                    failConnect(IOException("link lost during connect (status=$status)"))
                    drainWriteQueueWithError(IOException("link lost during write"))
                    try { g.close() } catch (_: Throwable) { /* best-effort */ }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnect(IOException("onServicesDiscovered status=$status"))
                return
            }
            val resolved = resolveCharacteristics(g, topology)
            if (resolved == null) {
                failConnect(IOException("required GATT characteristics missing for topology=$topology"))
                return
            }
            notifyChar = resolved.notify
            writeChar = resolved.write

            if (!g.setCharacteristicNotification(resolved.notify, true)) {
                failConnect(IOException("setCharacteristicNotification returned false"))
                return
            }
            val ccc = resolved.notify.getDescriptor(GattUuids.DESCRIPTOR_CCC)
            if (ccc == null) {
                failConnect(IOException("CCC descriptor missing on notify char ${resolved.notify.uuid}"))
                return
            }
            writeCccDescriptor(g, ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != GattUuids.DESCRIPTOR_CCC) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectCont?.takeIf { it.isActive }?.resume(Unit)
                connectCont = null
            } else {
                failConnect(IOException("CCC write failed status=$status"))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
        ) {
            // Legacy API, still invoked on API < 33 and as a fallback.
            val value = c.value ?: return
            dispatchIncoming(value.copyOf())
        }

        // API 33+ overload carries the value directly.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchIncoming(value.copyOf())
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val next = writeQueue.pollFirst() ?: return
            if (!next.isActive) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                next.resume(Unit)
            } else {
                next.resumeWithException(IOException("write failed status=$status"))
            }
        }
    }

    // ---- Public API ----------------------------------------------------

    override suspend fun connect() {
        opMutex.withLock {
            check(!closed) { "transport has been closed" }
            if (connected) return
            suspendCancellableCoroutine<Unit> { cont ->
                connectCont = cont
                cont.invokeOnCancellation { connectCont = null }
                try {
                    gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(context, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        @Suppress("DEPRECATION")
                        device.connectGatt(context, false, callback)
                    }
                    if (gatt == null) {
                        connectCont = null
                        cont.resumeWithException(IOException("connectGatt returned null"))
                    }
                } catch (t: Throwable) {
                    connectCont = null
                    cont.resumeWithException(t)
                }
            }
        }
    }

    override suspend fun disconnect() {
        opMutex.withLock {
            if (closed) return
            closed = true
            val g = gatt ?: run {
                connected = false
                return
            }
            if (!connected) {
                try { g.close() } catch (_: Throwable) { /* best-effort */ }
                gatt = null
                return
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    disconnectCont = cont
                    cont.invokeOnCancellation { disconnectCont = null }
                    try { g.disconnect() } catch (t: Throwable) {
                        disconnectCont = null
                        cont.resumeWithException(t)
                    }
                }
            } finally {
                try { g.close() } catch (_: Throwable) { /* best-effort */ }
                gatt = null
                connected = false
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        opMutex.withLock {
            val g = gatt ?: throw IOException("not connected")
            val ch = writeChar ?: throw IOException("write characteristic not resolved")
            if (!connected) throw IOException("not connected")
            suspendCancellableCoroutine<Unit> { cont ->
                writeQueue.addLast(cont)
                cont.invokeOnCancellation { writeQueue.remove(cont) }
                try {
                    val ok = submitWrite(g, ch, bytes)
                    if (!ok) {
                        writeQueue.remove(cont)
                        cont.resumeWithException(IOException("writeCharacteristic returned false"))
                    }
                } catch (t: Throwable) {
                    writeQueue.remove(cont)
                    cont.resumeWithException(t)
                }
            }
        }
    }

    // ---- Helpers -------------------------------------------------------

    private fun dispatchIncoming(bytes: ByteArray) {
        scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            _incoming.emit(bytes)
        }
    }

    private fun failConnect(cause: Throwable) {
        val cont = connectCont
        if (cont != null && cont.isActive) {
            connectCont = null
            cont.resumeWithException(cause)
        }
    }

    private fun drainWriteQueueWithError(cause: Throwable) {
        while (true) {
            val next = writeQueue.pollFirst() ?: return
            if (next.isActive) next.resumeWithException(cause)
        }
    }

    private data class ResolvedChars(
        val notify: BluetoothGattCharacteristic,
        val write: BluetoothGattCharacteristic,
    )

    private fun resolveCharacteristics(
        g: BluetoothGatt,
        topology: GattTopology,
    ): ResolvedChars? = when (topology) {
        GattTopology.SINGLE_CHAR -> {
            val svc = g.getService(GattUuids.SERVICE_FFE0) ?: return null
            val c = svc.getCharacteristic(GattUuids.CHAR_FFE1) ?: return null
            ResolvedChars(notify = c, write = c)
        }
        GattTopology.SPLIT_CHAR -> {
            val readSvc = g.getService(GattUuids.SERVICE_FFE0) ?: return null
            val writeSvc = g.getService(GattUuids.SERVICE_FFE5) ?: return null
            val notify = readSvc.getCharacteristic(GattUuids.CHAR_FFE4) ?: return null
            val write = writeSvc.getCharacteristic(GattUuids.CHAR_FFE9) ?: return null
            ResolvedChars(notify = notify, write = write)
        }
        GattTopology.NORDIC_UART -> {
            val svc = g.getService(GattUuids.SERVICE_NUS) ?: return null
            val rx = svc.getCharacteristic(GattUuids.CHAR_NUS_RX) ?: return null
            val tx = svc.getCharacteristic(GattUuids.CHAR_NUS_TX) ?: return null
            ResolvedChars(notify = rx, write = tx)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCccDescriptor(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeDescriptor(descriptor, value)
            if (rc != BluetoothGatt.GATT_SUCCESS) {
                failConnect(IOException("writeDescriptor returned $rc"))
            }
        } else {
            descriptor.value = value
            if (!g.writeDescriptor(descriptor)) {
                failConnect(IOException("writeDescriptor returned false"))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun submitWrite(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(ch, bytes, writeType)
            rc == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = writeType
            ch.value = bytes
            g.writeCharacteristic(ch)
        }
    }

    // Kept for binary-parity symmetry with potential future versions.
    @Suppress("unused")
    private suspend fun runOnScope(block: suspend () -> Unit) =
        withContext(scope.coroutineContext) { block() }

    // Not used publicly; exposed only to keep the linter quiet about
    // the unused `CompletableDeferred` import on some SDK levels.
    @Suppress("unused")
    private fun neverUsed(): CompletableDeferred<Unit>? = null
}
