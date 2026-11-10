# DeviceGuard MDM

**Author:** Sakthivel J
**Package:** `com.devguard.mdm` · **Version:** 1.0 (versionCode 1) · **Min Android:** 10 (API 29) · **Target/Compile:** Android 16 (API 36)
**Language:** Kotlin · **UI:** Android Views built in code (no XML layouts) with Material 3 components

DeviceGuard MDM is a single-phone Mobile Device Management app. It runs as Android **Device Owner**, which lets it control the Play Store, block or remove apps, filter websites, lock down device settings, clean up bloatware and run the phone in kiosk mode. You manage everything on the phone, behind an admin PIN; no server is needed.

**Current deployment:** the phone (Android 16) · Device Owner since 2026-11-09 · see [DEVICE_INFO.md](DEVICE_INFO.md)

---

## Contents

0. [Quick reference: install, Device Owner, update, uninstall](#0-quick-reference-install-device-owner-update-uninstall) (incl. **I. Reset a forgotten admin PIN**)
1. [Features](#1-features)
2. [Requirements](#2-requirements)
3. [Set up the build environment (from scratch)](#3-set-up-the-build-environment-from-scratch)
4. [Build, install, update](#4-build-install-update)
5. [Connect the phone with ADB](#5-connect-the-phone-with-adb)
6. [Provision Device Owner (factory reset)](#6-provision-device-owner-factory-reset)
7. [Using the app](#7-using-the-app)
8. [Recovery and escape hatches](#8-recovery-and-escape-hatches)
9. [Developer guide: architecture](#9-developer-guide-architecture)
10. [Developer guide: how to change things](#10-developer-guide-how-to-change-things)
11. [Android and vivo gotchas](#11-android-and-vivo-gotchas)
12. [Troubleshooting](#12-troubleshooting)
13. [Known limitations](#13-known-limitations)
14. [Change log](#14-change-log)

---

## 0. Quick reference: install, Device Owner, update, uninstall

All commands are run in **PowerShell on the PC**, **from the project root `D:\TV_CLEANUP\MDM_App`**. ADB is included in the project (`MDM_App\platform-tools`), so all paths are relative and the whole folder can be copied to another PC or drive. First go to the root and set the shortcuts:

```powershell
cd D:\TV_CLEANUP\MDM_App           # project root (change if you moved the folder)
$adb = ".\platform-tools\adb.exe"
$apk = ".\app\build\outputs\apk\debug\app-debug.apk"
& $adb devices        # phone must show as "device". If it's listed twice, add  -s <serial>  after & $adb
```

> Not built yet? Run `cd D:\TV_CLEANUP\MDM_App; .\gradlew.bat assembleDebug` first (§4).

### A. Install the app (as a normal app, no Device Owner)
Good for previewing the UI on any phone. Controls stay disabled ("Setup needed").
```powershell
& $adb install -t $apk
& $adb shell am start -n com.devguard.mdm/.MainActivity
```
`-t` is required because the app is built as `testOnly` (that's the ADB escape hatch, §8).

### B. Make it Device Owner (full control)
**Before you start:** the phone must have **0 accounts**, **only user 0** and **no owner**. This normally means a factory reset where you **skip all sign-ins** in setup (full walkthrough in §6).

1. Check the phone is ready:
   ```powershell
   & $adb shell pm list users                                  # must list ONLY user 0
   & $adb shell dumpsys account | Select-String "Accounts:"    # must be 0
   & $adb shell dpm list-owners                                # must say "no owners"
   ```
2. Install (if not already) and set Device Owner:
   ```powershell
   & $adb install -t $apk
   & $adb shell dpm set-device-owner com.devguard.mdm/.AdminReceiver
   ```
   ✅ Expected: `Success: Device owner set to package com.devguard.mdm/.AdminReceiver`
3. Verify:
   ```powershell
   & $adb shell dpm list-owners
   # → User 0: admin=com.devguard.mdm/.AdminReceiver,DeviceOwner,Affiliated
   ```
4. One-time permission so DeviceGuard can set **and lock** Private DNS itself (needed on vivo, and when Private Space exists):
   ```powershell
   & $adb shell pm grant com.devguard.mdm android.permission.WRITE_SECURE_SETTINGS
   ```
   It survives app updates (`install -r`), but has to be run again after an uninstall or reset.
5. On the phone: open **DeviceGuard MDM** → create the **admin PIN**. The dashboard card turns blue: **"Device protected"**.
6. Now sign in to Google and other accounts and reinstall your apps, before turning on account/install restrictions.

If step 2 fails, see §12 (errors such as *already some accounts* or *several users*).

### C. Check status at any time
```powershell
& $adb shell dpm list-owners                          # is it Device Owner?
& $adb shell pm list packages com.devguard.mdm        # is it installed?
& $adb shell dumpsys package com.devguard.mdm | Select-String "versionName"
```

### D. Update to a new build (keeps Device Owner and all settings)
```powershell
cd D:\TV_CLEANUP\MDM_App; .\gradlew.bat assembleDebug
& $adb install -t -r $apk
& $adb shell dpm list-owners                          # should still show DeviceOwner
```
- Turn off **Block all installs** in the app first if it's on.
- The new APK must be signed with the **same key** (the PC's `%USERPROFILE%\.android\debug.keystore`). Back that file up (§10.9).

### E. Remove Device Owner (keep the app installed)
Pick one:
- **In the app (recommended):** Dashboard → **Danger zone → Remove Device Owner (release all controls)**. This undoes every restriction, unhides and unsuspends apps, clears website filters and kiosk, then gives up ownership.
- **Over ADB** (if you forgot the PIN or the app misbehaves; works because the app is `testOnly`):
  ```powershell
  & $adb shell dpm remove-active-admin com.devguard.mdm/.AdminReceiver
  ```
  ⚠️ The ADB route **doesn't undo policies** the app set (hidden apps, restrictions, Private DNS). Prefer the in-app route, or re-enable things by hand afterwards.

> After removal, making it Device Owner again needs a phone with no accounts or extra users, which usually means another factory reset.

### F. Uninstall the app completely
Android won't uninstall an active Device Owner, so **remove Device Owner first (E)**, then:
```powershell
& $adb uninstall com.devguard.mdm
```
or on the phone: Settings → Apps → DeviceGuard MDM → Uninstall.

If uninstall says `DELETE_FAILED_DEVICE_POLICY_MANAGER`, Device Owner is still active; do step E first.

### G. Move to a new or another phone
1. Back up that phone and factory reset it (skip all sign-ins).
2. Turn on Developer options → USB debugging (and Install via USB on vivo), then connect ADB (§5).
3. Do **B** (install, set Device Owner, set the PIN).
4. Configure the policies again (the settings live on each phone, not on the PC).

### H. Lost access?
| Situation | Way out |
|---|---|
| Forgot PIN, have the **recovery code** | Lock screen → **Forgot PIN? → Use my recovery code** → enter `XXXX-XXXX-XXXX-XXXX` → create a new PIN |
| Forgot PIN, PC available | `& $adb shell am broadcast -a com.devguard.mdm.RESET_PIN -n com.devguard.mdm/.PinResetReceiver`, then open the app and create a new PIN (all protections stay on) |
| Forgot PIN, nothing else works but ADB does | **E** via ADB, then **F** |
| Forgot PIN **and** "Block Developer options & USB debugging" is on | Use the **recovery code**. If that's lost too: recovery-mode factory reset (power off → Power + Volume Up → Wipe data). With "Block factory reset" on, only the Android recovery menu works. |

### I. Reset a forgotten admin PIN

There are two ways. Both **only remove the PIN**: every protection, Device Owner and all data stay as they are.

**Option 1: Recovery code (on the phone, no PC)**
1. Open DeviceGuard → lock screen → **Forgot PIN?**
2. Tap **Use my recovery code**.
3. Enter the code you saved (`XXXX-XXXX-XXXX-XXXX`; dashes and capitals don't matter) → **Reset PIN**.
4. The app shows **Create admin PIN**. Set a new PIN.
5. A **new recovery code** is shown. Save it, because the old one no longer works.

**Option 2: ADB reset (from the PC)**
Needs: USB debugging on, phone connected, and this PC already allowed ("Allow USB debugging?").
```powershell
$adb = ".\platform-tools\adb.exe"
& $adb devices                     # phone must show as "device"
& $adb shell am broadcast -a com.devguard.mdm.RESET_PIN -n com.devguard.mdm/.PinResetReceiver
```
Expected output ends with: `result=0, data="DeviceGuard PIN cleared. Open the app on the phone to create a new PIN."`
Then open DeviceGuard on the phone → **Create admin PIN** → set a new PIN → save the new recovery code.

| | Recovery code | ADB reset |
|---|---|---|
| Needs | The saved code | PC + cable + USB debugging |
| Works if "Block Developer options & USB debugging" is on | ✅ | ❌ |
| Works if the code is lost | ❌ | ✅ |
| Removes protections or data | No | No |

Security: the ADB reset only accepts the ADB shell (the receiver requires the `DUMP` permission), so other apps can't trigger it. Wrong PIN or recovery code 5 times → 30-second lockout.
| Stuck in kiosk | Long-press the kiosk title → PIN → Exit, or **E** via ADB |

---

## 1. Features

| Area | What you can do |
|---|---|
| **Dashboard** | Gradient status card (protected / setup needed) and rounded square tiles in 3 columns. **Manage:** Apps, Websites, Restrictions, Bloatware, Blocked apps, Play Store, Kiosk, About, Lock panel. **Quick actions:** Lock now, Reboot, Change PIN. **Danger zone** button. |
| **Play Store** | **Allow** or **Hide** (icon removed, can't open). *Suspend is not possible on this phone: the Play Store is Android's required package verifier, which Android never lets anyone pause. The app explains this and offers Hide.* |
| **Install control** | **Approved apps only**: new installs are paused until you approve them (apps installed while the admin panel is unlocked are approved automatically). **Block all installs**: nothing can be installed, including over ADB. |
| **Per-app control** | For any app: Hidden, Suspended, Block uninstall, Approved, Allowed in kiosk, plus silent **Uninstall** for user-installed apps. |
| **Blocked apps** | "Always block" list from a Play Store link, a package name, the installed apps, or quick-add tiles. A blocked app is **silently uninstalled the moment it's installed**, with an "… is not allowed" notification. |
| **Website blocking** | (1) **Family-safe filter, no VPN**: forced and locked Private DNS (Cloudflare Family / Security, AdGuard Family, or custom such as NextDNS). (2) **Chrome rules, no VPN**: your blocked-site list, adult filter, SafeSearch, YouTube restricted mode, no incognito, and hide other browsers. (3) **Optional VPN DNS filter**: your list in every app. |
| **Device restrictions** | Camera, screenshots, factory reset, safe mode, add users, accounts, app settings, developer options/ADB, installs/uninstalls, sideloading, Wi-Fi, mobile network, hotspot, Bluetooth, VPN, Private DNS, USB file transfer, SD card, date/time, location, calls, SMS. |
| **Bloatware** | Every built-in app rated **Safe / Caution / Keep / Unknown** in 2×2 tabs. **Per-app on/off switch**, restore, and copyable ADB "remove completely" command. PC script `tools\debloat.ps1` for bulk list/disable/remove/restore with an undo log. |
| **Kiosk mode** | Lock the phone to chosen apps (lock task mode plus a forced home screen showing app tiles). |
| **Device actions** | Lock now, reboot, change PIN, release Device Owner, factory reset. |
| **Self-protection** | The MDM can't be uninstalled while it is Device Owner. Admin PIN (salted, 10,000× SHA-256), 5-minute session, 30-second lockout after 5 wrong tries. |

---

## 2. Requirements

### To build
| Item | Required | Used here |
|---|---|---|
| OS | Windows 10/11 (also works on macOS/Linux with `./gradlew`) | Windows 11 Pro |
| JDK | **17 or newer** | JDK 21.0.12.1, `C:\Program Files\Java\jdk-21.0.12.1` |
| Android SDK | **Platform 36** + **Build-tools 36.0.0** | `D:\TV_CLEANUP\android-sdk` |
| Gradle | Wrapper included (no install needed) | 8.14.3 (wrapper) |
| Internet | First build downloads AGP, Kotlin and AndroidX (~300 MB, cached in `%USERPROFILE%\.gradle`) | — |
| IDE (optional) | Android Studio, or any editor (VS Code used) | — |

### Libraries (`app/build.gradle.kts`)
| Library | Version | Used for |
|---|---|---|
| Android Gradle Plugin | 8.11.1 | Build |
| Kotlin Android plugin | 2.1.21 | Language |
| `androidx.core:core-ktx` | 1.13.1 | Insets, text watchers |
| `androidx.appcompat:appcompat` | 1.7.0 | Base activity |
| `com.google.android.material:material` | 1.12.0 | Cards, switches, buttons, dialogs |
| `androidx.recyclerview:recyclerview` | 1.3.2 | App list |

### To run
| Item | Requirement |
|---|---|
| Phone | Android 10+ (tested on Android 16) |
| Device Owner | Phone must have **no accounts and no extra users** when provisioning, so a factory reset is usually needed (§6) |
| PC tools | ADB, included in the project: `platform-tools\adb.exe` (v37) |
| Phone settings | Developer options → USB debugging (and Wireless debugging for Wi-Fi ADB) |

---

## 3. Set up the build environment (from scratch)

Only needed on a new PC. On this PC everything is already installed.

```powershell
# 1. JDK 17+: install Temurin/Oracle JDK, then check
java -version

# 2. Android SDK command-line tools (no Android Studio needed)
mkdir D:\TV_CLEANUP\android-sdk\cmdline-tools
# Download https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip
# Unzip it so that this path exists: D:\TV_CLEANUP\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat

# 3. Accept licenses and install the platform and build tools
$sdk = "D:\TV_CLEANUP\android-sdk"
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=$sdk --licenses
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=$sdk "platforms;android-36" "build-tools;36.0.0"

# 4. Point the project at the SDK (already present in local.properties)
#    sdk.dir=D\:\\TV_CLEANUP\\android-sdk

# 5. ADB is already included in MDM_App\platform-tools. To update it: download "SDK Platform-Tools for Windows" and unzip it over MDM_App\platform-tools
```

> `local.properties` is machine-specific. On another PC, change `sdk.dir` to that PC's SDK path.

---

## 4. Build, install, update

```powershell
cd D:\TV_CLEANUP\MDM_App
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"   # only if JAVA_HOME isn't set
.\gradlew.bat assembleDebug
```
Output: `app\build\outputs\apk\debug\app-debug.apk` (about 12 MB)

**Install or update on the phone.** Device Owner and all settings are kept on update:
```powershell
$adb = ".\platform-tools\adb.exe"
& $adb devices                                   # if the phone is listed twice, add -s <serial> to each command
& $adb install -t -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.devguard.mdm/.MainActivity
```

- `-t` is needed because the manifest sets `android:testOnly="true"` (see §8). `-r` replaces the installed app.
- If **Block all installs** is on, turn it off in the app first; it also blocks ADB installs.
- Useful while developing:
  ```powershell
  & $adb logcat -s AndroidRuntime:E               # crashes
  & $adb logcat -d -b crash | Select-String devguard
  & $adb shell dpm list-owners                    # confirm still Device Owner
  & $adb shell run-as com.devguard.mdm cat shared_prefs/policy.xml   # app settings (debug build only)
  ```

**Clean build:** `.\gradlew.bat clean assembleDebug`

**Release build:** see §10.9.

---

## 5. Connect the phone with ADB

Current phone IP: **`<phone-ip>`** (it changed after the reset; check Settings → About → Status or the Wireless debugging screen).

**Wireless:**
1. Phone: Settings → Developer options → **Wireless debugging → ON**.
2. Tap **Pair device with pairing code**. Keep the dialog open and immediately run:
   ```powershell
   .\platform-tools\adb.exe pair <IP>:<PAIRING_PORT> <6-DIGIT-CODE>
   ```
   The code expires as soon as the dialog closes. Pairing is needed once per PC.
3. Connect with the port shown on the main Wireless debugging screen (it changes every time wireless debugging is toggled):
   ```powershell
   .\platform-tools\adb.exe connect <IP>:<PORT>
   ```
   `adb mdns services` lists the current pairing and connect ports.

**USB:** plug in the cable and accept **Allow USB debugging**.

> If `adb devices` shows the phone twice (e.g. `…cyUdef` and `…cyUdef (2)`), use `adb -s <serial> …`.

---

## 6. Provision Device Owner (factory reset)

Android only lets an app become **Device Owner** when the phone has **no accounts**, **no extra users/profiles** (Private Space, App Clone, Guest, work profile) and **no existing owner**. In practice that means a factory reset. Back up first; the checklist is in DEVICE_INFO.md §6.

1. **Factory reset:** Settings → System → Reset → Erase all data.
2. **Setup wizard:** Wi-Fi only. **Skip** Google sign-in, **skip** vivo account, **don't restore** anything.
3. **Developer options:** Settings → About phone → Software information → tap **Version** 7×.
4. Turn on **USB debugging**, **Wireless debugging** and **Install via USB** (vivo).
5. Connect ADB (§5), then check the phone is clean:
   ```powershell
   $adb = ".\platform-tools\adb.exe"
   & $adb shell pm list users                                  # only user 0
   & $adb shell dumpsys account | Select-String "Accounts:"    # 0
   & $adb shell dpm list-owners                                # no owners
   ```
6. Install and provision:
   ```powershell
   & $adb install -t .\app\build\outputs\apk\debug\app-debug.apk
   & $adb shell dpm set-device-owner com.devguard.mdm/.AdminReceiver
   & $adb shell dpm list-owners      # → admin=com.devguard.mdm/.AdminReceiver,DeviceOwner,Affiliated
   ```
7. Open the app and **create the admin PIN**.
8. **Then** add your Google account and reinstall your apps, before turning on any account or install restrictions.

---

## 7. Using the app

Open **DeviceGuard MDM** and enter the PIN. The session locks after 5 minutes of inactivity, or when you tap **Lock panel**.

### Admin PIN & recovery
- **Recovery code:** after setting the PIN, the app shows a one-time **recovery code** (`XXXX-XXXX-XXXX-XXXX`). Write it down and keep it off the phone. Only its hash is stored. A new code is made every time the PIN is set. If you don't have one yet, the dashboard shows a reminder card.
- **Forgot PIN?** (button on the lock screen):
  1. **Use my recovery code** → the PIN is cleared → create a new PIN (and you get a new recovery code).
  2. **Reset from the PC (ADB):** with USB debugging on, run `adb shell am broadcast -a com.devguard.mdm.RESET_PIN -n com.devguard.mdm/.PinResetReceiver`. Only the ADB shell can send this (the receiver is protected by the `DUMP` permission).
- Wrong PIN or recovery code 5 times → 30-second lockout.
- Dashboard → **PIN & recovery**: change PIN, create a new recovery code, PC reset help.
- Resetting the PIN never turns any protection off.

### Dashboard
- **Status card:** blue "Device protected" when Device Owner, orange "Setup needed" otherwise. Pills show web filtering, approval mode, active restriction count and Play Store state.
- **Manage tiles:** Apps · Websites · Restrictions · Bloatware · Blocked apps · Play Store · Kiosk · About · Lock panel
- **Quick actions:** Lock now · Reboot · PIN & recovery
- **Danger zone** (red button): Remove Device Owner (releases everything) · Factory reset (type `WIPE`)

### Apps
- **Play Store tiles:** Allow / Suspend / Hide, with the current one highlighted. On this phone Suspend isn't allowed (see §11), so tapping it offers **Hide** instead.
- **Approved apps only:** everything installed when you switch it on counts as approved. Later installs are paused, and you approve them by tapping the app and ticking **Approved**. Apps you install within 5 minutes of entering the PIN are approved automatically.
- **Block all installs:** blocks every install.
- **Tap an app** for checkboxes: Hidden, Suspended, Block uninstall, Approved, Allowed in kiosk. User apps also get **Uninstall** (silent). System apps get a link to **Bloatware**.
- **Show system apps** includes built-in apps.

### Blocked apps (stop specific apps being installed)
Android can't show "not allowed" inside the Play Store without Google's managed Google Play, so DeviceGuard **removes a blocked app the moment it's installed**.
1. Dashboard → **Blocked apps**.
2. Add an app:
   - **Play Store link:** app page → **⋮** → **Share** → **Copy link**, then paste it and tap **Block this app**.
   - **Package name**, e.g. `com.instagram.android`.
   - **Choose from installed apps**, or a **Quick add** tile (Instagram, Facebook, Snapchat, Free Fire, Free Fire MAX, BGMI, Roblox, TikTok, Netflix).
3. Already-installed apps are uninstalled right away (after a confirmation listing them).
4. Future installs are removed within seconds, with a notification "… is not allowed". The list is also checked at boot.
5. Tap a blocked app → **Allow** to unblock.

For an extra layer, use the Play Store's own **parental controls** (Play Store → profile → Settings → Family → Parental controls → PIN → age rating).

### Websites
1. **Family-safe filter, no VPN:** **Choose filter**:

   | Option | Private DNS host | Blocks |
   |---|---|---|
   | Cloudflare Family | `family.cloudflare-dns.com` | Adult content and malware |
   | Cloudflare Security | `security.cloudflare-dns.com` | Malware |
   | AdGuard Family | `family.adguard-dns.com` | Adult content, ads, trackers; SafeSearch |
   | Custom | e.g. `abc123.dns.nextdns.io` | Your NextDNS/other rules |

   The Private DNS setting is then locked.
2. **Your blocked sites:** add domains (subdomains included) or quick-add tiles (Social media, Video, Games). The list feeds Chrome rules and the VPN filter.
3. **Chrome rules, no VPN:** **Enforce in Chrome** plus options (adult filter, SafeSearch, YouTube restricted, no incognito). Restart Chrome and check `chrome://policy`.
   - **Allow only Chrome** (switch): hides every other browser now and **any browser installed later** (with a "… is not allowed" notification). Turning it off restores them. Chrome rules only work in Chrome, so other browsers must be blocked.
   - Chrome rules **can't reach** browsers inside **Private Space** or the built-in browsers of apps (Instagram, Google app…). To block *your own* site list there too, use a **custom Private DNS** such as NextDNS (free tier) in the Family-safe filter. Add your sites in the NextDNS dashboard and enter its DNS-over-TLS hostname (e.g. `abc123.dns.nextdns.io`) under Choose filter → Custom.
4. **VPN filter (optional):** blocks your list in every app. It can't run together with the Private DNS filter, and the app switches between them for you.

**Recommended:** Cloudflare Family + Chrome rules + hide other browsers + Approved apps only.

**Office network backup (Smart DNS).** Some networks, often office Wi-Fi, block the filter's secure DNS port **853**. With a strict Private DNS filter the internet then stops working. DeviceGuard fixes this automatically (Websites → **Office network backup**, on by default):

| Situation | What happens | Internet gap |
|---|---|---|
| Port 853 works (home, mobile data) | Normal Private DNS filter (whole phone, incl. Private Space) | none |
| New network blocks 853 | Detected on connect (TLS test to the filter, 2–4 s) → **backup filter**: the app's VPN sends lookups to the **same filter over HTTPS (DoH, port 443)**, Private DNS set to automatic | ~2–4 s, once |
| Same network again | Remembered → backup starts instantly, then re-checked in the background | ~0–2 s |
| Back on a normal network | Private DNS filter restored, VPN stopped | none |

- A notification "Backup web filter on" shows while backup mode is active.
- **Office domains (intranet):** add names like `corp.local` or `intranet.company.com`. In backup mode they're resolved by the office DNS (split DNS), everything else by the family filter.
- Your **blocked-site list also applies** in backup mode.
- The lock stays on: in backup mode Private DNS is held at "automatic" (so it can't bypass the VPN), and changes are reverted.
- **Limit:** in backup mode the filter covers the **main profile only, not Private Space** (Android VPNs are per user).
- Wi-Fi login pages (captive portals) are left alone until you've logged in.
- **Forget remembered networks** button: clears the list of networks known to block 853.
- Needs the one-time `WRITE_SECURE_SETTINGS` grant (§0 B step 4).

**Private DNS lock on vivo / with Private Space.** vivo's Settings app ignores Android's "Lock Private DNS" restriction, and Private Space blocks the normal Device Owner method. After the one-time grant (§0 B step 4), DeviceGuard writes Private DNS itself and **reverts any change instantly**, with a "Private DNS is locked" notification. The Websites screen shows 🔒 Locked. Without the grant you can still set it from the PC, which applies to the whole phone, including Private Space:
```powershell
$adb = ".\platform-tools\adb.exe"
& $adb shell settings put global private_dns_mode hostname
& $adb shell settings put global private_dns_specifier family.cloudflare-dns.com   # or family.adguard-dns.com / your NextDNS host
& $adb shell settings get global private_dns_specifier                              # check
```
Then in the app turn on **Restrictions → Lock Private DNS**, so the user can't change it. The Websites screen shows it as "Current: Cloudflare Family". To turn it off: `& $adb shell settings put global private_dns_mode opportunistic`.
Alternative inside the app: **VPN filter + Family-safe upstream** (covers the main profile, not Private Space).

### Restrictions
Switches grouped in cards. Recommended baseline: Block factory reset, Block safe mode, Block adding users, Block app settings changes, Lock VPN settings, Lock Private DNS.
⚠️ **Block Developer options & USB debugging** cuts off ADB; only the app can undo it.

### Bloatware
- **2×2 tabs:** 🟢 Safe · 🟠 Caution · 🔴 Keep · ⚪ Unknown, with app counts.
- **Each app has its own on/off switch** (ON = active, OFF = disabled by the MDM). Keep and Unknown apps ask for confirmation. There's no "disable all"; you choose app by app.
- **Tap the app's name** for details: Disable/Enable/Restore, plus copyable ADB commands for *remove completely* (`pm uninstall -k --user 0`), disable, enable and restore.
- Apps that vivo ships disabled (Meet, YouTube, i Music) show a greyed-out switch. Use the ADB enable command for those.

**PC script** (works even without Device Owner), run from `MDM_App\tools` (it uses `MDM_App\platform-tools\adb.exe` automatically):
```powershell
.\debloat.ps1 -Action list [-Category SAFE|CAUTION|KEEP]
.\debloat.ps1 -Action disable -Category SAFE          # asks you to type YES
.\debloat.ps1 -Action remove  -Package com.vivo.browser
.\debloat.ps1 -Action restore -Package com.vivo.browser
.\debloat.ps1 -Action restore -FromLog                # undo everything the script did
```
Bulk actions are allowed only for SAFE or CAUTION. Every change is logged in `tools\debloat-log.csv`.

### Kiosk mode
1. Apps → tap each app → tick **Allowed in kiosk**.
2. Dashboard → **Kiosk** tile → **Start**. The phone shows only those apps as tiles; Back is disabled and Home returns to the kiosk screen.
3. To exit, **long-press the "Apps" title** → PIN → **Exit kiosk mode** / **Open admin panel**.

---

## 8. Recovery and escape hatches

| Problem | Fix |
|---|---|
| Remove the MDM completely | App → **Danger zone → Remove Device Owner**, then uninstall normally. |
| Forgot the PIN | **Recovery code** (lock screen → Forgot PIN?) or **ADB reset** `adb shell am broadcast -a com.devguard.mdm.RESET_PIN -n com.devguard.mdm/.PinResetReceiver`. Both keep every protection. Step by step in §0 I. |
| App stuck / must remove it (ADB still on) | `adb shell dpm remove-active-admin com.devguard.mdm/.AdminReceiver` (works only because the app is `testOnly`), then `adb uninstall com.devguard.mdm`. |
| Stuck in kiosk | Long-press the title + PIN, or the ADB command above. |
| A bloatware app is needed again | Bloatware → switch ON / Restore, or `adb shell cmd package install-existing <pkg>` |
| Blocked app needed | Blocked apps → tap → Allow |
| ADB was blocked by the "Developer options" restriction | Turn it off in the app (Restrictions). Without the PIN, only a factory reset helps. |

> **Keep `testOnly="true"` while developing.** It is the ADB escape hatch. Remove it only for a final release (§10.9), knowing that the only way out afterwards is the in-app release or a factory reset.

---

## 9. Developer guide: architecture

### 9.1 Project layout
```
MDM_App/
├── README.md                  ← this file
├── DEVICE_INFO.md             ← phone snapshot: hardware, users, accounts, apps, reset impact
├── build.gradle.kts           ← root: plugin versions (AGP, Kotlin)
├── settings.gradle.kts        ← repositories, module list
├── gradle.properties          ← JVM args, AndroidX flags
├── local.properties           ← sdk.dir (machine-specific)
├── gradlew(.bat), gradle/     ← Gradle wrapper 8.14.3
├── platform-tools/            ← ADB (adb.exe v37 + DLLs), bundled so every command uses .\platform-tools\adb.exe
├── tools/
│   ├── debloat.ps1            ← PC bloatware tool (reads app/src/main/assets/bloat.csv)
│   └── debloat-log.csv        ← created by the script (undo log)
└── app/
    ├── build.gradle.kts       ← SDK levels, version, dependencies
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/bloat.csv   ← bloatware catalogue (shared by app + script)
        ├── res/
        │   ├── drawable/      ← ic_*.xml Material icons (white, tinted in code), launcher foreground
        │   ├── mipmap-anydpi-v26/ic_launcher.xml   ← adaptive icon
        │   ├── values/strings.xml                  ← app_name, author
        │   ├── values/themes.xml, values-night/    ← Material 3 DayNight theme, brand colour
        │   └── xml/device_admin.xml                ← device-admin policies
        └── java/com/devguard/mdm/
            ├── MainActivity.kt          PIN gate (setup/login) + tile dashboard + danger zone
            ├── AppsActivity.kt          Play Store tiles, install policy, RecyclerView app list, per-app dialog, uninstall
            ├── BlockListActivity.kt     Always-block list UI
            ├── WebActivity.kt           Private DNS, blocked sites, Chrome rules, VPN filter UI
            ├── RestrictionsActivity.kt  Restriction switches (from RESTRICTION_GROUPS)
            ├── BloatActivity.kt         Bloatware tabs + per-app switches
            ├── KioskActivity.kt         Kiosk home screen (lock task)
            ├── AboutActivity.kt         App info and author
            ├── Mdm.kt                   AdminReceiver, RESTRICTION_GROUPS, Mdm (DevicePolicyManager wrapper), releaseAll()
            ├── Features.kt              WebFilter, PrivateDns, SmartDns, ChromePolicy, Kiosk, Blocklist, Approval
            ├── Bloat.kt                 BloatCat, bloat.csv loader, app state, ADB command builders
            ├── Services.kt              DnsVpnService (DNS filter), GuardService (install watcher), BootReceiver
            ├── Prefs.kt                 SharedPreferences store, PIN + recovery-code hashing, Session (timeout/lockout)
            ├── PinResetReceiver.kt      ADB-only forgotten-PIN reset (protected by DUMP permission)
            └── Ui.kt                    UI kit: pages, cards, tiles, tabs, switches, dialogs, palette
```

### 9.2 How the pieces fit

```
                 ┌──────────── MainActivity (PIN gate → dashboard tiles) ────────────┐
                 │                                                                    │
   AppsActivity  WebActivity  RestrictionsActivity  BloatActivity  BlockListActivity  KioskActivity
        │             │                │                  │                │               │
        └─────────────┴────────┬───────┴──────────────────┴────────────────┴───────────────┘
                               ▼
     Features.kt (WebFilter · PrivateDns · ChromePolicy · Kiosk · Blocklist · Approval) · Bloat.kt
                               ▼
               Mdm.kt  →  android.app.admin.DevicePolicyManager (as Device Owner)
                               │
     Prefs.kt (state we must remember)          Services.kt
     - PIN hash, Session                        - DnsVpnService  : local DNS filter (always-on VPN)
     - blocked domains / apps                   - GuardService   : foreground service; PACKAGE_ADDED →
     - approval + kiosk lists, flags                               Blocklist.onInstalled / Approval.onInstalled
                                                - BootReceiver   : re-applies baseline, restarts watchers
```

- **DevicePolicyManager stores most policies itself** (restrictions, hidden/suspended apps, Private DNS, Chrome restrictions, lock task). Screens read the live state from `Mdm` and don't cache it.
- **Prefs** stores only what DPM doesn't: PIN, lists (domains, blocked apps, approved, kiosk) and feature flags.
- **GuardService** runs only while it's needed (`GuardService.needed()` = approval mode on, or the blocked list isn't empty). Call `GuardService.refresh(ctx)` after changing either.

### 9.3 Key components (where to look)

| Need | File → symbol |
|---|---|
| Any DPM call | `Mdm.kt` → `class Mdm` (`setHidden`, `setSuspended`, `setRestriction`, `isOwner`…) |
| Restriction switches | `Mdm.kt` → `RESTRICTION_GROUPS` |
| Undo everything | `Mdm.kt` → `releaseAll()` |
| Office-network backup | `Features.kt` → `SmartDns` (`check`, `enterFallback`, `exitFallback`, `bootstrap`, `dohUrl`); network watcher in `GuardService` (`registerDefaultNetworkCallback`); DoH and split DNS in `DnsVpnService` (`doh`, `forwardLocal`) |
| PIN recovery | `Prefs.kt` → `newRecoveryCode/checkRecovery/clearPin`, `Session.tryRecovery`; `MainActivity` → `forgotPin`, `showRecoveryCode`, `securityMenu`; `PinResetReceiver.kt` (ADB reset) |
| Private DNS filter | `Features.kt` → `PrivateDns` (`PROVIDERS`, `apply()`; this does a network check, so run it off the main thread) |
| Chrome policies | `Features.kt` → `ChromePolicy.apply()` (Bundle keys = Chrome enterprise policy names) |
| VPN DNS filter | `Services.kt` → `DnsVpnService` (parses IPv4/UDP DNS, NXDOMAIN for blocked names, forwards the rest to 1.1.1.1/8.8.8.8 or 1.1.1.3) |
| Blocked apps | `Features.kt` → `Blocklist` (`PRESETS`, `parse()`, `add()`, `onInstalled()`, `sweep()`) |
| Approved-apps mode | `Features.kt` → `Approval` |
| Kiosk | `Features.kt` → `Kiosk.start/stop`; manifest alias `.KioskHome` |
| Bloat list and logic | `assets/bloat.csv`, `Bloat.kt` |
| PIN and session | `Prefs.kt` → `Prefs.setPin/checkPin`, `Session` |
| Page and widget builders | `Ui.kt` |

### 9.4 UI kit (`Ui.kt`)
All screens are built in code with these helpers. They keep the look consistent and handle insets, scroll and theming.

| Helper | What it makes |
|---|---|
| `scrollPage(title, back, subtitle, keepScroll) { … }` | Full page: header, scroll view, system-bar padding; **keeps the scroll position** on re-render |
| `setPage(view)` | Content with system-bar/IME padding (re-applies insets on every render) |
| `pageHeader(activity, title, subtitle, back)` | 26sp title and optional rounded back button |
| `card(title) { … }` | Rounded surface card (22dp radius) |
| `section(text)` | Section heading |
| `tileGrid(tiles, columns = 3)` | Rounded **square** tiles (`SquareCard`), icon box, label, sub-label |
| `tabGrid(tabs, selected, columns = 2)` | Compact rounded tabs; the selected one gets a coloured border |
| `statusCard(ok, headline, detail, pills)` | Gradient hero card with pills |
| `switchRow(title, sub, checked, enabled) { sw, on -> Boolean }` | Title, subtitle and `MaterialSwitch`; returning `false` reverts the switch |
| `button(label, outlined)` / `materialButton()` | Material buttons |
| `confirm()`, `askPin()`, `pinField()`, `padded()` | Dialogs and inputs |
| `act { }` | Runs code and shows any exception as a toast instead of crashing |
| `Palette` | Brand colours: BLUE, GREEN, PURPLE, ORANGE, TEAL, PINK, INDIGO, SLATE, RED |
| `BaseActivity` | Sends you back to the PIN screen if the session expired; extends the session on touch |

**Pattern for a screen:** `onResume()` → `render()` → `scrollPage { … }`. After any change, call `render()` again; scroll position and insets are preserved automatically.

---

## 10. Developer guide: how to change things

### 10.1 Add a device restriction
Add a line to the right group in `Mdm.kt → RESTRICTION_GROUPS`:
```kotlin
Restriction(UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT, "Lock screen timeout", "Optional note"),
```
It appears automatically on the Restrictions screen, in the dashboard count, and in `releaseAll()`. Use `danger = true` to require a confirmation.

### 10.2 Add or recategorise bloatware
Edit `app/src/main/assets/bloat.csv` (`package,category,name,note`, where category is `SAFE`, `CAUTION` or `KEEP`). The note may contain commas; the first three fields may not. Both the app and `tools\debloat.ps1` use it. To list the phone's built-in packages: `adb shell pm list packages -s`.

### 10.3 Add a quick-add app to Blocked apps
`Features.kt → Blocklist.PRESETS`: `"com.example.app" to "Name",`

### 10.4 Add a website preset or Private DNS provider
- Site presets: `Features.kt → WebFilter.PRESET_SOCIAL / PRESET_VIDEO / PRESET_GAMES`. For a new preset, also add a tile in `WebActivity` (Quick add).
- DNS providers: `Features.kt → PrivateDns.PROVIDERS` (the host must support DNS-over-TLS on port 853).

### 10.5 Add a Chrome policy
`Features.kt → ChromePolicy.apply()`: add a `Bundle` entry named exactly like the Chrome enterprise policy (see chromeenterprise.google/policies). Add a switch in `WebActivity` with `chromeSwitch(...)` and a key constant in `Prefs`.

### 10.6 Add a new screen
1. Create `XyzActivity : BaseActivity()` with `onResume { if (!isFinishing) render() }` and `render() = scrollPage("Title", back = true) { card("…") { … } }`.
2. Register it in `AndroidManifest.xml`: `<activity android:name=".XyzActivity" android:exported="false" />`.
3. Add a `Tile(...)` in `MainActivity.dashboard()`, in the **Manage** grid. Keep tile counts a multiple of 3 so the grid stays even.

### 10.7 Add an icon
Create `res/drawable/ic_name.xml` as a 24dp vector with a white fill (copy any `ic_*.xml`; Material icon path data works). Tiles tint it automatically.

### 10.8 Change look and behaviour
| What | Where |
|---|---|
| App name / author | `res/values/strings.xml` (`app_name`, `author`) |
| Brand colour | `res/values/themes.xml` (`brand`, `colorPrimary`) and `Ui.kt → Palette` |
| Tile/card roundness | `Ui.kt`: `tileView` radius 24dp, `card` 22dp, `tabView` 18dp |
| Grid columns | `tileGrid(…, columns = 3)` |
| PIN timeout / lockout | `Prefs.kt → Session` (`TIMEOUT_MS`, `MAX_FAILS`, `LOCKOUT_MS`) |
| Launcher icon | `res/drawable/ic_launcher_fg.xml`, `res/values/themes.xml → icon_bg` |

### 10.9 Version bump and release build
1. In `app/build.gradle.kts`, increase `versionCode` (it must go up for every update) and `versionName`.
2. **Release signing (once):**
   ```powershell
   keytool -genkeypair -v -keystore D:\TV_CLEANUP\keys\deviceguard.jks -alias deviceguard -keyalg RSA -keysize 2048 -validity 10000
   ```
   Add a `signingConfigs { create("release") { … } }` block in `app/build.gradle.kts`, then run `.\gradlew.bat assembleRelease`.
   ⚠️ **Updates must be signed with the same key** as the installed app. The phone currently has the **debug-signed** build (`%USERPROFILE%\.android\debug.keystore`). Switching to a release key means uninstalling, which requires releasing Device Owner first and then a factory reset to provision again. **Back up `debug.keystore`**; losing it means you can't update the installed app.
3. `testOnly="true"` stays on for the ADB escape hatch (§8). Remove it only if you accept that recovery then needs the PIN or a factory reset.

### 10.10 Test checklist after a change
- [ ] `.\gradlew.bat assembleDebug` succeeds
- [ ] `adb install -t -r …` succeeds and `dpm list-owners` still shows DeviceOwner
- [ ] PIN screen → dashboard; go into each section and back (title must not overlap the status bar)
- [ ] Toggle a switch far down a list (the page must not jump to the top)
- [ ] `adb logcat -d -b crash | Select-String devguard` is empty
- [ ] Update this README (features, layout, change log)

---

## 11. Android and vivo gotchas

| Gotcha | Explanation / how the code handles it |
|---|---|
| **Play Store can't be suspended** | On Google phones `com.android.vending` is the *required package verifier*; Android rejects suspending it (`dumpsys package` → `Verifiers: Required: com.android.vending`). The app offers **Hide** instead. |
| **No "admin blocked" message in the Play Store** | Needs managed Google Play (EMM), which custom DPCs can't register for, so `Blocklist` uninstalls blocked apps right after install. |
| **Edge-to-edge (target SDK 36)** | Android draws behind the status bar. `setPage()` pads by the system-bar insets and **re-applies them on every re-render**; otherwise the title overlaps the status bar when coming back to a screen. |
| **Scroll jumping to the top** | Re-rendering builds a new ScrollView. `scrollPage()` copies the old `scrollY` and blocks auto-focus on text fields. The Apps RecyclerView updates only the changed row. |
| **Private DNS vs VPN filter** | A forced Private DNS host sends DNS-over-TLS straight to that host, bypassing the VPN, so the app keeps them mutually exclusive. |
| **`setGlobalPrivateDnsModeSpecifiedHost` blocks** | It tests the host over the network, so it's called on a background thread. |
| **Implicit broadcasts** | `PACKAGE_ADDED` can't be received by a manifest receiver, so `GuardService` (foreground, `specialUse`) registers it at runtime. |
| **"Unaffiliated users" error (Private DNS filter)** | Phone-wide policies such as Private DNS need **every user to be affiliated** with the Device Owner. Private Space can never be affiliated (Android doesn't allow an admin inside it), so the app can't set Private DNS while it exists. **Workarounds that keep Private Space:** (1) set Private DNS **from the PC** (see *Websites → Private Space on the phone* in §7), whole phone; (2) VPN filter + Family-safe upstream, main profile only; (3) Chrome rules. Check users with `adb shell pm list users`. |
| **vivo ignores "Lock Private DNS"** | The restriction is applied (`dumpsys device_policy` shows `disallow_config_private_dns = true`), but vivo's custom Private DNS screen doesn't check it. Fix: `WRITE_SECURE_SETTINGS` granted over ADB. `GuardService` watches `private_dns_mode`/`private_dns_specifier` with a `ContentObserver` and restores the chosen host (`PrivateDns.enforce`). |
| **"Lock Private DNS" also blocks DeviceGuard** | While `DISALLOW_CONFIG_PRIVATE_DNS` is on, Android's SettingsProvider **silently ignores** writes to `private_dns_*` from every app and from `adb shell settings put` (only the system Settings app is exempt, which is how vivo Settings can switch it off). `PrivateDns.write()` lifts the restriction for a moment, writes, reads it back to check, and re-applies it. To change Private DNS from the PC, first turn the lock off in the app. |
| **Networks that block port 853** | Strict Private DNS has no fallback, so the internet dies. `SmartDns` tests 853 with bootstrap IPs (no DNS needed), then switches to the VPN filter with a DoH upstream. DeviceGuard is excluded from its own VPN (`addDisallowedApplication`) so its DoH requests and network tests go straight out. |
| **Chrome rules only work in Chrome** | Other browsers ignore Chrome policies, so **Allow only Chrome** hides other browsers (`ChromePolicy.setChromeOnly`) and `GuardService` hides newly installed ones (`ChromePolicy.isBrowser` = handles any `http` link). |
| **Duplicate ADB device entries** | Wireless debugging can list the phone twice; use `adb -s <serial>`. |
| **vivo account / App Clone / Private Space** | These count as accounts/users and block `set-device-owner`. Skip them in setup. |
| **vivo "Install via USB"** | Needed for `adb install` on vivo. |
| **System apps can't be uninstalled** | `Blocklist` and Bloatware **hide** them instead; full removal for user 0 is ADB-only (`pm uninstall -k --user 0`). |

---

## 12. Troubleshooting

### `dpm set-device-owner` errors
| Error | Fix |
|---|---|
| `…already some accounts on the device` | Remove the account in Settings → Accounts, or reset and skip sign-in. |
| `…already several users on the device` | Remove Private Space, App Clone, Guest or the work profile, or reset. |
| `…device owner is already set` | Already done; check `dpm list-owners`. |
| `Unknown admin` | The APK isn't installed; run `adb install -t` first. |

### Other
| Symptom | Fix |
|---|---|
| `adb: more than one device/emulator` | `adb devices`, then `adb -s <serial> …` |
| `INSTALL_FAILED_TEST_ONLY` | Use `adb install -t`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Different signing key. Build with the same `debug.keystore` (§10.9). |
| Install blocked | Turn off **Block all installs** in the app. |
| Choose filter → "unaffiliated users" | Private Space / App Clone exists. Keep it and set Private DNS from the PC (§7 Websites), or use the VPN filter. |
| Blocked site still opens | Close and reopen the browser (DNS cache). Check Websites → filter "Current". |
| Chrome rules not applied | Force-stop Chrome and check `chrome://policy`. |
| Gradle can't find SDK | Fix `sdk.dir` in `local.properties`. |
| `JAVA_HOME` errors | `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"` |

---

## 13. Known limitations

- Website blocking is DNS-based: apps with their own DNS-over-HTTPS or hard-coded IPs can get around it.
- Blocked apps are removed *after* installing (Play Store still shows Install).
- Management happens on the phone only; there's no remote console yet.
- vivo battery management can delay the install watcher after a reboot. It catches up via the boot sweep and when the panel is opened.

---

## 14. Change log

| Date | Change |
|---|---|
| 2026-11-09 | v1.0: first build. Device Owner MDM with PIN, apps and Play Store control, approved-apps mode, VPN DNS filter, restrictions, kiosk. |
| 2026-11-09 | Website blocking without VPN: Private DNS family filters and Chrome enterprise policies; hide other browsers. |
| 2026-11-09 | Modern UI: gradient status card, rounded square tiles, cards, back headers. DEVICE_INFO.md added. |
| 2026-11-09 | Bloatware manager (Safe/Caution/Keep/Unknown, `bloat.csv`) and `tools\debloat.ps1`. |
| 2026-11-09 | Phone factory reset; app provisioned as Device Owner (IP now <phone-ip>). |
| 2026-11-09 | Bloatware: 2×2 tabs (labels were clipped), per-app switches instead of "disable all". |
| 2026-11-09 | Keep scroll position on re-render; Apps list updates only the changed row. |
| 2026-11-09 | Blocked apps (auto-uninstall on install); Play Store Suspend explains the verifier limit and offers Hide; dashboard regrouped. |
| 2026-11-09 | Fixed title overlapping the status bar when returning to a screen (insets re-applied). README rewritten with requirements and developer guide. |
| 2026-11-10 | Private DNS: clear message for the "unaffiliated users" error (caused by Private Space); new restrictions Block Private Space, Block App Clone, Block work profile. Workaround keeping Private Space: Private DNS set over ADB (app now detects and shows it), or VPN filter + family-safe upstream. |
| 2026-11-10 | Private DNS lock that really works on vivo: one-time `WRITE_SECURE_SETTINGS` grant; the app writes Private DNS itself (works with Private Space) and auto-reverts edits. New **Allow only Chrome** switch: hides other browsers now and on install. |
| 2026-11-10 | Fix: auto-revert couldn't write while the lock restriction was on (writes were silently dropped). The app now lifts the lock, writes, checks and re-locks. Checked on the phone: Private DNS `family.adguard-dns.com`, validated, lock re-applied. |
| 2026-11-10 | **Office network backup (Smart DNS):** network watcher, port-853 test on connect, remembered blocking networks, backup VPN filter over DoH, office domains (split DNS), status card. **PIN recovery:** one-time recovery code, "Forgot PIN?" on the lock screen, ADB reset receiver, dashboard reminder, PIN & recovery tile. |

© Sakthivel J
