# Cyberpunk Commute: Building a Modern AR Heads-Up Display for the Inmotion V5F with Jetpack Compose

*How I built RideFlux from scratch to show real-time electric unicycle diagnostics on AR glasses, making my rides safer and way cooler. Updated for what it became: an open-source app on Google Play.*

> **Updated October 2026.** I first published this post in December 2025, about the first version of RideFlux. Since then I have rebuilt the app, the way it talks to the glasses is completely different, and RideFlux is on Google Play. I kept the original story, corrected what is no longer true, and put the news in the last sections.

## The "Why"

There is a specific kind of freedom that comes with riding an Electric Unicycle (EUC). It's just you, a wheel, and the sensation of gliding. However, that freedom comes with anxiety: How fast am I going? How much battery is left? Is the mainboard heating up on this hill?

With my Inmotion V5F, checking these stats meant one of two things:

- Pulling out my phone (dangerous and inconvenient).
- Glancing down at a smart watch (takes eyes off the road, breaking concentration).

When you are balancing on one wheel at 25 km/h, taking your eyes off the terrain ahead is a bad idea. I realized I needed a Heads-Up Display (HUD), something fighter pilots have used for decades to keep critical data in their line of sight.

Enter AR glasses. The first version of this project targeted a Rokid Air; today I wear Rokid Glasses RV101. My goal was simple but technically challenging: bridge the gap between the EUC's internal Bluetooth sensors and the glasses on my face to create a real-time, unobtrusive riding dashboard.

## The Turning Point: Why I Didn't Just Fork WheelLog

Initially, my plan was to simply fork and modify the open-source WheelLog app. However, the moment I looked at the source code, I hit a wall. WheelLog relies heavily on outdated Android technologies, legacy Java code, and clunky XML layouts. As a developer, I just couldn't stand working with that archaic tech stack.

I decided to scrap the modification idea entirely and build a brand-new application from the ground up. I named the project RideFlux. By using modern Kotlin and Jetpack Compose, I could create a highly reactive, maintainable, and clean architecture perfectly suited for real-time telemetry.

**A word on WheelLog,** because I owe it more than that paragraph suggests. Not forking it did not mean not learning from it. WheelLog is open source (GPL-3.0) and one of the places where the community's knowledge of these wheels' Bluetooth protocols is written down. RideFlux's decoders are my own Kotlin, written with reference to open-source projects such as WheelLog, and some of my test data, including real frames recorded from a V5F, is taken from WheelLog's tests. That is credited in the repository's NOTICE file, and RideFlux is licensed GPL-3.0-or-later as well.

## The Gear List

- **Wheels:** an Inmotion V5F (the test wheel of the first version) and a Begode A2 (my main wheel today, and the only wheel I have verified the current app on; more on that below).
- **Glasses:** Rokid Glasses RV101, which run their own Android apps and connect to the phone wirelessly, with no cable. (The first version, as originally described in this post, drove a Rokid Air over USB-C as an external display.)
- **Compute:** an Android phone (Android 9 or later) acting as the bridge.

## The Technical Challenge

The project breaks down into three hurdles:

1. **The data source (Bluetooth LE):** reliably connecting to a wheel and decoding its undocumented Bluetooth protocol into speed, battery, voltage and temperature, for more than one brand.
2. **The display layer (Jetpack Compose):** a declarative, high-contrast UI built for transparent AR glasses that doesn't distract from the real world.
3. **The link:** getting that data from the phone to the glasses without two devices fighting over the wheel's single Bluetooth connection. In version one this hurdle did not exist, because the glasses were just a second screen on a cable.

## Part 1: Decoding the Wheels' Bluetooth Protocols

EUC manufacturers rarely provide public SDKs. To get data out of a wheel you have to dive into Bluetooth Low Energy (BLE). I used modern Kotlin Coroutines and Flow to handle the connection asynchronously, moving away from legacy callback hell. In the first version, through some trial and error, I identified the right service and read characteristic UUIDs for the V5F, subscribed to the notifications, and parsed the byte arrays that arrive every few hundred milliseconds.

That approach does not scale to more wheels, and three things I learned along the way shaped the current decoders.

**1. You often can't trust the advertisement.** The V5F puts only its local name on the air, with no service UUID, so you cannot filter the scan by UUID. RideFlux scans unfiltered and works out the wheel's family from the advertised name first, falling back to the GATT service UUIDs. A UUID-only guess is never trusted until the wheel has answered its identification handshake.

