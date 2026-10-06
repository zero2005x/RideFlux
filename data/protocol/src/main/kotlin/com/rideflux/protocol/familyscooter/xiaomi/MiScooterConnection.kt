package com.rideflux.protocol.familyscooter.xiaomi

import com.rideflux.domain.bond.BondEntry
import com.rideflux.domain.bond.BondFamily
import com.rideflux.domain.bond.BondStore
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.MiRegistrationState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.transport.MiAuthTransport
import com.rideflux.protocol.familyscooter.m365.M365Codec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** L2 Xiaomi profile: authentication and benign M365 reads only; no vehicle validation. */
class MiScooterConnection(
    private val transport: MiAuthTransport,
    override val device: ScooterDevice,
    private val scope: CoroutineScope,
    private val store: BondStore,
    private val auth: MiAuthSession = MiAuthSession(transport),
    private val pollingIntervalMillis: Long = 1_000,
    private val readTimeoutMillis: Long = 3_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScooterConnection {
    init { require(pollingIntervalMillis > 0 && readTimeoutMillis > 0) }
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state = mutableState.asStateFlow()
    private val mutableTelemetry = MutableStateFlow<ScooterTelemetry?>(null)
    override val telemetry = mutableTelemetry.asStateFlow()
    private val mutableHandshake = MutableStateFlow(ScooterHandshakeState.UNBONDED)
    override val handshakeState = mutableHandshake.asStateFlow()
    private val mutableRegistration = MutableStateFlow(MiRegistrationState.NOT_REQUIRED)
    override val registrationState = mutableRegistration.asStateFlow()
    override val confirmationTimeoutMillis = 30_000L
    private val operation = Mutex()
    private val readMutex = Mutex()
    private var encrypted: EncryptedUartTransport? = null
    private var ingestJob: Job? = null
    private var pollJob: Job? = null
    private var authJob: Job? = null
    @Volatile private var closed = false
    private class PendingRead(val register: Int, val length: Int, val reply: CompletableDeferred<ByteArray>)
    @Volatile private var pending: PendingRead? = null

    override suspend fun start(): Unit = operation.withLock {
        check(!closed && state.value == ConnectionState.Disconnected)
        authJob = currentCoroutineContext()[Job]
        mutableState.value = ConnectionState.Connecting
        try {
            transport.connect()
            mutableState.value = ConnectionState.ScooterHandshaking
            val token = storedToken()
            if (token == null) mutableRegistration.value = MiRegistrationState.CONSENT_REQUIRED
            else try { login(token) } finally { token.fill(0) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { fail(ConnectionState.Failed.Reason.AUTHENTICATION_FAILED) }
        finally { authJob = null }
        Unit
    }

    private suspend fun storedToken(): ByteArray? {
        val entries = store.list()
        return try {
            entries.firstOrNull { it.mac == BondEntry.normalizeMac(device.address) &&
                it.family == BondFamily.XIAOMI_MI }?.credential()
        } finally { entries.forEach(BondEntry::wipe) }
    }

    override suspend fun registerAfterUserConfirmation(confirmed: Boolean): Boolean {
        if (!confirmed) return false
        return operation.withLock {
            if (closed || registrationState.value != MiRegistrationState.CONSENT_REQUIRED) return@withLock false
            authJob = currentCoroutineContext()[Job]
            mutableRegistration.value = MiRegistrationState.AUTHENTICATING
            try {
                registerAndLogin()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail(ConnectionState.Failed.Reason.AUTHENTICATION_FAILED); false }
            finally { authJob = null }
        }
    }

    private suspend fun registerAndLogin(): Boolean = coroutineScope {
        val progress = launch(start = CoroutineStart.UNDISPATCHED) {
            auth.registrationProgress.collect {
                mutableRegistration.value = if (it == MiRegistrationProgress.WAITING_FOR_BUTTON)
                    MiRegistrationState.WAITING_FOR_POWER_BUTTON else MiRegistrationState.AUTHENTICATING
            }
        }
        try {
            when (val result = auth.register(true)) {
                is MiRegistrationResult.Success -> try {
                    if (closed) return@coroutineScope false
                    val entry = BondEntry(device.address, BondFamily.XIAOMI_MI, result.token,
                        label = device.model.take(BondEntry.MAX_TEXT), model = device.model.take(BondEntry.MAX_TEXT))
                    try { store.put(entry) } finally { entry.wipe() }
                    login(result.token)
                } finally { result.wipe() }
                else -> { fail(ConnectionState.Failed.Reason.AUTHENTICATION_FAILED); false }
            }
        } finally { progress.cancelAndJoin() }
    }

    private suspend fun login(token: ByteArray): Boolean {
        mutableRegistration.value = MiRegistrationState.AUTHENTICATING
        val result = auth.login(token)
        if (closed) return false
        if (result !is MiLoginResult.Success) {
            fail(ConnectionState.Failed.Reason.AUTHENTICATION_FAILED)
            return false
        }
        val cipherTransport = EncryptedUartTransport(transport, result.keys)
        result.keys.wipe()
        if (closed) {
            try { cipherTransport.disconnect() } catch (_: Exception) { /* best effort */ }
            return false
        }
        encrypted = cipherTransport
        mutableRegistration.value = MiRegistrationState.NOT_REQUIRED
        mutableHandshake.value = ScooterHandshakeState.READY_FOR_TELEMETRY
        mutableState.value = ConnectionState.Ready
        ingestJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { ingest() }
        pollJob = scope.launch {
            try {
                while (isActive) {
                    readRegister(0xB0, 32)
                    delay(pollingIntervalMillis)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail(ConnectionState.Failed.Reason.BLE_LINK_LOST) }
        }
        return true
    }

    private suspend fun ingest() {
        try {
            requireNotNull(encrypted).incoming.collect { frame ->
                try {
                    val read = pending
                    if (read != null) M365Codec.readReplyPayload(frame, read.register, read.length)?.let {
                        pending = null
                        read.reply.complete(it)
                    }
                    val payload = M365Codec.readReplyPayload(frame, 0xB0, 32)
                    if (payload != null) try {
                        M365Codec.decodeB0(payload)?.let { mutableTelemetry.value = it.toTelemetry(clock()) }
                    } finally { payload.fill(0) }
                } finally { frame.fill(0) }
            }
            if (!closed) fail(ConnectionState.Failed.Reason.BLE_LINK_LOST)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (!closed) fail(ConnectionState.Failed.Reason.AUTHENTICATION_FAILED) }
    }

    override suspend fun readRegister(register: Int, readLength: Int): ByteArray? = readMutex.withLock {
        // Replies carry payload+2 in a single unsigned size byte.
        require(register in 0..255 && readLength in 1..253)
        if (closed || state.value != ConnectionState.Ready) return@withLock null
        val request = PendingRead(register, readLength, CompletableDeferred())
        pending = request
        try {
            requireNotNull(encrypted).write(M365Codec.readRequest(register, readLength))
            withTimeoutOrNull(readTimeoutMillis) { request.reply.await() }
        } finally { if (pending === request) pending = null }
    }

    override suspend fun lock(): CommandOutcome = CommandOutcome.Unsupported(WheelCommand.Raw(byteArrayOf(0x70)))
    override suspend fun unlock(): CommandOutcome = CommandOutcome.Unsupported(WheelCommand.Raw(byteArrayOf(0x71)))
    private suspend fun fail(reason: ConnectionState.Failed.Reason) {
        if (closed) return
        mutableState.value = ConnectionState.Failed(reason)
        mutableRegistration.value = MiRegistrationState.NOT_REQUIRED
        pending?.reply?.cancel(); pending = null
        pollJob?.cancel()
        withContext(NonCancellable) {
            try { encrypted?.disconnect() ?: transport.disconnect() } catch (_: Exception) { /* best effort */ }
        }
    }
    override suspend fun close() {
        if (closed) return
        closed = true
        authJob?.cancel()
        pollJob?.cancel(); ingestJob?.cancel(); pending?.reply?.cancel(); pending = null
        withContext(NonCancellable) { encrypted?.disconnect() ?: transport.disconnect() }
        mutableState.value = ConnectionState.Disconnected
        mutableRegistration.value = MiRegistrationState.NOT_REQUIRED
        mutableHandshake.value = ScooterHandshakeState.UNBONDED
        mutableTelemetry.value = null
    }
    override fun toString() = "MiScooterConnection(<redacted>)"
}
