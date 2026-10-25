/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import android.net.Uri
import com.rideflux.app.navigation.Routes
import com.rideflux.app.ui.dashboard.DashboardViewModel
import com.rideflux.data.ble.FamilyResolutionException
import com.rideflux.data.ble.GattSnapshot
import com.rideflux.data.ble.NegotiatingTransport
import com.rideflux.data.ble.SessionHints
import com.rideflux.data.ble.WheelCodecFactoryImpl
import com.rideflux.data.ble.WheelConnectionImpl
import com.rideflux.data.ble.WheelResolution
import com.rideflux.data.ble.WheelSessionResolver
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.protocol.familyvesc.VescSetupCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * S-01 regression: the scanner's brand choice must survive the route callback
 * and the navigation argument, and reach the resolver as a manual family.
 *
 * Every case starts from the production [scannerDeviceNavigator] — the exact
 * callback `ScannerRoute` installs — encodes the dashboard route with
 * [Routes.dashboard] and decodes the family argument with
 * [Routes.parseFamilyArgument] (the function the NavHost uses). The decoded
 * family is then fed into the real resolver/connection wiring, so a route that
 * dropped the family (the old `family = null` lambda) fails these tests at the
 * navigation boundary rather than only at the resolver.
 *
 * Software-only: a fake negotiating transport replays fixed frames; no Android
 * GATT stack, no wheel.
 */