**2. Bytes get escaped, and notifications get cut in the wrong place.** The V5F's protocol wraps a CAN-style record in `AA AA ... 55 55`. Inside that, the values `AA`, `55` and `A5` are escaped with a leading `A5`, and so is the checksum byte. Real V5F frames escape about one byte in a hundred, and a BLE notification is only 20 bytes, so one will regularly end right after the escape marker, before the byte it escapes. That is not a broken frame. It is a frame still arriving. Treating it as an error silently drops frames.

**3. Signed means signed.** Pitch, roll and the two speed components are signed 32-bit values. Read them as unsigned and a slightly negative angle becomes tens of millions of degrees, and a wheel standing still becomes millions of km/h. This is the kind of bug that only shows up with real frames, so the tests use captures of a V5F on a bench:

```kotlin
// From InmotionI1SignednessTest: frames recorded from a V5F on a bench.
@Test
fun `the derived speed is a rideable number for either sign`() {
    assertEquals(1.178, telemetryOf(InmotionI1RealFrames.negativeAngles).speedKmh(), 1e-3)
    assertEquals(1.546, telemetryOf(InmotionI1RealFrames.positiveRoll).speedKmh(), 1e-3)
}
```

To keep eight wheel families from turning into spaghetti, every codec is a small, stateless object behind one interface. The per-connection state (reassembly buffers, keystreams) lives in an opaque object that the caller threads through, and a bad frame can never take down the connection, because `decodeSafely` turns any exception into an event:

```kotlin
// Abridged from the domain module.
interface WheelCodec {
    fun newState(): State
    fun decode(state: State, bytes: ByteArray): List<DecodeEvent>
    fun decodeSafely(state: State, bytes: ByteArray): List<DecodeEvent> // never throws
    fun encode(state: State, command: WheelCommand): List<ByteArray>
}

sealed class DecodeEvent {
    data class TelemetryUpdate(val snapshot: WheelTelemetry) : DecodeEvent()
    data class Alert(val alert: WheelAlert) : DecodeEvent()
    data class Malformed(val reason: String, val offendingBytes: ByteArray?) : DecodeEvent()
    // ...plus Identified, emitted once per handshake
}
```

One more rule has paid for itself many times: in the telemetry model, `null` means *unknown*, never "zero" and never "no fault". An alert system that confuses the two is worse than none.

## Part 2: Designing the AR Interface with Jetpack Compose

Once I had a clean stream of data flowing into the app state, I needed to show it on the glasses. This is where Jetpack Compose shines. Instead of rigid XML layouts and TextViews, I built a fluid, declarative UI. Designing for AR still comes with its own rules, and most of them survived every rewrite:

- **Black is transparent.** In optical see-through AR, black pixels are transparent. The background of the Compose surface must be pure black (`Color.Black`). Some glasses' optics flip the image, so the HUD has a one-switch horizontal mirror too.
- **Contrast is king.** Bright neon green and cyan on black, with white at 70 % opacity for secondary labels, read well against unpredictable real-world backgrounds.
- **Three zones, nothing heavy.** Clock and phone/glasses status on one side, a big speed number in the middle, wheel battery and trip on the other. No cards, no gradients, no borders: anything heavier bleeds light into your view.
- **A warning must be impossible to miss, and impossible to mute by accident.** When a safety threshold trips, the whole HUD gets a flashing red frame. A ring press can blank the HUD to cut glare, but an active warning still breaks through the blank:

```kotlin
// Abridged from the HUD screen. Blanked by a ring press: only the drawing
// stops. A live safety threshold is the one thing that still breaks through.
val suppressed = !hudVisible && !uiState.thresholdAlertActive
...
if (uiState.thresholdAlertActive && phaseOf(uiState) == HudPhase.Ready && !suppressed) {
    val flash by rememberAlertFlash()
    Box(Modifier.fillMaxSize().border(8.dp, Color.Red.copy(alpha = flash)))
}
```

In the first version I projected the Compose layout onto the glasses with Android's Presentation API, treating them as a second 1080p display on a USB-C cable. That is gone. The Rokid Glasses RV101 are standalone Android devices, so RideFlux now ships a second, tiny app that runs **on the glasses**. It is still Compose all the way down, but the phone no longer sends pixels. It sends data.

## Part 3: Getting the Data from the Phone to the Glasses

