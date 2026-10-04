package com.rideflux.app.ui.bond

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentResolverBondFileIoTest {
    @get:Rule val folder = TemporaryFolder()

    private val io get() = ContentResolverBondFileIo(RuntimeEnvironment.getApplication().contentResolver)

    @Test fun `a file is read back whole and a missing one reads as null`() = runTest {
        val file = File(folder.root, "a.rfbond").apply { writeBytes(ByteArray(20_000) { it.toByte() }) }
        assertArrayEquals(file.readBytes(), io.read(file.toURI().toString(), 64 * 1024))
        assertNull(io.read(File(folder.root, "missing").toURI().toString(), 1024))
    }

    @Test fun `a file larger than the limit is refused`() = runTest {
        val file = File(folder.root, "big.rfbond").apply { writeBytes(ByteArray(5_000)) }
        assertNull(io.read(file.toURI().toString(), 4_999))
        assertTrue(io.read(file.toURI().toString(), 5_000) != null)
    }

    @Test fun `writing replaces the content and a bad location fails`() = runTest {
        val file = File(folder.root, "w.rfbond").apply { writeBytes(ByteArray(100) { 1 }) }
        assertTrue(io.write(file.toURI().toString(), byteArrayOf(7, 8, 9)))
        assertArrayEquals(byteArrayOf(7, 8, 9), file.readBytes())
        assertFalse(io.write("content://no.such.provider/x", byteArrayOf(1)))
    }
}
