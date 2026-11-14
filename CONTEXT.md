# Gen5W Walk-Away Auto Door Lock (`gen5w.autolock`)

## 1. Project Overview & Target Hardware
- **Vehicle & Head Unit:** Pre-2024 Hyundai Ioniq 5 (`NEEV.USA.S5W_M.V015.012.260507`) running Hyundai Mobis Wide Gen5W infotainment.
- **Operating System:** Android 4.4.2 KitKat (API level 19, 32-bit ARMv7, TCC897x SoC), screen resolution `1280x720` (or ultrawide split with `720px` height).
- **Execution Environment:** Rooted via **NEMESIS** (`hmg_nemesis`), which supports installing packages composed of an Android APK (`[app.0]`) and a persistent background root shell service (`[service.0]`).
- **Purpose:** Provide reliable Walk-Away Auto Door Locking when the driver parks, turns off ignition (`IGN OFF`), exits the vehicle, and all 4 cabin doors close (excluding the rear hatch/5th door), with a configurable delay (`5s`, `10s`, `15s`, `20s`), a final safety check that no cabin door is ajar before locking, and an optional Bluetooth disconnect requirement.

---

## 2. Architecture (`MainActivity.java` + `daemon.sh`)
Because Android 4.4.2 restricts normal non-system apps from calling privileged Binder services directly via `service call`, while `/system/bin/sh` root scripts cannot easily implement complex `android.os.Binder` callback interfaces (`IBCMListener`) or query the Android `BluetoothAdapter` profile connection state APIs, the project uses a hybrid two-part architecture communicating through `/data/nemesis/autolock/`:

### A. Android UI & Event Bridge (`com.gen5w.autolock/.MainActivity`)
- **Source:** `packages/gen5w.autolock/app_src/java/com/gen5w/autolock/MainActivity.java`
- **Role:**
  1. **User Interface:** Provides switches for enabling/disabling Auto-Lock, enabling/disabling the optional Bluetooth disconnect check, selecting the countdown timer (`5s`, `10s`, `15s`, `20s`), manually testing Lock/Unlock in Park, and viewing the live log.
  2. **Binder Callback Listener (`com.hkmc.bcm.IBCMListener`):**
     - Uses reflection (`android.os.ServiceManager.getService("com.hkmc.micom.bcm")`) to obtain the raw `IBinder` for the MICOM Body Control Module service (`com.hkmc.bcm.IBCMService`).
     - Registers a custom `android.os.Binder` implementation via `IBCMService.registerBCMListener(IBCMListener, IBinder)` (transaction code `1`).
     - Receives real-time callbacks pushed by MICOM whenever door open/lock states, ignition, ACC, or gear change.
     - Specifically decodes transaction `2` (`onDoorAllStateChanged(int, int, int, int, int, int, int, int, int, int)`) and writes `<anyCabinOpen> <allCabinClosed>` to `/data/nemesis/autolock/door_state`.
  3. **Bluetooth Connection Monitor:**
     - Polls `BluetoothAdapter.getDefaultAdapter().getProfileConnectionState(BluetoothProfile.HEADSET)` (`HFP`) and `BluetoothProfile.A2DP` (`A2DP`).
     - Writes `1` (connected) or `0` (disconnected) to `/data/nemesis/autolock/bt_connected` every second while active.

### B. Root Daemon (`autolock-daemon` -> `svc/daemon.sh`)
- **Source:** `packages/gen5w.autolock/svc/daemon.sh`
- **Role:**
  - Runs continuously as `uid=root` (`mode=restart` in `NEMESIS.data`).
  - Polls Ignition status every 1 second via `service call com.hkmc.micom.bcm 17` (`getIGNStatus`).
  - Tracks the state machine across `IGN ON -> IGN OFF` transitions:
    1. Waits for `IGN` to transition from `1` (`ON`) to `0` (`OFF`).
    2. Waits for at least one cabin door (`drvOpen`, `pasOpen`, `rlOpen`, `rrOpen`) to open (`any_open=1` in `/data/nemesis/autolock/door_state`).
    3. Waits for **all 4 cabin doors** to close (`all_closed=1`). Whenever any cabin door re-opens (e.g., passengers getting out or grabbing items from the back seat), the countdown timer resets so the delay is always measured from when the **last cabin door closes**.
    4. **Hatch / 5th Door Exclusion:** `trunkOpen` (the 9th integer in `onDoorAllStateChanged`) and `hoodState` (the 10th integer) are intentionally excluded from `anyCabinOpen` / `allCabinClosed` so the car still locks normally even if the power tailgate/hatch is open or closing slowly.
    5. Counts down the configured delay (`5`, `10`, `15`, or `20` seconds).
    6. **Optional Bluetooth Gate (`bt_req=1`):** If enabled, waits after the countdown finishes until `/data/nemesis/autolock/bt_connected` reads `0` (both HFP and A2DP disconnected). *Note:* Keep in mind that if the car is parked ~30 ft from the house, phone Bluetooth may stay connected inside the house unless out of range or head unit sleep cuts BT.
    7. **Final Safety Check & Lock Execution:** Re-reads `/data/nemesis/autolock/door_state` right before locking to verify `all_closed_now == 1`. If all 4 cabin doors are closed, executes:
       ```sh
       service call com.hkmc.micom.bcm 5 i32 1
       ```
       (`IBCMService.lockDoor(true)`).

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
