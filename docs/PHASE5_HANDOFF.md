# Phase 5 handoff: PLEV dashboard and HUD

## Delivered

- `:domain`: `PlevRepository`, typed wheel/scooter connection handles, and `PlevCategory`. `ScooterConnection.lockSupported` defaults to false so UI cannot present an unverified write path as usable.
- `:data:protocol`: `PlevRepositoryImpl` combines wheel discovery with an injectable scooter discovery/connection provider. The production DI configuration currently supplies the existing wheel repository only. Its provider seam is covered by a pure JVM fake test.
- `:app`: DashboardViewModel accepts scooter handles when the navigation category is `SCOOTER` and exposes one `StateFlow<DashboardUiState>`. It projects scooter speed, battery and distance without synthesizing wheel-only metrics. DashboardScreen shows the 20-second pairing banner with a one-time haptic cue, countdown, and Material 3 progress indicator. Scooter lock controls use a capability flag and a stationary UI guard; the connection's MotionInterlock remains authoritative. Main and parameter pages collapse wheel-only metric cards in scooter mode. Navigation carries the category to dashboard and phone HUD routes.
- `:hud-app`: `PlevTelemetrySource` maps scooter telemetry into the existing three-column HUD projection. Direct mode selects it when the activity receives `category=SCOOTER`; the bridge and wheel paths remain intact. HUD layout geometry was not changed.
- Tests: fake scooter projection and action-availability tests in `DashboardViewModelPlevTest`, fake repository routing in `PlevRepositoryImplTest`, and fake direct scooter HUD source mapping in `HudViewModelPlevTest`.

## Protocol boundary

The production Hilt bindings use `PlevRepositoryImpl(wheels)` without a scooter provider. They therefore do not discover or connect real scooters. The Phase 4 evidence still leaves FE95 characteristic selection, encrypted traffic, the 0x5D payload, speed scale, and lock writes unresolved. The new UI path is available to a verified provider or tests; default lock controls remain disabled. `isLocked` remains null until a verified lock-state telemetry field exists. A successful connection or control result is never inferred from an unverified frame.

## Verification

- Java 21, `./gradlew :domain:test :data:protocol:test --no-configuration-cache`: **305 tests, 0 failures** (75 domain, 230 protocol).
- In a clean WSL copy with an Android SDK path overlay: `:app:compileDebugKotlin :hud-app:compileDebugKotlin` succeeded. The copy avoids existing Windows-path KSP caches; it contains the same source changes.
- Focused Android JVM tests passed: `DashboardViewModelPlevTest` (2), `HudViewModelPlevTest` (1), plus the existing `DashboardViewModelBehaviourTest` and `HudViewModelStateTest` suites. All test source sets compiled.

The implementation follows the referenced Android Ninja guidance on domain/UI separation, unidirectional state, stateless Compose components, and fake-based tests: <https://github.com/Drjacky/claude-android-ninja/blob/master/references/architecture.md>.
