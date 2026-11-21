# Gen5W Walk-Away Auto Door Lock (`gen5w.autolock`)

## 1. Project Overview & Target Hardware
- **Vehicle & Head Unit:** Pre-2024 Hyundai Ioniq 5 (`NEEV.USA.S5W_M.V015.012.260507`) running Hyundai Mobis Wide Gen5W infotainment.
- **Operating System:** Android 4.4.2 KitKat (API level 19, 32-bit ARMv7, TCC897x SoC), screen resolution `1280x720` (or ultrawide split with `720px` height).
- **Execution Environment:** Rooted via **NEMESIS** (`hmg_nemesis`), which supports installing packages composed of an Android APK (`[app.0]`) and a persistent background root shell service (`[service.0]`).
- **Purpose:** Provide Walk-Away Auto Door Locking when the driver parks, turns off ignition (`IGN OFF`), exits the vehicle, and all monitored doors close (optionally ignoring rear hatch/trunk, default **ON**), with a configurable delay (`5s`–`20s`, default `10s`), automatic phone proximity detection (Wi-Fi Direct priority, Bluetooth fallback, with **Require Phone** default **ON**), a final check that all cabin doors are closed and not already locked, and an 8-second key-fob safety warning gate on launch (**Master Auto-Lock** default **OFF** on fresh install).

---

## 2. Architecture (`MainActivity.java` + `AutoLockBridgeService.java` + `daemon.sh`)
Because Android 4.4.2 restricts normal non-system apps from calling privileged Binder services directly via `service call`, while `/system/bin/sh` root scripts cannot easily implement complex `android.os.Binder` callback interfaces (`IBCMListener`) or query Android's `BluetoothAdapter` profile state APIs, the project uses a hybrid architecture communicating atomically through `/data/nemesis/autolock/`:

### A. Background Android Bridge Service (`AutoLockBridgeService.java` + `BootReceiver.java`)
- **Sources:**
  - `packages/gen5w.autolock/app_src/java/com/gen5w/autolock/AutoLockBridgeService.java`
  - `packages/gen5w.autolock/app_src/java/com/gen5w/autolock/BootReceiver.java`
- **Role:**
  1. **Lifecycle:** Started at `BOOT_COMPLETED` by `BootReceiver`, on UI launch by `MainActivity`, and monitored/restarted every ~15s if missing by `daemon.sh` via `am startservice`.
  2. **Binder Callback Listener (`com.hkmc.bcm.IBCMListener`):**
     - Obtains `com.hkmc.micom.bcm` via reflection on `android.os.ServiceManager.getService(...)`.
     - Registers via `IBCMService.registerBCMListener(IBCMListener, IBinder)` (transaction code `1`).
     - Decodes transaction `2` (`onDoorAllStateChanged(10 ints)`):
       - Writes `<anyCabinOpen> <allCabinClosed> <trunkOpen>` atomically to `/data/nemesis/autolock/door_state`.
       - Writes `<allLocked> <drvUnlock> <pasUnlock> <rlUnlock> <rrUnlock>` (`0 = locked`, `1 = unlocked`) atomically to `/data/nemesis/autolock/lock_state`.
  3. **Bluetooth Connection Monitor (`1s` poll):**
     - Polls `BluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)` and `BluetoothProfile.A2DP`.
     - Writes `"1"` or `"0"` atomically to `/data/nemesis/autolock/bt_connected`.

### B. Android Settings UI (`MainActivity.java`)
- **Source:** `packages/gen5w.autolock/app_src/java/com/gen5w/autolock/MainActivity.java`
- **Role:**
  1. Displays an 8-second **Important Safety Notice** warning that key fob presence inside the car is not checked (`DISCLAIMER_WAIT_SEC = 8`) before revealing settings.
  2. Displays live **Dynamic Logic Workflow** panels at top (or a red **Switched OFF** notice when Master Auto-Lock is disabled) reflecting delay, hatchback setting, `Require Phone` setting, and live phone state (`wifi_state` + `bt_connected`).
  3. Persists `/data/nemesis/autolock/{enabled,delay,ignore_hatch,require_phone}` atomically. Default on fresh install: `enabled=0`, `delay=10`, `ignore_hatch=1`, `require_phone=1`.

