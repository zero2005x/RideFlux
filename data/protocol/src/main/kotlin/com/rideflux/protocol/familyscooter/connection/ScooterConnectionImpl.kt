package com.rideflux.protocol.familyscooter.connection

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.transport.BleTransport
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plaintext Ninebot Retail connection. Encrypted 55 AB traffic is outside this pipeline.
 * [verifiedSpeed] must be supplied only for a model with a proven speed scale and source;
 * the default keeps every critical action closed. The SHU-specific 0x5D body reuses the
 * proposed app random. Lock writes require an explicit model-profile opt-in.
 */
class ScooterConnectionImpl(
    private val transport: BleTransport,
    override val device: ScooterDevice,
    private val scope: CoroutineScope,
    private val handshake: ScooterHandshakeStateMachine,
    private val interlock: MotionInterlock,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollingIntervalMillis: Long = 1_000L,
    private val verifiedSpeed: (ByteArray) -> Float? = { null },
    private val acceptancePayload: (ByteArray) -> ByteArray? = { null },
    private val appRandom: () -> ByteArray = { ByteArray(16).also(SecureRandom()::nextBytes) },
    private val lockProfileVerified: Boolean = false,
) : ScooterConnection {
    init { require(pollingIntervalMillis > 0L) }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _telemetry = MutableStateFlow<ScooterTelemetry?>(null)
    override val telemetry: StateFlow<ScooterTelemetry?> = _telemetry.asStateFlow()
    private val _handshakeState = MutableStateFlow(ScooterHandshakeState.UNBONDED)
    override val handshakeState: StateFlow<ScooterHandshakeState> = _handshakeState.asStateFlow()
    override val lockSupported: Boolean get() = lockProfileVerified
    private var ingestJob: Job? = null
    private var watchdogJob: Job? = null
    private var pollingJob: Job? = null
    private data class PendingRead(val register: Int, val length: Int,
                                   val result: CompletableDeferred<ByteArray>)
    private var pendingRead: PendingRead? = null
    private var closed = false

    override suspend fun start() {
        check(!closed && _state.value == ConnectionState.Disconnected)
        _state.value = ConnectionState.Connecting
        try {
            transport.connect()
            // Start collecting before sending the request so an immediate reply is not lost.
            ingestJob = scope.launch { ingest() }
            _state.value = ConnectionState.ScooterHandshaking
            val request = handshake.begin()
            publishHandshake()
            transport.write(request)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            fail(ConnectionState.Failed.Reason.GATT_ERROR, failure)
            throw failure
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        pollingJob?.cancel()
        watchdogJob?.cancel()
        ingestJob?.cancel()
        pendingRead?.result?.cancel()
        pendingRead = null
        try { transport.disconnect() } finally {
            handshake.reset()
            publishHandshake()
            _telemetry.value = null
            _state.value = ConnectionState.Disconnected
        }
    }

    private suspend fun ingest() {
        try {
            transport.incoming.collect { bytes ->
                val frame = NinebotRetailCodec.decodeFrame(bytes) ?: return@collect
                val now = clock()
                // Only a verified model-specific decoder can supply speed for safety gating.
                val speed = verifiedSpeed(bytes)
                val b0Reply = NinebotRetailCodec.readReplyPayload(bytes, 0xb0, 32) != null
                if (speed != null || b0Reply) interlock.observe(speed, now)
                when (_state.value) {
                    ConnectionState.ScooterHandshaking -> advanceHandshake(bytes, frame.command, frame.argument)
                    ConnectionState.Ready -> ingestTelemetry(bytes, frame.command, speed, now)
                    else -> Unit
                }
            }
            if (!closed && _state.value != ConnectionState.Disconnected) {
                fail(ConnectionState.Failed.Reason.BLE_LINK_LOST)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (!closed) fail(
                if (failure is SecurityException || failure is IllegalArgumentException ||
                    failure is IllegalStateException) ConnectionState.Failed.Reason.AUTHENTICATION_FAILED
                else ConnectionState.Failed.Reason.INTERNAL, failure)
        }
    }

    private suspend fun advanceHandshake(bytes: ByteArray, command: Int, argument: Int) {
        when (handshake.state) {
            ScooterHandshakeStateMachine.State.RequestingBleRandom -> if (command == 0x5b) {
                transport.write(handshake.onBleRandomReceived(bytes, appRandom()))
                publishHandshake()
                watchdogJob?.cancel()
                watchdogJob = scope.launch {
                    delay(ScooterHandshakeStateMachine.CONFIRMATION_TIMEOUT_MILLIS)
                    if (handshake.pollTimeout()) {
                        publishHandshake()
                        fail(ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT)
                    }
                }
            }
            is ScooterHandshakeStateMachine.State.WaitingForUserConfirmation ->
                if (command == 0x5c && argument == 0x01) {
                    transport.write(handshake.onUserConfirmed(bytes, acceptancePayload(bytes)))
                    watchdogJob?.cancel()
                    publishHandshake()
                }
            ScooterHandshakeStateMachine.State.AwaitingPairingAcceptance -> if (command == 0x5d) {
                handshake.onPairingAccepted(bytes)
                publishHandshake()
                handshake.markReadyForTelemetry()
                publishHandshake()
                _state.value = ConnectionState.Ready
                pollingJob = scope.launch {
                    try {
                        while (true) {
                            transport.write(NinebotRetailCodec.buildReadRequest(0xb0, 32))
                            delay(pollingIntervalMillis)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        fail(ConnectionState.Failed.Reason.GATT_ERROR, failure)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun ingestTelemetry(bytes: ByteArray, command: Int, speed: Float?, now: Long) {
        val read = pendingRead
        if (read != null) {
            val payload = NinebotRetailCodec.readReplyPayload(bytes, read.register, read.length)
            if (payload != null) {
                pendingRead = null
                read.result.complete(payload)
            }
        }
        if (command == 0xb0) {
            val payload = NinebotRetailCodec.readReplyPayload(bytes, 0xb0, 32) ?: return
            val block = NinebotRetailCodec.decodeEs2B0(payload) ?: return
            _telemetry.value = block.toTelemetry(now).copy(speedKmh = speed)
        } else if (speed != null) {
            _telemetry.value = (_telemetry.value ?: ScooterTelemetry(now)).copy(
                timestampMillis = now, speedKmh = speed)
        }
    }

    override suspend fun readRegister(register: Int, readLength: Int): ByteArray? {
        require(register in 0..255 && readLength in 1..255)
        if (_state.value != ConnectionState.Ready || pendingRead != null) return null
        val result = CompletableDeferred<ByteArray>()
        pendingRead = PendingRead(register, readLength, result)
        return try {
            transport.write(NinebotRetailCodec.buildReadRequest(register, readLength))
            withTimeoutOrNull(3_000L) { result.await() }
        } finally {
            if (pendingRead?.result === result) pendingRead = null
        }
    }

    override suspend fun lock(): CommandOutcome = control(0x70)
    override suspend fun unlock(): CommandOutcome = control(0x71)

    /** All other retail write opcodes, including 0x78/0x79 and firmware, are barred. */
    suspend fun control(register: Int): CommandOutcome {
        val command = WheelCommand.Raw(byteArrayOf(register.toByte()))
        if (_state.value != ConnectionState.Ready) return CommandOutcome.TransportError(command, null)
        return try {
            NinebotRetailCodec.requireControlAllowed(register, interlock, clock())
            if (!lockProfileVerified) return CommandOutcome.Unsupported(command)
            transport.write(if (register == 0x70)
                NinebotRetailCodec.buildLockRequest(interlock, clock())
            else NinebotRetailCodec.buildUnlockRequest(interlock, clock()))
            CommandOutcome.Success // BLE transport write completed; device state is not inferred.
        } catch (denied: SecurityException) {
            CommandOutcome.InvalidArgument(command, denied.message ?: "Vehicle speed unverified")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            CommandOutcome.TransportError(command, failure)
        }
    }

    private fun publishHandshake() {
        _handshakeState.value = when (handshake.state) {
            ScooterHandshakeStateMachine.State.Unbonded -> ScooterHandshakeState.UNBONDED
            ScooterHandshakeStateMachine.State.RequestingBleRandom -> ScooterHandshakeState.REQUESTING_BLE_RANDOM
            is ScooterHandshakeStateMachine.State.WaitingForUserConfirmation -> ScooterHandshakeState.WAITING_FOR_USER_CONFIRMATION
            ScooterHandshakeStateMachine.State.AwaitingPairingAcceptance -> ScooterHandshakeState.AWAITING_PAIRING_ACCEPTANCE
            ScooterHandshakeStateMachine.State.PairingComplete -> ScooterHandshakeState.PAIRING_COMPLETE
            ScooterHandshakeStateMachine.State.ReadyForTelemetry -> ScooterHandshakeState.READY_FOR_TELEMETRY
        }
    }

    private fun fail(reason: ConnectionState.Failed.Reason, cause: Throwable? = null) {
        if (closed || _state.value is ConnectionState.Failed) return
        pollingJob?.cancel()
        watchdogJob?.cancel()
        pendingRead?.result?.cancel()
        pendingRead = null
        interlock.reset()
        _state.value = ConnectionState.Failed(reason, cause?.message)
        scope.launch {
            try { transport.disconnect() } catch (_: Exception) { /* failure already reported */ }
        }
    }
}