Two apps means a wireless link, and this turned out to be the part with the most surprises. The phone keeps the wheel's single Bluetooth connection and re-broadcasts a compact 20-byte frame once a second over a small BLE GATT service, with the phone as the peripheral and the glasses as the central. The frame fits the default 23-byte ATT MTU, so a notification always carries a whole frame. (Rokid's own CXR channel is supported as an alternative, and falls back to the BLE bridge when it can't connect.)

The details that cost me the most time:

- **Identify the phone by a token, not by its Bluetooth address.** Android advertises from a private address that rotates roughly every 15 minutes and changes on every Bluetooth restart, so a MAC address recorded at pairing time quietly stops matching minutes later. The phone mints a short token on first run and publishes it with the advertisement, and the glasses pair to that.
- **Don't advertise before the service is registered.** `BluetoothGattServer.addService()` completes asynchronously. Advertise too early and a fast central discovers an empty GATT database, which Android then caches by address, and the usual escape hatch (`BluetoothGatt.refresh()`) is a hidden API that has been blocked since Android 9.
- **Android has a scan budget.** Start a scan more than five times in 30 seconds and Android silently stops delivering results, with no callback to tell you. A shared throttle books every scan.
- **A link can go silent without dropping.** The phone app gets killed, the rider removes the glasses from the approved list, an approval window expires, and the connection stays up while the notifications simply stop. Once frames have been flowing, the HUD now treats 30 seconds of silence as a dead link and starts over.

## The Result: The First Ride

Putting it all together for the first test ride was a surreal experience. As I accelerated on the V5F, the Compose UI floating in my vision updated instantly and smoothly. Approaching a steep incline, I could monitor my voltage sag in real time without ever looking down at my pedals. It fundamentally changed the safety profile of the ride.

That was the first version, on the V5F. Today I ride a Begode A2, and I want to be exact about what that means for the claims in this post, because they are easy to overstate.

## What I Said I'd Do, and What Happened

- **Turn-by-turn navigation from Mapbox next to the speed.** Not done, and shelved. RideFlux now declares no internet permission at all, so nothing can leave your phone, and an online map SDK would break that promise.
- **Turn the speed red when approaching a limit.** Done, differently and more broadly. A threshold monitor watches overspeed, over-temperature, low battery and PWM load (defaults: 45 km/h, 80 °C, 25 % and 90 %, all adjustable), with debounce, cooldown and hysteresis so that a value hovering on a limit doesn't machine-gun you with alerts. When one trips, the HUD frames the whole view in red.

## Where RideFlux Is Today

- **On Google Play** (v0.1.10 as I write this), with no ads, no analytics, no accounts and **no internet permission**. Everything it records stays on your phone unless you export it.
- **A complete phone app on its own:** live dashboard, BMS details and charts, trip recording with routes, CSV and GPX export, and a ZIP backup. The glasses are optional.
- **Hands-free HUD control:** a Bluetooth ring can show or hide the HUD.
- **18 languages**, including right-to-left Arabic and Urdu.
- **Open source** under GPL-3.0-or-later, with unit tests for the codecs and the bridge, and a continuous-integration pipeline.

### An honest support list

- **Begode / Gotway (A2): verified on hardware.** A couple of readings, trip distance and PWM, are known to be wrong and are being fixed.
- **Other Begode models, KingSong, Veteran, Ninebot and Inmotion (including the V5F): experimental.** Implemented from open-source references and recorded frames, but not yet tried on a real wheel with the current app.

I would rather label a wheel "experimental" than let you trust a battery gauge that has only ever been checked against recorded frames. (Yes, that includes the V5F that started all of this. The current decoders run against real V5F frames, but I have not yet ridden the current app on one.)

### Help me verify your wheel

If you ride one of the experimental wheels, a short report is worth more than any feature: your wheel's model and firmware, and what RideFlux showed next to what the wheel's own display showed. Open an issue on GitHub.

If you are an EUC rider with a pair of AR glasses gathering dust, this is still the ultimate DIY upgrade for your ride. Just keep your eyes on the road and your hands off your phone while you ride.

## Links

- **Google Play:** https://play.google.com/store/apps/details?id=com.rideflux.app
- **Website (18 languages):** https://zero2005x.github.io/RideFlux/
- **Source code:** https://github.com/zero2005x/RideFlux

*RideFlux is an independently developed third-party app and is not affiliated with any wheel manufacturer or Rokid.*
