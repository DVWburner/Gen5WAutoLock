# Timed Auto-Lock (`gen5w.autolock`) — v1.5.0

> **AI-Generated Project Notice:** This project (including its Android application, root daemon script, icons, and packaging tools) is **AI-generated**.

**Timed Auto-Lock** is a walk-away automatic door lock package for **NEMESIS**-equipped Hyundai/Kia Wide **Gen5W** infotainment head units (Android 4.4.2 / API 19). Tested on a **'22 Hyundai Ioniq 5 Limited**.

---

## ⚠️ CRITICAL WARNING — KEY FOB LOCKOUT RISK

**THIS APPLICATION DOES NOT DETECT OR MONITOR WHETHER YOUR KEY FOB IS STILL INSIDE THE VEHICLE.**

- If Timed Auto-Lock is enabled, **it will lock all vehicle doors after the timer expires even if your key fob is still inside the car.**
- **You are solely responsible for ensuring you do not lock your key fob inside the vehicle.**
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
[ Ignition Off & Leave Car ] -> [ 5–20s After Last Door Closes (, ignoring hatchback) ] -> [ Phone Signal Drops (if detected) ] -> [ Lock Doors ]
```

1. **Ignition Off (`IGN ON -> IGN OFF`):** Detects when the vehicle is parked and turned off.
2. **Driver & Passengers Exit:** Waits for a door to open and then for all doors to close.
   - **Ignore Hatchback Being Open (Default ON):** When checked, the rear hatch / power tailgate is ignored by the timer. When unchecked, the timer waits for the rear hatch to close as well.
3. **Configurable Delay (`5–20s`):** Counts down your chosen delay (`5` to `20` seconds, default `10s`) after the last door closes.
4. **Automatic Phone Proximity Detection (No Toggle Required):**
   - **Wi-Fi Direct (Wireless CarPlay / Android Auto — Priority):** If a phone is connected over Wi-Fi Direct (`wlan0`), the daemon holds off locking after the timer until your phone drops out of range (~15–20 ft outside the car).
   - **Bluetooth (`HFP` / `A2DP` — Fallback):** If no Wi-Fi Direct phone is detected but a Bluetooth phone is connected, the daemon waits until Bluetooth disconnects.
   - **Timer Only:** If no phone is detected, the doors lock automatically as soon as the countdown finishes.
5. **Final Safety Check & Lock:** Verifies all monitored doors are still closed right before issuing `IBCMService.lockDoor(true)`.

---

## Interface & Controls

- **5-Second Safety Notice on Launch:** Requires acknowledging the key fob lockout warning before accessing controls.
- **Dynamic Logic Workflow Display:** Updates live on screen to reflect whether Master Auto-Lock is **ON** or **OFF**, your delay setting, hatchback preference, and currently detected phone type (`Wi-Fi Direct`, `Bluetooth`, or `Timer only`).
- **Phone Connection Status Box:** Live green/red indicator showing:
  - `Wi-Fi Direct Phone Connected` *(Green)*
  - `Bluetooth Phone Connected` *(Green)*
  - `No Phone Detected, Timer only` *(Red)*
- **Large Touch Delay Stepper (`-` / `+`):** Adjust lock delay from `5s` to `20s` with instant `✓ Saved` confirmation.
- **Master Auto-Lock Switch (Default ON) & Background Service:** Toggle auto-locking **ON** or **OFF** directly on the settings page without uninstalling; continues monitoring in the background after you tap **Exit**.

---

## Troubleshooting Log (Optional)

- By default, no logs are written to USB drives.
- To capture a troubleshooting log, create an empty file named **`autolock.log`** at the root of your USB drive before inserting it into the head unit; the daemon will append logs to that file only when it already exists.

---

## Installation (NEMESIS)

### Option 1: NEMESIS Package Store
Install `gen5w.autolock` (**Timed Auto-Lock**) via the [NEMESIS Package Store](https://xwtk.cloud/services/nemesis/).

### Option 2: USB Drive (`NEMESIS.data`)
1. Download **`gen5w.autolock-1.5.0-USB.zip`** from the [**v1.5.0 Release**](../../releases/tag/v1.5.0) (contains both `NEMESIS.data` and `gen5w.autolock-1.5.0.zip`), or copy both files from [`dist/autolock-installer/`](dist/autolock-installer/).
2. Extract both `NEMESIS.data` and `gen5w.autolock-1.5.0.zip` to the root of a FAT32/exFAT USB drive.
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
- `dist/autolock-installer/gen5w.autolock-1.5.0.zip`
- `dist/autolock-installer/NEMESIS.data`
