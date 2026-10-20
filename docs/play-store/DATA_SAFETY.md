# Play Console — Data safety answers

What the **Data safety** form for `com.rideflux.app` should say, and the code behind each
answer. This is a working note for whoever fills in the form in Play Console; it is not
submitted anywhere itself. Re-check it whenever [`PRIVACY.md`](../../PRIVACY.md) changes.

Last checked against the code on 2026-10-07 (0.1.10 plus everything merged after it).

## Short answers

| Question | Answer | Why |
|---|---|---|
| Does the app collect or share any of the required user data types? | **No** | Nothing leaves the phone: the manifest has no `INTERNET` permission, and there are no ad, analytics or crash-reporting SDKs. Google's definition of "collect" is transmitting data off the device, and data that is only processed on the device is not collected. |
| Is all of the data encrypted in transit? | Not applicable | Nothing is transmitted. |
| Can users request that data be deleted? | **Yes** | Trips and single scooter pairing keys can be deleted in the app; uninstalling or clearing storage removes everything. Cloud backup is off (`allowBackup="false"`). |
| Privacy policy URL | the published `PRIVACY.md` page (GitHub Pages: `/privacy/`, `/zh-TW/privacy/`) | Rendered by `site/build.py` from `PRIVACY.md`. |

## What the app handles on the device

| Data | Used for | Leaves the phone? |
|---|---|---|
| Precise and approximate location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | Route of a trip, only while a trip is recorded. No background location. | Only if the user exports a trip (CSV/GPX/ZIP) to a place they choose. |
| Wheel and scooter telemetry | Dashboard, alerts, trip samples. | Same as above. |
| Bluetooth addresses and pairing tokens | Reconnecting to the wheel, scooter, glasses and ring. | No. |
| **Scooter pairing keys** (Xiaomi Mi token, Ninebot app random) | Reconnecting to a scooter without pairing again. | Only if the user exports a **`.rfbond`** file: passphrase-encrypted (AES-256-GCM, PBKDF2-HMAC-SHA256, passphrase of at least 10 characters), preceded by a biometric / screen-lock check. |
| Settings | Preferences. | Only inside the user's own trip/settings backup. |
| **Diagnostic log** (bridge state changes and reasons, glasses connect/leave events; addresses cut to their last two bytes; no keys, tokens or location) | Finding out afterwards why the HUD stopped. Capped at about 256 KB. | Only if the user saves it as a text file from Settings. |

## Permissions worth explaining if Play asks

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`: find and talk to the wheel,
  scooter, glasses and ring; advertise to the paired glasses (HUD bridge).
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`: the HUD bridge and
  trip recording run as foreground services with a persistent notification.
- `RECEIVE_BOOT_COMPLETED`: restores the HUD bridge after a reboot, only if the user turned
  on bridge autostart (off by default).
- `USE_BIOMETRIC`: only to ask Android to confirm the user before pairing keys are exported.
  The app never receives biometric data.
- `POST_NOTIFICATIONS`: foreground-service and glasses-pairing notifications.

## Keep in sync

If a future change adds a network permission, an SDK, or a new kind of export, this table and
`PRIVACY.md` must change in the same pull request.
