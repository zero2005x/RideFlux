package com.rideflux.data.ble

import org.junit.Assert.*
import org.junit.Test

class ScooterClassifierTest {
    @Test fun recognizesKnownScooterNamesAndFe95() {
        for (name in listOf("MIScooter3456", "Ninebot KickScooter Max", "ES2-9901", "G30-1", "F30")) {
            assertEquals(name, ScooterClassifier.classify(name, emptySet()))
        }
        assertEquals("Ninebot/Xiaomi Scooter", ScooterClassifier.classify(null, setOf("FE95")))
        assertEquals("Mi device", ScooterClassifier.classify("Mi device",
            setOf(GattUuids.SERVICE_FE95.toString())))
        assertFalse(ScooterClassifier.isNinebotRetailCandidate("MIScooter3456"))
        assertFalse(ScooterClassifier.isNinebotRetailCandidate("Mi device"))
        assertTrue(ScooterClassifier.isNinebotRetailCandidate("ES2-9901"))
    }

    @Test fun excludesWheelNamesEvenWithFe95AndUnrelatedAdvertisements() {
        for (name in listOf("Begode Master", "Inmotion V11", "KingSong S22", "Veteran Sherman", "Ninebot One S2")) {
            assertNull(ScooterClassifier.classify(name, setOf("fe95")))
        }
        assertNull(ScooterClassifier.classify("Heart Rate Monitor", emptySet()))
        assertNull(ScooterClassifier.classify(null, emptySet()))
    }

    @Test
    fun identifiesXiaomiMiCandidates() {
        assertTrue(ScooterClassifier.isXiaomiMiCandidate("MIScooter3456"))
        assertTrue(ScooterClassifier.isXiaomiMiCandidate("Xiaomi Pro 2"))
        assertTrue(ScooterClassifier.isXiaomiMiCandidate("M365-1234"))
        assertTrue(ScooterClassifier.isXiaomiMiCandidate("Ninebot/Xiaomi Scooter"))
        assertFalse(ScooterClassifier.isXiaomiMiCandidate("Ninebot ES2"))
        assertFalse(ScooterClassifier.isXiaomiMiCandidate("KickScooter Max"))
    }
}
