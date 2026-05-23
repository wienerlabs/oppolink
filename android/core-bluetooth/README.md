# `:core-bluetooth`

BLE transport layer: advertising, scanning, and (Sprint 1 D3+) GATT +
L2CAP CoC socket lifecycle.

## Boundary
- **In**: `:core-protocol` for typed wire-format manufacturer-data and the
  service UUID (Rust is the source of truth — Kotlin must not parse the
  payload by hand).
- **Out**: `PeerScanner` and `PeerAdvertiser` interfaces that the `:app`
  layer drives; `Peer` data class for the UI.

## Sprint 1 D2 scope (shipped)
- `OppoLinkUuid` — service UUID + manufacturer ID, sourced from Rust via
  UniFFI so any wire-format change in Rust propagates automatically.
- `BluetoothPermissions` — single source of truth for the runtime permission
  set (handles the API 31 split between `BLUETOOTH_SCAN/CONNECT/ADVERTISE`
  and the legacy API 29–30 set).
- `PeerScanner` — `BluetoothLeScanner` wrapper. Filters by service UUID,
  parses manufacturer-data via Rust, exposes `StateFlow<List<Peer>>` with
  per-peer staleness eviction.
- `PeerAdvertiser` — `BluetoothLeAdvertiser` wrapper. Two-packet advertise:
  primary carries the service UUID, scan response carries the
  manufacturer-data payload (nickname + capability bitmap).
- `BluetoothModule` — Hilt singleton providers for `BluetoothManager`,
  `PeerScanner`, and `PeerAdvertiser`.

## Sprint 1 D3 scope (shipped)
- `ConnectionState` sealed interface — `Idle` / `RoleDecided` / `Connecting`
  / `Handshaking` / `PsmExchanged` / `Failed`.
- `GattServerHost` — registers the OppoLink GATT service, allocates an
  insecure L2CAP server socket via `BluetoothAdapter.listenUsingInsecureL2capChannel()`,
  and bakes the resulting PSM into the handshake characteristic. Singleton,
  kept alive while the discovery screen is mounted.
- `GattClient` — `suspend fetchHandshake(peer)` performs `connectGatt` →
  `discoverServices` → `readCharacteristic`, parses the payload via Rust
  `parseHandshake`, and tears the GATT session down. Cancellation closes
  the GATT handle.
- `PeerConnector` — orchestrator with `StateFlow<ConnectionState>`. The
  tapping side always plays the client role; Rust `decide_role` is reserved
  for Sprint 4 D13 where both peers exchange identifiers over an open
  channel.

## Sprint 1 D4 scope (shipped)
- `L2capChannel` — `AutoCloseable` wrapper around `BluetoothSocket` with
  blocking `send` / `receiveExact`. Owned by exactly one thread.
- `GattServerHost.acceptL2cap()` — blocking accept on the listening L2CAP
  server socket. Called from a dedicated worker `Thread` started by
  `PeerConnector.startPassiveServer`.
- `GattClient.openL2capSocket(peer, psm)` — client-side `createInsecureL2capChannel`
  + blocking `connect()`.
- `GattClient.fetchHandshake` now snapshots the negotiated PHY via
  `BluetoothGatt.readPhy()` and returns a `GattHandshakeResult` carrying
  both the parsed handshake and the PHY (1 / 2 / 3 = LE 1M / 2M / Coded).
- `PeerConnector.runEchoTest(peer, samples)` — drives 10 round-trips on
  `OppoLinkEchoClient` thread (priority MAX), surfaces progress on
  `ConnectionState.EchoInProgress`, finalises with
  `ConnectionState.EchoCompleted(stats, negotiatedPhy)`. Server side runs
  its echo loop on `OppoLinkAccept`.

## Sprint 2+ (next)
- `AudioRecord` capture → Opus encode → `L2capChannel.send`
- `L2capChannel.receiveExact` → Opus decode → `AudioTrack`

## Hard rules
- Never parse manufacturer-data layout in Kotlin. Always call
  `parseManufacturerData(bytes)` through UniFFI.
- The advertiser MUST be a Singleton: the Android BluetoothLeAdvertiser is
  a process-wide handle and recreating it after a Bluetooth toggle leaks
  callbacks on some OEMs.