@RunWith(RobolectricTestRunner::class)
class ScannerRouteFamilySelectionTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private val factory = WheelCodecFactoryImpl()
    private val resolver = WheelSessionResolver(factory)

    // The UUID constants are internal to :data:ble; the wire values are
    // fixed by the Bluetooth SIG / Nordic UART and repeated here.
    private val nusTable = GattSnapshot(
        deviceName = null,
        services = mapOf(
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e") to listOf(
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            ),
        ),
    )

    private fun singleCharTable(name: String?) = GattSnapshot(
        deviceName = name,
        services = mapOf(
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb") to listOf(
                UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            ),
        ),
    )

    private fun unnamedNusDevice() =
        DiscoveredWheel(address = address, displayName = null, rssi = -60, family = null)

    /**
     * Run one scanner event through the production route mapping, then through
     * the production route encoding/decoding. Returns the navigation arguments
     * and the family the dashboard would receive.
     */
    private fun navigate(selection: ScannerSelection): Pair<WheelNavigationSelection, WheelFamily?> {
        var captured: WheelNavigationSelection? = null
        val navigate = scannerDeviceNavigator { address, family, category ->
            captured = WheelNavigationSelection(address, family, category)
        }
        navigate(selection)
        val target = requireNotNull(captured) { "the scanner route callback did not navigate" }
        val route = Routes.dashboard(target.address, target.family, target.category)
        val familyArgument = Uri.parse(route)
            .getQueryParameter(DashboardViewModel.ARG_FAMILY)
        return target to Routes.parseFamilyArgument(familyArgument)
    }

    private class FakeNegotiatingTransport(
        private val snapshot: GattSnapshot,
        private val resolve: (GattSnapshot) -> WheelResolution,
    ) : NegotiatingTransport {
        private val channel = Channel<ByteArray>(64)
        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
        val writes = mutableListOf<ByteArray>()

        override var gattSnapshot: GattSnapshot? = null
            private set
        override var resolution: WheelResolution? = null
            private set

        override suspend fun connect() {
            gattSnapshot = snapshot
            val resolved = resolve(snapshot)
            resolution = resolved
            if (!resolved.usable) throw FamilyResolutionException(resolved)
        }

        override suspend fun disconnect() = Unit

        override suspend fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        suspend fun emit(bytes: ByteArray) {
            channel.send(bytes)
        }
    }

    /**
     * Build the connection exactly as the repository does for a device whose
     * advertisement carried [name]/[services]: the manual family comes from
     * the navigation argument, everything else is discovery.
     */
    private fun connect(
        scope: CoroutineScope,
        snapshot: GattSnapshot,
        manualFamily: WheelFamily?,
        advertisedName: String? = snapshot.deviceName,
    ): Pair<WheelConnectionImpl, FakeNegotiatingTransport> {
        val hints = SessionHints(
            manualFamily = manualFamily,
            savedFamily = null,
            advertisedName = advertisedName,
            advertisedServices = snapshot.services.keys.map { it.toString() }.toSet(),
        )
        val transport = FakeNegotiatingTransport(snapshot) { resolver.resolve(hints, it) }
        val connection = WheelConnectionImpl(
            transport = transport,
            codec = null,
            scope = scope,
            clock = { 1_000L },
            codecForResolution = { resolution ->
                factory.forFamilyWithAddress(requireNotNull(resolution.family), address)
            },
        )
        return connection to transport
    }

    // ------------------------------------------------------------------ cases

    @Test
    fun `a plain tap navigates without a family and an unnamed NUS stays ambiguous`() = runTest {
        val device = unnamedNusDevice()
        val (target, family) = navigate(ScannerSelection.Scanned(device))

        assertEquals(address, target.address)
        assertEquals(PlevCategory.WHEEL, target.category)
        assertNull("a plain tap must not claim a family", target.family)
        assertNull("the dashboard must receive no manual family", family)

        // The repository would build SessionHints.manualFamily from exactly
        // this value; discovery must therefore stay ambiguous and write nothing.
        val (connection, transport) = connect(backgroundScope, nusTable, family)
        connection.start()

        val state = connection.state.value
        assertTrue("expected Failed, got $state", state is ConnectionState.Failed)
        assertEquals(
            ConnectionState.Failed.Reason.UNKNOWN_FAMILY,
            (state as ConnectionState.Failed).reason,
        )
        assertNull(connection.resolvedFamily)
        assertTrue("an ambiguous session must not write", transport.writes.isEmpty())
        connection.close()
    }

    @Test
    fun `a VESC brand choice survives the route and binds the NUS VESC codec`() = runTest {
        val device = unnamedNusDevice()
        val (target, family) = navigate(ScannerBrand.VESC.select(device))

        assertEquals(WheelFamily.VESC, target.family)
        assertEquals(WheelFamily.VESC, family)

        val (connection, transport) = connect(backgroundScope, nusTable, family)
        connection.start()

        assertEquals(WheelFamily.VESC, connection.resolvedFamily)
        assertEquals(ConnectionState.Handshaking(WheelFamily.VESC), connection.state.value)
        assertEquals("the 0x2F poll is the only bootstrap", 1, transport.writes.size)
        assertArrayEquals(VescSetupCodec.setupPoll(), transport.writes.single())

        transport.emit(officialVescFrame())
        runCurrent()

        assertEquals(ConnectionState.Ready, connection.state.value)
        val telemetry = connection.telemetry.value
        assertEquals(36.0f, telemetry.speedKmh!!, 0.001f)
        assertEquals(50.4f, telemetry.voltageV!!, 0.01f)
        assertEquals(80.0f, telemetry.batteryPercent!!, 0.01f)
        assertEquals("VESC", connection.identity.value?.modelName)
        connection.close()
    }

    @Test
    fun `a Gotway-named device with a Veteran brand choice uses V and never bootstraps G`() = runTest {
        val device = DiscoveredWheel(
            address = address,
            displayName = "Gotway Tesla",
            rssi = -55,
            // The scan hint (name) says G; the rider's choice overrides it.
            family = WheelFamily.G,
        )
        val (target, family) = navigate(ScannerBrand.VETERAN.select(device))
        assertEquals(WheelFamily.V, target.family)
        assertEquals(WheelFamily.V, family)

        val (connection, transport) = connect(
            backgroundScope,
            singleCharTable("Gotway Tesla"),
            family,
            advertisedName = "Gotway Tesla",
        )
        connection.start()

        assertEquals(WheelFamily.V, connection.resolvedFamily)
        assertEquals(ConnectionState.Handshaking(WheelFamily.V), connection.state.value)
        assertTrue("family V auto-advertises: no G/K bootstrap may be sent", transport.writes.isEmpty())

        transport.emit(veteranLegacyFrame())
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(20.0f, connection.telemetry.value.speedKmh!!, 0.01f)
        connection.close()
    }

    @Test
    fun `a brand choice the GATT table cannot carry is a conflict with zero writes`() = runTest {
        val device = unnamedNusDevice()
        // Begode uses FFE0/FFE1; this device only exposes Nordic UART.
        val (target, family) = navigate(ScannerBrand.BEGODE.select(device))
        assertEquals(WheelFamily.G, target.family)
        assertEquals(WheelFamily.G, family)

        val (connection, transport) = connect(backgroundScope, nusTable, family)
        connection.start()

        val state = connection.state.value
        assertTrue("expected Failed, got $state", state is ConnectionState.Failed)
        assertEquals(
            ConnectionState.Failed.Reason.UNKNOWN_FAMILY,
            (state as ConnectionState.Failed).reason,
        )
        assertTrue(
            "the diagnostic must name the manual family: ${(state as ConnectionState.Failed).message}",
            state.message.orEmpty().contains("manual selection G"),
        )
        assertNull(connection.resolvedFamily)
        assertTrue("a conflict must not write", transport.writes.isEmpty())
        connection.close()
    }

    @Test
    fun `every brand option is an explicit choice that survives the route`() {
        val device = unnamedNusDevice()
        val families = ScannerBrand.entries.map { brand ->
            val selection = brand.select(device)
            assertTrue("${brand.name} must emit a Brand event", selection is ScannerSelection.Brand)
            assertEquals(brand.family, (selection as ScannerSelection.Brand).family)

            val (target, decoded) = navigate(selection)
            assertEquals("${brand.name} lost its family in the route", brand.family, target.family)
            assertEquals("${brand.name} family argument did not round-trip", brand.family, decoded)
            brand.family
        }
        assertTrue("VESC must be selectable by hand", WheelFamily.VESC in families)
        assertEquals("every brand option must be distinct", families.size, families.distinct().size)
        assertFalse(families.isEmpty())
    }

    // ----------------------------------------------------------------- frames

    /**
     * Official-conformant COMM_GET_VALUES_SETUP reply: speed word 0x2710 =
     * 10000 → 10 m/s → 36 km/h, voltage 50.4 V, battery 80 %. Fixed bytes; no
     * product encoder is used to produce them.
     */
    private fun officialVescFrame(): ByteArray =
        ("02462F00FD0000000004D20000023701F4000007D00000271001F8032000" +
            "000000000000000000000000000000000000000000000000000000000000" +
            "00000000000030390000000083A903")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** Synthetic Veteran legacy 36-byte frame (same shape as the protocol tests). */
    private fun veteranLegacyFrame(): ByteArray {
        val frame = ByteArray(36)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A.toByte()
        frame[2] = 0x5C.toByte()
        frame[3] = 0x20.toByte()
        frame[4] = 0x27
        frame[5] = 0x60
        frame[6] = 0x00
        frame[7] = 0xC8.toByte()
        frame[16] = 0x05
        frame[17] = 0xDC.toByte()
        frame[18] = 0x0D
        frame[19] = 0xAC.toByte()
        frame[28] = 0x03
        frame[29] = 0xE8.toByte()
        frame[30] = 0x00
        frame[31] = 0x02
        frame[34] = 0x09
        frame[35] = 0xC4.toByte()
        return frame
    }
}
