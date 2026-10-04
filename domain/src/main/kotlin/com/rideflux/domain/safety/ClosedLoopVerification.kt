package com.rideflux.domain.safety

/** An ACK only acknowledges transport. The observed value must match after a write. */
interface ClosedLoopVerification<T> {
    suspend fun read(): T?
    suspend fun write(target: T)
    suspend fun awaitReadback(): T?

    suspend fun verify(target: T): Boolean {
        if (read() == null) return false
        write(target)
        return awaitReadback() == target
    }
}
