package com.rideflux.protocol.familyscooter

import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailFrameAssembler
import com.rideflux.protocol.testutil.hex
import org.junit.Assert.*
import org.junit.Test

class NinebotRetailFrameAssemblerTest {
    @Test fun reassemblesSplitAndCoalescedFrames() {
        val assembler = NinebotRetailFrameAssembler()
        val first = hex("5A A5 00 3E 04 5B 00 62 FF")
        val second = hex("5A A5 02 23 3E 22 04 41 00 35 FF")
        for (byte in first.dropLast(1)) assertTrue(assembler.append(byteArrayOf(byte)).isEmpty())
        val frames = assembler.append(byteArrayOf(first.last()) + second)
        assertEquals(2, frames.size)
        assertArrayEquals(first, frames[0])
        assertArrayEquals(second, frames[1])
    }

    @Test fun discardsNoiseAndBadChecksumBeforeNextValidFrame() {
        val assembler = NinebotRetailFrameAssembler()
        val good = hex("5A A5 00 3E 04 5B 00 62 FF")
        val bad = good.copyOf().apply { this[lastIndex] = 0 }
        val frames = assembler.append(hex("00 01 5A") + bad + good)
        assertEquals(1, frames.size)
        assertArrayEquals(good, frames.single())
        assembler.reset()
        assertTrue(assembler.append(good.copyOfRange(0, 3)).isEmpty())
        assertArrayEquals(good, assembler.append(good).single())
    }
}
