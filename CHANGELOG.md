# Changelog

All notable changes to **Timed Auto-Lock** (`gen5w.autolock`) are documented in this file.

## [v1.5.0] — 2026-11-15

### Added
- **Automatic Wi-Fi Direct Proximity Detection:** Automatically detects phones connected via Wireless CarPlay / Android Auto (`wlan0` peer reachability) and holds off auto-locking after the timer expires until the phone drops out of range (~15–20 ft outside the vehicle).
- **Automatic Phone Priority & Fallback:** Automatically selects **Wi-Fi Direct** first, falls back to **Bluetooth (`HFP` / `A2DP`)** if no Wi-Fi Direct phone is detected, or runs on the **timer alone** if no phone is connected (no manual Bluetooth toggle required).
- **Live Phone Status Box:** Added a status indicator box with a green/red light displaying `Wi-Fi Direct Phone Connected`, `Bluetooth Phone Connected`, or `No Phone Detected, Timer only`.
- **Dynamic Workflow Panels:** Top workflow diagram now updates live based on your selected delay, hatchback option, and detected phone connection.
- **Ignore Hatchback Setting:** Added the **Ignore Hatchback being open** checkbox (default **ON**); when unchecked, the timer and final lock check wait for the rear hatch to close as well.
- **Large Touch Delay Stepper:** Replaced the slider with a high-visibility number box and large `-` / `+` touch buttons (`5s`–`20s`) with instant `✓ Saved` confirmation.
- **Opt-In USB Troubleshooting Log:** Writes logs to `autolock.log` on the root of a USB drive only if an existing `autolock.log` file is already present on the drive; keeps internal `/data/nemesis/autolock/autolock.log` bounded.

### Changed
- Redesigned the main UI layout for `1280x720` automotive readability while preserving the 5-second key-fob safety warning screen on launch.

---

## [v1.0.0] — 2026-11-13

### Added
- Initial release of **Timed Auto-Lock** (`gen5w.autolock`) for NEMESIS-equipped Hyundai/Kia Gen5W head units (tested on '22 Hyundai Ioniq 5 Limited).
- 5-second countdown safety acknowledgement screen warning that key fob presence inside the vehicle is not checked.
- Background `AutoLockBridgeService` + `BootReceiver` and root `autolock-daemon` (`daemon.sh`) monitoring ignition (`IGN OFF`), 4 cabin doors (excluding rear hatch), configurable `5s`–`20s` delay, and optional Bluetooth disconnect check.
- Custom padlock + clock launcher icon (`ic_launcher.png`).
