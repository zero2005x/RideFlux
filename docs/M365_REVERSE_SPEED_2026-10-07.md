# M365 reverse-speed correction — 2026-10-07

The owner reports that forward and free-spinning speed look correct, but moving
the connected Xiaomi M365 backwards displays about 64 km/h in red. The previous
B5 decoder treated the two-byte word at B0 payload offset 10 as unsigned.
For example, the LE bytes `18 FC` represent -1000 m/h, but were decoded as
64536 / 1000 = 64.536 km/h.

The wire word remains unsigned in `speedRaw` for diagnostics. M365 `speedKmh`
interprets it as signed LE16, widens it to Int before taking its absolute value,
and divides by 1000. Telemetry therefore reports the magnitude in either
direction. Only the exact zero word gives zero speed. This also fixes very slow
reverse words such as `FF FF` (0.001 km/h), which the previous `>= 0xFF00` guard
incorrectly treated as unknown. The signed minimum `00 80` gives 32.768 km/h
without overflowing Short during absolute-value conversion.

The [M365 ESC register map](https://github.com/etransport/ninebot-docs/wiki/M365ESC)
documents B5 as speed in m/h. The reverse-sign interpretation is inferred from
the owner's symptom and the matching unsigned wraparound; no new raw capture or
physical scooter run was performed for this change. Regression vectors are
synthetic. A physical check should compare forward, backward, free-spinning and
stopped readings with the scooter after installing the corrected build.

Ninebot ES2 sign and scale remain unverified. Its diagnostic speed decoder keeps
the previous unsigned scale and unknown-speed guard; production pairing and
lock behavior are unchanged. Xiaomi lock/unlock/power writes remain disabled.
Nonzero reverse speeds revoke a stationary permit rather than becoming zero.

Regression coverage includes forward/free-spinning scale, reverse 1 and 2.5
km/h, both sides of the former sentinel boundary, reverse 0.001 km/h, exact
zero, signed extrema, stationary-permit revocation and authenticated UART
telemetry delivery. Existing checksum and malformed-payload rejection tests
remain in place.
