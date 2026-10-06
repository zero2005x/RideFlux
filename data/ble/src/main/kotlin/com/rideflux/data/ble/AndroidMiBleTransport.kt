package com.rideflux.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.rideflux.domain.transport.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** L2 Xiaomi transport. MTU 23 is sufficient: auth parcels retain their 18-byte payload limit. */
@SuppressLint("MissingPermission")
class AndroidMiBleTransport internal constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val connectTimeoutMillis: Long = 20_000,
    private val writeTimeoutMillis: Long = 5_000,
) : MiAuthTransport {
    init { require(connectTimeoutMillis > 0 && writeTimeoutMillis > 0) }
    private val dataPackets = Channel<ByteArray>(256, onUndeliveredElement = { it.fill(0) })
    private val authPackets = Channel<MiAuthNotification>(256, onUndeliveredElement = MiAuthNotification::wipe)
    override val incoming = dataPackets.receiveAsFlow()
    override val authNotifications = authPackets.receiveAsFlow()
    private val operation = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var profile: XiaomiGattProfile? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var connecting: CompletableDeferred<Unit>? = null
    private var cccIndex = 0
    @Volatile private var expectedDescriptor: BluetoothGattDescriptor? = null
    private class Write(val characteristic: BluetoothGattCharacteristic, val bytes: ByteArray) {
        val done = CompletableDeferred<Unit>()
        @Suppress("DEPRECATION")
        fun wipe() { bytes.fill(0); characteristic.value?.fill(0); characteristic.value = ByteArray(0) }
    }
    @Volatile private var pending: Write? = null
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!current(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                terminate(IOException("Xiaomi BLE link lost")); return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { if (!g.discoverServices()) terminate(IOException("Xiaomi service discovery rejected")) }
                catch (_: Exception) { terminate(IOException("Xiaomi service discovery failed")) }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!current(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) { terminate(IOException("Xiaomi service discovery failed")); return }
            try { profile = XiaomiGattProfile.resolve(g); cccIndex = 0; enableNext(g) }
            catch (_: Exception) { terminate(IOException("Required Xiaomi GATT profile is absent")) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!current(g) || descriptor !== expectedDescriptor) return
            if (status != BluetoothGatt.GATT_SUCCESS) { terminate(IOException("Xiaomi notification setup failed")); return }
            expectedDescriptor = null
            cccIndex++
            try { enableNext(g) } catch (_: Exception) { terminate(IOException("Xiaomi notification setup failed")) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!current(g)) return
            val write = pending ?: return
            if (characteristic !== write.characteristic) return
            write.wipe()
            if (status == BluetoothGatt.GATT_SUCCESS) write.done.complete(Unit)
            else terminate(IOException("Xiaomi BLE write failed"))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (current(g)) notify(characteristic, value)
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (current(g)) characteristic.value?.let { notify(characteristic, it) }
        }
    }
    override suspend fun connect() = operation.withLock {
        check(!closed) { "Xiaomi transport is closed" }
        if (ready) return@withLock
        val completion = CompletableDeferred<Unit>()
        connecting = completion
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("Xiaomi connectGatt returned null")
            withTimeout(connectTimeoutMillis) { completion.await() }
        } catch (timeout: TimeoutCancellationException) {
            terminate(IOException("Xiaomi connection timed out", timeout))
            throw IOException("Xiaomi connection timed out", timeout)
        } catch (cancel: CancellationException) {
            terminate(IOException("Xiaomi connection cancelled")); throw cancel
        } catch (_: Exception) {
            terminate(IOException("Xiaomi connection failed")); throw IOException("Xiaomi connection failed")
        } finally { connecting = null }
    }
    override suspend fun disconnect() { terminate() }
    override suspend fun write(bytes: ByteArray) { writeTo({ it.uartWrite }, bytes) }
    override suspend fun writeAuth(characteristic: MiAuthChar, bytes: ByteArray) {
        writeTo({ if (characteristic == MiAuthChar.UPNP) it.upnp else it.avdtp }, bytes)
    }
    private suspend fun writeTo(select: (XiaomiGattProfile) -> BluetoothGattCharacteristic, bytes: ByteArray) = operation.withLock {
        val currentGatt = gatt ?: throw IOException("Xiaomi transport is not connected")
        if (!ready || closed) throw IOException("Xiaomi transport is not ready")
        val write = Write(select(requireNotNull(profile)), bytes.copyOf())
        pending = write
        try {
            if (!submit(currentGatt, write)) throw IOException("Xiaomi write rejected")
            withTimeout(writeTimeoutMillis) { write.done.await() }
        } catch (timeout: TimeoutCancellationException) {
            terminate(IOException("Xiaomi write timed out", timeout))
            throw IOException("Xiaomi write timed out", timeout)
        } catch (cancel: CancellationException) {
            terminate(IOException("Xiaomi write cancelled")); throw cancel
        } catch (_: Exception) {
            terminate(IOException("Xiaomi BLE write failed")); throw IOException("Xiaomi BLE write failed")
        } finally { pending = null; write.wipe() }
    }
    @Suppress("DEPRECATION")
    private fun submit(g: BluetoothGatt, write: Write): Boolean {
        val type = XiaomiGattProfile.writeType(write.characteristic)
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(write.characteristic, write.bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            write.characteristic.writeType = type
            write.characteristic.value = write.bytes
            g.writeCharacteristic(write.characteristic)
        }
    }
    @Suppress("DEPRECATION")
    private fun enableNext(g: BluetoothGatt) {
        val notifications = requireNotNull(profile).notifications
        if (cccIndex == notifications.size) { ready = true; connecting?.complete(Unit); return }
        val characteristic = notifications[cccIndex]
        if (!g.setCharacteristicNotification(characteristic, true)) throw IOException("Xiaomi notifications rejected")
        val descriptor = characteristic.getDescriptor(GattUuids.DESCRIPTOR_CCC)
            ?: throw IOException("Xiaomi CCC descriptor absent")
        expectedDescriptor = descriptor
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val accepted = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        else { descriptor.value = value; g.writeDescriptor(descriptor) }
        if (!accepted) throw IOException("Xiaomi CCC write rejected")
    }
    private fun current(g: BluetoothGatt): Boolean = !closed && g === gatt
    private fun notify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val selected = profile ?: return
        when (characteristic) {
            selected.upnp -> offerAuth(MiAuthChar.UPNP, value)
            selected.avdtp -> offerAuth(MiAuthChar.AVDTP, value)
            selected.uartNotify -> {
                val copy = value.copyOf()
                if (dataPackets.trySend(copy).isFailure) { copy.fill(0); terminate(IOException("Xiaomi receive overflow")) }
            }
        }
    }
    private fun offerAuth(characteristic: MiAuthChar, value: ByteArray) {
        val notification = MiAuthNotification(characteristic, value)
        if (authPackets.trySend(notification).isFailure) {
            notification.wipe(); terminate(IOException("Xiaomi auth receive overflow"))
        }
    }
    private fun terminate(cause: IOException? = null) {
        closed = true; ready = false
        val previous = gatt
        gatt = null; profile = null; expectedDescriptor = null
        val failure = cause ?: IOException("Xiaomi transport closed")
        connecting?.completeExceptionally(failure)
        pending?.let { it.wipe(); it.done.completeExceptionally(failure) }
        dataPackets.close(cause); authPackets.close(cause)
        while (true) (dataPackets.tryReceive().getOrNull() ?: break).fill(0)
        while (true) (authPackets.tryReceive().getOrNull() ?: break).wipe()
        try { previous?.disconnect() } catch (_: Exception) { /* best effort */ }
        try { previous?.close() } catch (_: Exception) { /* best effort */ }
    }
    override fun toString() = "AndroidMiBleTransport(<redacted>)"
}