### C. Root Daemon (`autolock-daemon` -> `svc/daemon.sh`)
- **Source:** `packages/gen5w.autolock/svc/daemon.sh`
- **Role:**
  - Runs continuously as `uid=root` (`mode=restart` in `NEMESIS.data`).
  - Polls Ignition status via `service call com.hkmc.micom.bcm 17` (`getIGNStatus`, preserving previous state if an IPC call fails) and Wi-Fi Direct / P2P ARP peer reachability (`p2p*` preferred, `wlan*` fallback, `ping -c 1 -W 1`) written to `/data/nemesis/autolock/wifi_state`.
  - Tracks `session_phone` (`wifi` -> `bt` fallback -> `none`) with a 5s hysteresis while `IGN ON` so transient Wi-Fi glitches don't drop tracking while mid-drive disconnects still update cleanly before `IGN OFF`.
  - State machine on `IGN ON -> IGN OFF` (when `enabled=1`):
    1. Waits for at least one monitored door to open (`eff_any=1`) and then all monitored doors to close (`eff_closed=1`; rear hatch included only when `ignore_hatch=0`).
    2. Counts down `delay` seconds (`5`–`20s`) from when the last monitored door closed.
    3. Checks phone requirement/proximity:
       - If `session_phone="none"` and `require_phone=1`, skips locking (`SKIP_LOCK: No phone detected`) and sets `locked_this_cycle=1` without falling back to timer-only locking.
       - If `session_phone="wifi"`, holds until `wifi_fail_streak >= 2`.
       - If `session_phone="bt"`, holds until `bt_conn=0`.
    4. Final safety check: re-reads `door_state` (aborting if empty/open) and `lock_state` (skipping redundant lock command if `all_locked_now=1`). Otherwise executes `service call com.hkmc.micom.bcm 5 i32 1` (`IBCMService.lockDoor(true)`).
  - Writes bounded logs to `/data/nemesis/autolock/autolock.log` and mirrors logs to USB (`<usb>/autolock.log`) **only** if `<usb>/autolock.log` already exists on the mounted USB drive.

---

## 3. Reverse-Engineered `com.hkmc.micom.bcm` Binder Protocol Reference

### Service Name & Interface Descriptor
- **Binder Service Name:** `com.hkmc.micom.bcm`
- **Interface Descriptor:** `com.hkmc.bcm.IBCMService`
- **Implementation Class (in `/system/framework/automotive-service.jar`):** `com.hkmc.micom.BCMService`

### Key `IBCMService` Transaction Codes
| Code (Dec) | Code (Hex) | Method Signature | Description & Usage |
| :--- | :--- | :--- | :--- |
| `1` | `0x01` | `registerBCMListener(IBCMListener listener, IBinder cb)` | Registers a callback listener. Immediately fires current cached state (`onACCChanged`, `onIgnitionChanged`, `onDoorAllStateChanged`, `onDoorLockStateChanged`, etc.) upon registration. |
| `2` | `0x02` | `unregisterBCMListener(IBCMListener listener, IBinder cb)` | Unregisters a previously registered callback listener. |
| `5` | `0x05` | `lockDoor(boolean lock)` | **Locks or unlocks all vehicle doors.**<br>Lock: `service call com.hkmc.micom.bcm 5 i32 1`<br>Unlock: `service call com.hkmc.micom.bcm 5 i32 0`<br>Under the hood in `BCMService.lockDoor(Z)`, this sends `C_PreCrankReq(1)` followed by `C_CtrlDrLockRlyIBOX(1)` (or `C_CtrlDrUnlockRlyIBOX(1)`) over B-CAN to the IBU/BCM. **Note:** Vehicle hardware only honours `lockDoor` when ignition is `OFF` or gear is in `P` (Park). |
| `9` | `0x09` | `getDoorLockStatus()` | Returns cached overall door lock state. |
| `14` | `0x0e` | `getGearStatusInfo()` | Returns current gear state (`1` = Park, `0` = Reverse/Drive/Neutral depending on sub-state). |
| `15` | `0x0f` | `getHoodStatusInfo()` | Returns hood open/close state. |
| `17` | `0x11` | `getIGNStatus()` | Returns ignition state: `1` (`00000001`) = `IGN ON`, `0` (`00000000`) = `IGN OFF`. |
| `85` | `0x55` | `requestAllResponse()` | Requests MICOM to re-broadcast all BCM statuses. |

### Key `IBCMListener` Callback Transaction Codes
Interface descriptor: `com.hkmc.bcm.IBCMListener`

