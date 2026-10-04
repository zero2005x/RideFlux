package com.rideflux.domain.safety

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedLoopVerificationTest {
    @Test fun `a write succeeds only after pre-read and matching readback`() = runTest {
        var value: Int? = 1
        var writes = 0
        val verifier = object : ClosedLoopVerification<Int> {
            override suspend fun read() = value
            override suspend fun write(target: Int) { writes++; value = target }
            override suspend fun awaitReadback() = value
        }
        assertTrue(verifier.verify(2))
        assertEquals(1, writes)
        value = null
        assertFalse(verifier.verify(3))
        assertEquals(1, writes)
    }

    @Test fun `mismatched readback is failure even after successful write`() = runTest {
        val verifier = object : ClosedLoopVerification<Int> {
            override suspend fun read() = 1
            override suspend fun write(target: Int) = Unit
            override suspend fun awaitReadback() = 1
        }
        assertFalse(verifier.verify(2))
    }
}
