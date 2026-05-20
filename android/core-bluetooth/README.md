# `:core-bluetooth`

BLE transport layer: GATT advertising, scanning, characteristic exchange, and
L2CAP CoC socket lifecycle.

## Boundary
- **In**: `:core-protocol` for typed handshake messages and wire format.
- **Out**: an interface that the `:app` layer drives — start scanning, connect
  to a peer, open an L2CAP channel, hand the resulting socket to `:core-audio`.

## Sprint 1 scope
Stubs only. Real implementation lands in Sprint 1 D2–D4.