| Code (Dec) | Code (Hex) | Callback Method & Parameters | Parameter Breakdown (Verified in `BCMService.smali` lines 17716–17776) |
| :--- | :--- | :--- | :--- |
| `2` | `0x02` | `onDoorAllStateChanged(int, int, int, int, int, int, int, int, int, int)` | Reads **10 `int` values** in exact order:<br>1. `mDrvUnlockState` (`0` = locked, `1` = unlocked)<br>2. `mPasUnlockState` (`0` = locked, `1` = unlocked)<br>3. `mRLUnlockState` (`0` = locked, `1` = unlocked)<br>4. `mRRUnlockState` (`0` = locked, `1` = unlocked)<br>5. `mDrvOpenedState` (`0` = closed, `1` = open) — **Driver cabin door**<br>6. `mPasOpenedState` (`0` = closed, `1` = open) — **Passenger cabin door**<br>7. `mRLOpenedState` (`0` = closed, `1` = open) — **Rear-Left cabin door**<br>8. `mRROpenedState` (`0` = closed, `1` = open) — **Rear-Right cabin door**<br>9. `mTrunkOpenedState` (`0` = closed, `1` = open) — **Rear Hatch / 5th Door**<br>10. `mHoodStatus` (`0` = closed, `1` = open) — **Front Hood / Frunk** |
| `3` | `0x03` | `onDoorLockStateChanged(int state)` | Overall lock state change notification. |
| `4` | `0x04` | `onACCChanged(int state)` | Accessory power state (`5` when running, `4` on power down). |
| `5` | `0x05` | `onIgnitionChanged(int state)` | Ignition state (`1` = ON, `0` = OFF). |
| `15` | `0x0f` | `onSideMirrorStatusChanged(int state)` | Side mirror fold/unfold status (`0` = folded/closed, `1` = unfolded). |
| `19` | `0x13` | `onGearStatusInfoChanged(int gear, int sub)` | Gear shift notification (`(1, -1)` = Park). |

---

## 4. Platform Quirks & Important Implementation Notes
1. **Android 4.4.2 Shell (`toolbox`) Limitations:**
   - The head unit's `/system/bin/sh` environment uses Android's minimal `toolbox` without `tr`, `awk`, or `sed`.
   - Never use `tr` in `daemon.sh`; use the pure-shell `one_line()` helper function instead.
   - Always normalize shell scripts (`.sh`) and `NEMESIS.data` to Unix `LF` (`\n`) line endings and `0755` permissions inside the zip archive (handled automatically by `scripts/build_package.py`).
2. **APK Signing & Target SDK:**
   - Android 4.4.2 KitKat **requires v1 (JAR) signing** (`--v2-signing-enabled false --v3-signing-enabled false`) and `--min-api 19` when dexing with `d8`. This is fully configured in `scripts/build_apk.py`.
3. **Ideas for Finishing Touches in This Spin-Off Project:**
   - **Background Android `Service` for `IBCMListener` & Bluetooth:** Currently `MainActivity` registers the `IBCMListener` and polls `BluetoothAdapter`. Converting that bridge into a lightweight persistent Android `Service` (started on `BOOT_COMPLETED` or by `daemon.sh` via `am startservice`) will ensure `door_state` and `bt_connected` are always updated even if the user hasn't opened the `Gen5W Auto Lock` UI app since booting the car!
   - **Daemon-Side Bluetooth Fallback:** In `daemon.sh`, `dumpsys bluetooth_manager` is already available to root. If the UI/Service isn't running, `daemon.sh` can also parse `dumpsys bluetooth_manager` directly to check if any device is connected.
   - **Passive Walk-Away Re-Lock Prevention:** Optionally track if the car was already locked (`mDrvUnlockState == 0 && mPasUnlockState == 0 && mRLUnlockState == 0 && mRRUnlockState == 0`) so it doesn't send a redundant lock pulse if the user already locked the door via the exterior handle or key fob.

---

## 5. Building the Package
From `f:\Antigravity Projects\Gen5WAutoLock`:
```powershell
python scripts/build_apk.py
python scripts/build_package.py
```
This produces the ready-to-install NEMESIS USB files in:
- `dist/autolock-installer/gen5w.autolock-1.0.0.zip`
- `dist/autolock-installer/NEMESIS.data`
