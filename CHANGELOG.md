# Changelog

All notable changes to **Timed Auto-Lock** (`gen5w.autolock`) are documented in this file.

## [v1.6.0] — 2026-11-20

### Added
- **Require Phone Setting (Default ON):** Added the **Require Phone (Wi-Fi or Bluetooth)** checkbox on the settings screen (enabled by default). When checked, auto-locking requires a detected phone to disconnect/drop signal and will not fall back to locking purely on the timer alone unless manually unchecked.
- **Skip Redundant Lock Commands:** Checks door unlock states (`onDoorAllStateChanged` -> `/data/nemesis/autolock/lock_state`) right before locking and skips issuing `lockDoor(true)` if all four cabin doors are already locked (e.g., via exterior handle or key fob).
- **Post-Lock Door Re-Open Re-Arming:** Opening a monitored door while the head unit is still awake after a completed lock/skip cycle now automatically re-arms a fresh countdown + phone-drop cycle (grabbing an item from the car won't leave it unlocked afterward).
- **Expanded Automotive Bluetooth Detection:** Added head-unit sink/client profile checks (`A2DP_SINK`, `HEADSET_CLIENT`) alongside `HEADSET`/`A2DP` and bonded `BluetoothClass.Device.Major.PHONE` connection checks (ignoring non-phone accessories like Bluetooth OBD2 dongles).

### Fixed & Improved
- **Multi-Peer Wi-Fi ARP Scanning:** `daemon.sh` now prioritizes `p2p*` interfaces and iterates across multiple `/proc/net/arp` entries (testing the last active peer first) so stale router or prior-device ARP entries cannot hide an active Wireless CarPlay / Android Auto phone.
- **BCM Service Reconnect & Watchdog:** Added `IBinder.isBinderAlive()` and `linkToDeath` auto-reconnection in `AutoLockBridgeService` plus a ~15s `ps` watchdog in `daemon.sh` to recover cleanly across warm head-unit sleep or early boot.
- **Atomic IPC File Updates & Race Hardening:** Switched Java and daemon state/config writes to atomic `.tmp` + `rename` with `0666` permissions, added sub-second door open/close sequence counters (`cabinEventSeq` / `allDoorEventSeq`), preserved `IGN` state across transient Binder errors, added 5s Wi-Fi disconnect hysteresis while driving, and paused the 8-second safety disclaimer countdown if the app is backgrounded.

---

## [v1.5.0] — 2026-11-15

### Added
- **Automatic Wi-Fi Direct Proximity Detection:** Automatically detects phones connected via Wireless CarPlay / Android Auto (`wlan0` peer reachability) and holds off auto-locking after the timer expires until the phone drops out of range (~15–20 ft outside the vehicle).
- **Automatic Phone Priority & Fallback:** Automatically selects **Wi-Fi Direct** first, falls back to **Bluetooth (`HFP` / `A2DP`)** if no Wi-Fi Direct phone is detected, or runs on the **timer alone** if no phone is connected (no manual Bluetooth toggle required).
- **Live Phone Status Box:** Added a status indicator box with a green/red light displaying `Wi-Fi Direct Phone Connected`, `Bluetooth Phone Connected`, or `No Phone Detected, Timer only`.
- **Master Auto-Lock Switch (Default OFF):** Added a prominent **Master Auto-Lock** switch on the settings page (defaulting to **OFF** on fresh install so users must open the app and acknowledge the safety warning before enabling); switching it **OFF** immediately updates the top workflow display to show that auto-locking is disabled.
- **Dynamic Workflow Panels:** Top workflow diagram now updates live based on the Master switch, selected delay, hatchback option, and detected phone connection.
- **Ignore Hatchback Setting:** Added the **Ignore Hatchback being open** checkbox (default **ON**); when unchecked, the timer and final lock check wait for the rear hatch to close as well.
- **Large Touch Delay Stepper:** Replaced the slider with a high-visibility number box and large `-` / `+` touch buttons (`5s`–`20s`) with instant `✓ Saved` confirmation.
- **Opt-In USB Troubleshooting Log:** Writes logs to `autolock.log` on the root of a USB drive only if an existing `autolock.log` file is already present on the drive; keeps internal `/data/nemesis/autolock/autolock.log` bounded.

### Changed
- Redesigned the main UI layout for `1280x720` automotive readability and extended the launch key-fob safety warning countdown from 5 seconds to 8 seconds.

---

## [v1.0.0] — 2026-11-13

### Added
- Initial release of **Timed Auto-Lock** (`gen5w.autolock`) for NEMESIS-equipped Hyundai/Kia Gen5W head units (tested on '22 Hyundai Ioniq 5 Limited).
- 5-second countdown safety acknowledgement screen warning that key fob presence inside the vehicle is not checked.
- Background `AutoLockBridgeService` + `BootReceiver` and root `autolock-daemon` (`daemon.sh`) monitoring ignition (`IGN OFF`), 4 cabin doors (excluding rear hatch), configurable `5s`–`20s` delay, and optional Bluetooth disconnect check.
- Custom padlock + clock launcher icon (`ic_launcher.png`).
