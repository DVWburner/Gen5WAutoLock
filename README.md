# Timed Auto-Lock (`gen5w.autolock`) — v1.0.0

> **AI-Generated Project Notice:** This project (including its Android application, root daemon script, icons, and packaging tools) is **AI-generated**.

**Timed Auto-Lock** is a walk-away automatic door lock package for **NEMESIS**-equipped Hyundai/Kia Wide **Gen5W** infotainment head units (Android 4.4.2 / API 19). Tested on a **'22 Hyundai Ioniq 5 Limited**.

---

## ⚠️ CRITICAL WARNING — KEY FOB LOCKOUT RISK

**THIS APPLICATION DOES NOT DETECT OR MONITOR WHETHER YOUR KEY FOB IS STILL INSIDE THE VEHICLE.**

- If Timed Auto-Lock is enabled, **it will lock all vehicle doors after the timer expires even if your key fob is still inside the car.**
- **You are solely responsible for ensuring you do not lock yourself, passengers, pets, or your keys inside the vehicle.**
- Always carry your key fob with you when exiting the vehicle, and **always keep an alternate method to unlock your car available** at all times:
  - Connected phone app (e.g., Hyundai Bluelink / Kia Connect)
  - Physical mechanical key blade
  - Secondary / spare key fob

---

## Disclaimer — No Warranty or Liability

THIS SOFTWARE IS PROVIDED **"AS IS"**, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT.

IN NO EVENT SHALL THE AUTHOR(S) OR CONTRIBUTORS BE LIABLE FOR ANY CLAIM, DAMAGES, VEHICLE LOCKOUTS, HARDWARE/SOFTWARE MALFUNCTIONS, OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT, OR OTHERWISE, ARISING FROM, OUT OF, OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE. **USE ENTIRELY AT YOUR OWN RISK.**

---

## How It Works

```text
[ Ignition Off ]  --->  [ Driver Exits Car ]  --->  [ Doors Lock 5–20s After Last Door Closes ]
```

1. **Ignition Off (`IGN ON -> IGN OFF`):** The background daemon detects when the vehicle is parked and powered off.
2. **Driver / Occupants Exit:** Waits for at least one of the 4 cabin doors to open and then for **all 4 cabin doors to close**. If any cabin door is re-opened while getting items out, the timer resets until the last cabin door closes.
   - **Rear Hatch Excluded:** The rear hatch / power tailgate (`5th door`) and front hood are intentionally excluded from this timer so the cabin doors still lock normally while the tailgate is open or closing.
3. **Configurable Countdown (`5–20s`):** Counts down your selected delay (`5` to `20` seconds; default `10s`).
4. **Optional Phone Bluetooth Disconnect Gate:** If **"Require Bluetooth connection to phone to drop before locking"** is enabled, the daemon waits until your phone disconnects from the head unit's Bluetooth (`HFP` and `A2DP`) before locking.
5. **Final Door Check & Lock:** Performs a final check that all 4 cabin doors remain closed, then issues the BCM door lock command (`IBCMService.lockDoor(true)`).

---

## Features

- **5-Second Safety Acknowledgement on Launch:** Requires confirming that you understand the app does not check for key fobs inside the car before accessing settings.
- **Master On / Off Toggle:** Enable or disable walk-away auto-locking at any time with instant `✓ Saved` confirmation.
- **Adjustable Lock Delay (`5s – 20s`):** High-contrast touch slider and `-` / `+` stepper buttons.
- **Optional Bluetooth Disconnect Requirement:** Wait for phone Bluetooth connection to drop before locking.
- **Persistent Background Bridge (`AutoLockBridgeService` + `autolock-daemon`):** Continues monitoring door states and Bluetooth even after you tap **Exit** to close the app UI.

---

## Installation (NEMESIS)

### Option 1: NEMESIS Package Store
Install `gen5w.autolock` (**Timed Auto-Lock**) via the [NEMESIS Package Store](https://xwtk.cloud/services/nemesis/).

### Option 2: USB Drive (`NEMESIS.data`)
1. Download `gen5w.autolock-1.0.0.zip` and `NEMESIS.data` from the [**v1.0.0 Release**](../../releases/tag/v1.0.0) (or copy them from [`dist/autolock-installer/`](dist/autolock-installer/)).
2. Place both `NEMESIS.data` and `gen5w.autolock-1.0.0.zip` at the root of a FAT32/exFAT USB drive.
3. Insert the USB drive into your NEMESIS-equipped Gen5W head unit to install.

---

## Building from Source

Requires Python 3, JDK (`javac`, `keytool`), and Android SDK Build-Tools / Platform (`API 36` `android.jar` + `aapt2`, `d8`, `zipalign`, `apksigner` targeting `--min-api 19` with v1 JAR signing for Android 4.4.2 KitKat):

```powershell
python scripts/build_apk.py
python scripts/build_package.py
```

Outputs:
- `packages/gen5w.autolock/app/Gen5WAutoLock.apk`
- `dist/autolock-installer/gen5w.autolock-1.0.0.zip`
- `dist/autolock-installer/NEMESIS.data`
