# DeviceGuard MDM

**Author:** Sakthivel J
**Package:** `com.devguard.mdm` · **Version:** 2.0 (versionCode 2) · **Min Android:** 10 (API 29) · **Target/Compile:** Android 16 (API 36)
**Language:** Kotlin · **UI:** Android Views built in code (no XML layouts) with Material 3 components

DeviceGuard MDM is a single-phone Mobile Device Management app. It runs as Android **Device Owner**, which lets it control the Play Store, block or remove apps, filter websites, lock down device settings, clean up bloatware and run the phone in kiosk mode. You manage everything on the phone, behind an admin PIN; no server is needed.

**Known issues and planned fixes:** kept locally in `ISSUES.md` (not in git; reference the issue ID, e.g. `B-01`, in commit messages)

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
   For **Screen time** (limits and usage): `& $adb shell appops set com.devguard.mdm GET_USAGE_STATS allow` (or Settings → Usage access → DeviceGuard).
   Optional, so Background apps can show Android's Restricted state: `& $adb shell pm grant com.devguard.mdm android.permission.GET_APP_OPS_STATS`
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
- The new APK must be signed with the **same key**: `keys\deviceguard.keystore` via `keystore.properties` (§10.9). Keep both backed up.

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

Security: the ADB reset only accepts the ADB shell (the receiver requires the `DUMP` permission), so other apps can't trigger it. Wrong answers: after 5 wrong answers (PIN, recovery code or two-factor code, counted together) each further wrong answer waits 30 s, 1 min, 2 min … up to 1 hour; closing the app doesn't reset it.
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
| **Website blocking** | (1) **Family-safe filter**: forced and locked Private DNS (Cloudflare Family / Security, AdGuard Family, or your NextDNS profile, which can also block your own site list in every app). (2) **Chrome rules**: your blocked-site list, adult filter, SafeSearch, YouTube restricted mode, no incognito, and hide other browsers. |
| **Device restrictions** | Camera, screenshots, factory reset, safe mode, add users, accounts, app settings, developer options/ADB, installs/uninstalls, sideloading, Wi-Fi, mobile network, hotspot, Bluetooth, VPN, Private DNS, USB file transfer, SD card, date/time, location, calls, SMS. |
| **Bloatware** | Every built-in app rated **Safe / Caution / Keep / Unknown** in 2×2 tabs. **Per-app on/off switch**, restore, and copyable ADB "remove completely" command. PC script `tools\debloat.ps1` for bulk list/disable/remove/restore with an undo log. |
| **Background apps** | **Force stop** any app (Apps → app → Actions…, or Background apps → tap). **No background running** per app: listed apps are force-stopped ~10 s after the screen turns off. Optional Android **Restricted** mode over ADB (`tools\background.ps1`). |
| **Screen time** | **Daily app limits** (app paused until midnight), **schedules** (block apps and websites at set times, e.g. study or bedtime), **temporary unlock** (+15/30/60 min), **usage dashboard** (today + last 7 days). Needs one-time Usage access. |
| **Two-factor unlock** | PIN + 6-digit code from Zoho OneAuth or Google Authenticator (TOTP, offline). |
| **App permissions** | Per app and per permission group: let the user decide / always allow / always deny (location, camera, microphone, contacts, SMS…). |
| **Wi-Fi allowlist** | Only the listed Wi-Fi networks can be joined (Android 13+). |
| **System updates** | Phone default / install automatically / install at night / postpone 30 days. |
| **Profile backup** | Export every setting to a JSON file and import it again, e.g. after a reset. Secrets aren't exported. |
| **QR provisioning** | Set up another phone as Device Owner by scanning a QR code during setup, with no PC. |
| **NextDNS categories** | Use your NextDNS profile as the filter and switch categories (adult, gambling, dating, piracy, social, gaming, video) plus SafeSearch, YouTube restricted and block-bypass from the app. |
| **Lock screen & branding** | "Managed by **Sakthivel J**" instead of "your organization", plus your own lock screen line and the message shown when something is blocked. The managed notice itself can't be hidden. |
| **App updates** | Silent auto-update from GitHub Releases (`build-<versionCode>` tags). `tools\release.ps1` builds and publishes. |
| **Kiosk mode** | Lock the phone to chosen apps (lock task mode plus a forced home screen showing app tiles). |
| **Device actions** | Lock now, reboot, change PIN, release Device Owner, factory reset. |
| **Self-protection** | The MDM can't be uninstalled while it is Device Owner. Admin PIN of 6–12 digits (salted PBKDF2-SHA256, 100,000 rounds; no all-same or in-a-row PINs), 5-minute session, growing lockout: after 5 wrong answers (PIN, recovery code or two-factor code, counted together) each further wrong answer waits 30 s, 1 min, 2 min … up to 1 hour; closing the app doesn't reset it. |

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
| `com.google.zxing:core` | 3.5.3 | QR codes (authenticator setup, provisioning) |

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

**Unit tests** (pure logic: schedules, two-factor codes, blocked-app links, website names, PIN rules): `.\gradlew.bat testDebugUnitTest`. Report: `app\build\reports\tests\testDebugUnitTest\index.html`.

**R8:** release builds are shrunk and obfuscated (keep rules: `app\proguard-rules.pro`). To try the R8 build on the phone and keep the ADB escape hatch, build `.\gradlew.bat assembleDebug -PminifyDebug` and install it as above. `tools\release.ps1` also saves each release's R8 mapping as `dist\mapping-<version>.txt`: it turns that release's crash stack traces back into readable names, so back it up with the keystore.

**Maven Central is blocked on the development network** (`repo.maven.apache.org` answers 404). `settings.gradle.kts` lists Google's mirror of Maven Central first, so builds work there. Never run `--refresh-dependencies` with only Maven Central listed: Gradle then marks every cached library as missing and every build fails.

**Release build and publishing** (for QR provisioning and auto-update): raise `versionCode`/`versionName` in `app\build.gradle.kts`, then:
```powershell
cd D:\TV_CLEANUP\MDM_App\tools
.\release.ps1                                    # builds dist\DeviceGuard.apk (release, not testOnly)
$env:GITHUB_TOKEN = "github_pat_..."             # Contents: Read and write on the repo
.\release.ps1 -Upload -Notes "What's new"        # creates release build-<versionCode> with DeviceGuard.apk
```
Debug and release builds are signed with the **same key** (`keys\deviceguard.keystore`, described by `keystore.properties`; both git-ignored, §10.9), so either can update the other. Keep installing the **debug** build on the phone while developing: only it has the `testOnly` ADB escape hatch.

---

## 5. Connect the phone with ADB

The phone's IP address is in Settings → About → Status, or on the Wireless debugging screen (it can change after a reset).

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

Android only lets an app become **Device Owner** when the phone has **no accounts**, **no extra users/profiles** (Private Space, App Clone, Guest, work profile) and **no existing owner**. In practice that means a factory reset. Back up first: chats, authenticator apps (turn on sync or export the accounts), banking/UPI logins and MPINs, photos and files. Private Space is erased too.

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
- Wrong answers: after 5 wrong answers (PIN, recovery code or two-factor code, counted together) each further wrong answer waits 30 s, 1 min, 2 min … up to 1 hour; closing the app doesn't reset it.
- Dashboard → **Settings** → Admin PIN & recovery: change PIN, create a new recovery code, PC reset help.
- **Two-factor unlock** (Settings): after the PIN, also enter the 6-digit code from Zoho OneAuth or Google Authenticator. Turn on → scan the QR → enter one code. The recovery code and the PC reset also turn two-factor off, in case the authenticator phone is lost.
- Resetting the PIN never turns any protection off.

### Dashboard
- **Status card:** blue "Device protected" when Device Owner, orange "Setup needed" otherwise. Pills show web filtering, approval mode, active restriction count and Play Store state.
- **Manage tiles:** Apps · Websites · Screen time · Restrictions · Background · Blocked apps · Bloatware · Kiosk · Settings (Play Store state is shown in the status card; control it in Apps)
- **Quick actions:** Lock now · Reboot · Lock panel
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
1. **Family-safe filter:** **Choose filter**:

   | Option | Private DNS host | Blocks |
   |---|---|---|
   | Cloudflare Family | `family.cloudflare-dns.com` | Adult content and malware |
   | Cloudflare Security | `security.cloudflare-dns.com` | Malware |
   | AdGuard Family | `family.adguard-dns.com` | Adult content, ads, trackers; SafeSearch |
   | Custom | e.g. `abc123.dns.nextdns.io` | Your NextDNS/other rules |

   The Private DNS setting is then locked.
2. **Your blocked sites:** add domains (subdomains included) or quick-add tiles (Social media, Video, Games). The list feeds the Chrome rules, and **Send my blocked sites to NextDNS** copies it to your NextDNS denylist (every app).
3. **Chrome rules:** **Enforce in Chrome** plus options (adult filter, SafeSearch, YouTube restricted, no incognito). Restart Chrome and check `chrome://policy`.
   - **Allow only Chrome** (switch): hides every other browser now and **any browser installed later** (with a "… is not allowed" notification). Turning it off restores them. Chrome rules only work in Chrome, so other browsers must be blocked.
   - Chrome rules **can't reach** browsers inside **Private Space** or the built-in browsers of apps (Instagram, Google app…). To block *your own* site list there too, use a **custom Private DNS** such as NextDNS (free tier) in the Family-safe filter. Add your sites in the NextDNS dashboard and enter its DNS-over-TLS hostname (e.g. `abc123.dns.nextdns.io`) under Choose filter → Custom.

**Recommended:** Cloudflare Family + Chrome rules + hide other browsers + Approved apps only.

**Filter guard (self-control lock).** Websites → **Filter guard** (top card). When it's on, anything that **weakens website filtering** needs a challenge. Turning protections **on** is always instant.
1. **Typing puzzle:** retype a random 40-character code. Pasting is blocked, and any mistake gives a new code.
2. **Maths:** a 2-digit × 2-digit multiplication.
3. **Cooling-off wait:** none, 15 (default), 30 or 60 minutes. After solving, wait, then repeat the same action **within 10 minutes** to confirm, or the request expires. The wait uses the time since boot, so changing the clock doesn't skip it and a reboot restarts it. The wait belongs to **that one action**: a different action can't be confirmed with it (you're asked to cancel the waiting request first).

Guarded actions (Websites screen, plus **Settings → Import profile**, because a profile can turn filters off): filter → Off, a weaker filter (Cloudflare Security) or Custom; turning off any Chrome rule, Allow only Chrome or the incognito block; unblocking a site; shortening the wait; turning the guard off. The **Switch to Automatic when blocked** switch, all **NextDNS** switches and **Stop using NextDNS** are **not** guarded. Switching between the family filters (Cloudflare Family ↔ AdGuard Family) is free. Restrictions, Blocked apps and the Danger zone are **not** guarded. A PIN reset doesn't turn the guard off.

**Which layer blocks what.** The DNS filter (Private DNS) is the **main protection**. Chrome rules are an extra layer.

| Blocked | DNS filter ON | DNS filter OFF |
|---|---|---|
| Your site list in Chrome | ✅ | ✅ (Chrome rules) |
| Adult sites in Chrome | ✅ | ✅ (SafeSites, less complete) |
| Adult sites in other browsers and in-app browsers | ✅ | ❌ |
| Adult sites in incognito | ✅ (incognito can't bypass DNS) | Chrome incognito off only |
| Private Space | ✅ (normal networks) | ❌ |

When no DNS filter is active, the dashboard **Websites** tile turns red ("⚠ No filter") and the Websites screen shows a warning.

**Incognito in other browsers:** only Chrome accepts a policy to disable private browsing on Android, so **Disable incognito / private browsing** also turns on **Allow only Chrome** (other browsers hidden, new ones hidden on install).

**When the filter's DNS can't connect (Smart DNS).** Some networks, often office Wi-Fi, block the filter's secure DNS port **853**. With a strict Private DNS filter the internet then stops working. DeviceGuard switches **Private DNS to Automatic** on that network so the internet keeps working (Websites → **When the filter's DNS can't connect** → *Switch to Automatic when blocked*, on by default). **No VPN is used** (the old VPN backup made many apps fail to open, so it was removed on 2026-11-23).

| Situation | What happens | Internet gap |
|---|---|---|
| Port 853 works (home, mobile data) | Normal Private DNS filter (whole phone, incl. Private Space) | none |
| New network blocks 853 | Detected on connect (TLS test to the filter, 2–4 s) → Private DNS set to **Automatic** (no family filter on this network) | ~2–4 s, once |
| Same network again | Remembered → switches instantly, then re-checked in the background | ~0–2 s |
| Back on a normal network | Private DNS filter restored | none |
| Filter stops responding on the **same** network (service down, mobile data problem) | Android marks the connection *not validated* → DeviceGuard tests the filter at once (with one retry) → **Automatic** | a few seconds |
| On **mobile data** while on Automatic | Re-checked **every 3 minutes** and when the screen turns on; filter switched back as soon as it connects | none |

- On Wi-Fi, Automatic is re-checked when the network changes (not every few minutes), so an office network that blocks the filter isn't tested over and over.
- A notification "Private DNS on Automatic" shows while it's active; the Websites screen shows the filter as *paused* (orange).
- On such a network only the **Chrome rules** still block (your site list, SafeSites, SafeSearch). The family DNS filter does not.
- The lock stays on: while paused, Private DNS is held at Automatic and changes are reverted; elsewhere it's held at the filter.
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

### Background apps (force stop + no background running)
Dashboard → **Background**:
- **Tap an app** → **Force stop**. It's closed completely (same as Settings → Force stop), and its notifications and alarms stop until it's opened again. Also available in **Apps → tap app → Actions… → Force stop now**.
- **Switch next to an app = no background running.** About **10 s after the screen turns off**, DeviceGuard force-stops every switched-on app. Turning the screen back on within 10 s cancels it. The apps start normally when you open them. The **Stop when screen turns off** switch, **Last run**, and **Stop all listed apps now** are at the top.
- Status per app: **Stopped** / **Can run**, plus **Android Restricted** once the PC mode is applied (after the one-time grant below).
- Messaging and alarm apps (WhatsApp, Telegram, Zoho Cliq/Mail, Gmail, Clock, OneAuth, Spotify) show a warning: they won't get messages, alarms or playback while stopped.
- Can't be stopped: DeviceGuard, core system apps (Keep list), the home screen app, keyboard, SMS app and phone app.
- **Android Restricted mode (optional, PC):** Android's own "Restricted" battery mode blocks *all* background work and survives reboots, but only ADB can set it. Use **Copy apply command** in the app, or run from `MDM_App\tools`:
  ```powershell
  .\background.ps1 -Action status      # state of every app on the Background list
  .\background.ps1 -Action apply       # restrict them all
  .\background.ps1 -Action undo        # back to normal
  .\background.ps1 -Action apply -Package org.videolan.vlc
  ```
  To let the app show which apps are Restricted, run once: `& $adb shell pm grant com.devguard.mdm android.permission.GET_APP_OPS_STATS`

### Screen time
Dashboard → **Screen time**. Needs Usage access (one-time, see §0 B); schedules work without it.
- **Today:** time per app with a progress bar against its limit; ⛔ = paused.
- **Daily limits:** *Add a daily limit* → pick an app → 15 min … 4 h. When the limit is reached the app is **paused until midnight** (Android's "app paused" screen) and a notification says why.
- **Extra time:** tap an app → *Allow 15/30 more minutes / 1 more hour*. It runs even if a limit or schedule blocks it.
- **Schedules:** *Add a schedule* → name, days, start and end time (overnight works, e.g. 22:00–06:00), apps to block, websites to block (comma separated). Inside the time window the apps are paused and the sites are added to the Chrome rules. Switch a schedule on/off, or tap *Edit* to change or delete it.
- **Last 7 days:** total per day and the most-used apps of the week.
- **Automatic date & time stays on** while screen time has limits or schedules: limits count by the clock, so changing the date would reset them. If it's turned off, DeviceGuard turns it back on within seconds and shows a notification.
- Checked every minute while the screen is on, and on screen on. Apps paused by another feature (approved-apps mode) stay paused. Protected apps (launcher, keyboard, SMS, phone, core system) can't be limited.

### App permissions
Apps → tap an app → **Actions… → Permissions…** → each group the app asks for (Location, Camera, Microphone, Contacts, Phone, Call log, SMS, Photos & media, Calendar, Nearby devices, Body sensors, Notifications) → **Let the user decide / Always allow / Always deny**. "Always deny" also stops the user granting it in Settings. Android may refuse some changes for some apps (shown as a message).

### Wi-Fi allowlist & system updates
Restrictions → **Wi-Fi networks**: add the current network or one by name, then **Only allow these networks**. Other networks can't be joined. If the current Wi-Fi isn't in the list, you're asked before it disconnects. Seeing the current Wi-Fi name needs Location turned on.
Restrictions → **System updates**: phone default / install automatically / install at night (02:00–04:00) / postpone 30 days.

### Settings
Dashboard → **Settings**:
- **Admin PIN & recovery**, **Two-factor unlock** (above).
- **Lock screen & branding:** tap a text to change it (empty = hidden), or *Reset to defaults*. Defaults:
  | Text | Default | Where it shows |
  |---|---|---|
  | Organization name | Sakthivel J | Lock screen / quick settings / Settings: "This device is managed by Sakthivel J" |
  | Lock screen message | Managed by Sakthivel J | Extra line on the lock screen |
  | Message when something is blocked | Blocked by Sakthivel J | Pop-up when a blocked setting is tapped |
  | Device admin info | This phone is managed by Sakthivel J with DeviceGuard MDM. | Settings → Security → Device admin apps |
  Android always shows that a Device Owner manages the phone. Only these texts can change. They're applied at every start, after updates and after a profile import; lock screen changes show after the screen turns off and on.
- **Backup & restore profile:** *Export profile…* saves a `deviceguard-profile-YYYYMMDD.json` (restrictions, hidden/suspended/uninstall-blocked apps, filters, blocked apps and sites, screen time, background list, Chrome rules, Wi-Fi list, update policy). *Import profile…* applies one. PIN, recovery code, authenticator secret and tokens are never exported.
- **Set up another phone (QR code):** enter a public direct-download link to the **release** APK (and optionally Wi-Fi), then *Show QR*. On the new phone: welcome screen → tap 6 times → scan. Android downloads DeviceGuard, checks its signature and makes it Device Owner. Then set the PIN and import your profile. A private GitHub link won't work; use a public release or any direct-download host.
- **App updates:** repository (default `sakthivelgreen/DeviceGaurd`) and, for a private repo, a fine-grained GitHub token with *Contents: Read-only*. *Check for updates now*, or turn on *Check daily (auto-install)*. Updates install silently as Device Owner.

### NextDNS categories
Websites → **NextDNS categories** → *Connect NextDNS…* → Profile ID (nextdns.io → Setup, e.g. `abc123`) + API key (nextdns.io → Account → API). Then:
- **Use NextDNS as the filter**: sets Private DNS to `abc123.dns.nextdns.io` (locked, switches to Automatic on networks that block it, like the other filters).
- Switch categories: Adult content, Gambling, Dating, Piracy, Social networks, Online gaming, Video streaming. Plus **SafeSearch**, **YouTube restricted mode** and **Block bypass methods**. These switches change your NextDNS profile instantly, on or off, with no Filter guard.
- **Stop using NextDNS** (shown while NextDNS is the filter): pick **AdGuard Family** or **Cloudflare Family** (the one used last is listed first). The phone switches straight to it, with no guard, so it's never left unfiltered. The NextDNS key and profile stay saved; tap *Use NextDNS as the filter* to go back.
- **Send my blocked sites to NextDNS**: copies your blocked-site list to the NextDNS denylist, so it applies in every app and in Private Space.

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
├── ISSUES.md                  ← local issue tracker (git-ignored): bugs, security, cleanup, DRY, architecture
├── build.gradle.kts           ← root: plugin versions (AGP, Kotlin)
├── settings.gradle.kts        ← repositories, module list
├── gradle.properties          ← JVM args, AndroidX flags
├── local.properties           ← sdk.dir (machine-specific)
├── gradlew(.bat), gradle/     ← Gradle wrapper 8.14.3
├── .gitignore                 ← build outputs, dist/, local.properties, keys/ and keystore.properties are not committed
├── keys/deviceguard.keystore  ← signing key (NOT in git; back it up with keystore.properties)
├── keystore.properties        ← keystore path + password (NOT in git)
├── platform-tools/            ← ADB (adb.exe v37 + DLLs), bundled so every command uses .\platform-tools\adb.exe
├── tools/
│   ├── debloat.ps1            ← PC bloatware tool (reads app/src/main/assets/bloat.csv)
│   ├── background.ps1         ← PC tool: Android Restricted background mode for the Background apps list
│   ├── release.ps1            ← build release APK (+ publish GitHub release build-<versionCode>)
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
            ├── WebActivity.kt           Private DNS / NextDNS, blocked sites, Chrome rules UI
            ├── RestrictionsActivity.kt  Restriction switches (from RESTRICTION_GROUPS)
            ├── BloatActivity.kt         Bloatware tabs + per-app switches
            ├── KioskActivity.kt         Kiosk home screen (lock task)
            ├── AboutActivity.kt         App info and author
            ├── Mdm.kt                   AdminReceiver, RESTRICTION_GROUPS, Mdm (DevicePolicyManager wrapper), releaseAll()
            ├── WebFilter.kt             Site-list helpers, presets, onDomainsChanged(), old-VPN cleanup
            ├── PrivateDns.kt            Private DNS filter: providers, apply(), direct write, lock
            ├── SmartDns.kt              Falls back to Automatic on networks that block port 853; DoT test
            ├── ChromePolicy.kt          Chrome enterprise policies, Allow only Chrome
            ├── Kiosk.kt                 Lock task kiosk start/stop
            ├── Blocklist.kt             Always-blocked apps
            ├── Approval.kt              Approved-apps-only mode
            ├── Bloat.kt                 BloatCat, bloat.csv loader, app state, ADB command builders
            ├── Services.kt              GuardService (install watcher, network/settings observers), BootReceiver
            ├── Prefs.kt                 SharedPreferences store, PIN + recovery-code hashing, Session (timeout/lockout)
            ├── PinResetReceiver.kt      ADB-only forgotten-PIN reset (protected by DUMP permission)
            ├── Guard.kt                 Filter guard: puzzle, maths, cooling-off wait (`Guard.require`)
            ├── AppControl.kt            Force stop (hide/unhide), silent uninstall, no-background list, Restricted-mode ADB commands
            ├── Notify.kt                Notification channels + helper; InstallResultReceiver (uninstall/update failures)
            ├── Secrets.kt               Encrypts the NextDNS key, GitHub token and two-factor secret (Android Keystore, AES-256-GCM)
            ├── Holds.kt                 Who wants each app paused/hidden (manual, approval, screen time, Chrome-only, blocklist); applies the combined result
            ├── ScreenTime.kt            Limits, schedules, temporary unlock, usage queries, engine (evaluate)
            ├── ScreenTimeActivity.kt    Screen time screen + schedule editor
            ├── Totp.kt                  Two-factor TOTP (RFC 6238) + QR bitmap helper (zxing)
            ├── DeviceGuardApp.kt        Application: one-time setup at process start (notification channels)
            ├── Apps.kt                  Installed apps, cached names, background icon loading (one place for all screens)
            ├── AdminUi.kt               Shared dialogs: recovery code, PC reset, change PIN, TOTP prompt/setup
            ├── SettingsActivity.kt      PIN/2FA, profile export/import, provisioning QR, app updates
            ├── Device.kt                Wi-Fi allowlist, system update policy, per-app permissions, provisioning JSON
            ├── Branding.kt              Organization name, lock screen message, support messages (defaults: Sakthivel J)
            ├── Profile.kt               Policy profile export/import (JSON)
            ├── Updater.kt               GitHub Releases auto-update (silent PackageInstaller install)
            ├── NextDns.kt               NextDNS API: categories, SafeSearch/YouTube/bypass, denylist sync
            ├── BackgroundActivity.kt    Background apps screen
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
     WebFilter · PrivateDns · SmartDns · ChromePolicy · Kiosk · Blocklist · Approval (one file each) · Bloat.kt
                               ▼
               Mdm.kt  →  android.app.admin.DevicePolicyManager (as Device Owner)
                               │
     Prefs.kt (state we must remember)          Services.kt
     - PIN hash, Session                        - GuardService   : foreground service; PACKAGE_ADDED →
     - blocked domains / apps
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
| Auto-switch on blocking networks | `SmartDns.kt` → `SmartDns` (`check`, `enterFallback` = Private DNS → Automatic, `exitFallback`, `bootstrap`, `dotReachable`); network watcher in `GuardService` (`registerDefaultNetworkCallback`). |
| PIN recovery | `Prefs.kt` → `newRecoveryCode/checkRecovery/clearPin`, `Session.tryRecovery`; `MainActivity` → `forgotPin`, `showRecoveryCode`, `securityMenu`; `PinResetReceiver.kt` (ADB reset) |
| Private DNS filter | `PrivateDns.kt` → `PrivateDns` (`PROVIDERS`, `apply()`; this does a network check, so run it off the main thread) |
| Chrome policies | `ChromePolicy.kt` → `ChromePolicy.apply()` (Bundle keys = Chrome enterprise policy names) |
| Blocked apps | `Blocklist.kt` → `Blocklist` (`PRESETS`, `parse()`, `add()`, `onInstalled()`, `sweep()`) |
| Approved-apps mode | `Approval.kt` → `Approval` |
| Kiosk | `Kiosk.kt` → `Kiosk.start/stop`; manifest alias `.KioskHome` |
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
| `EditText.keepText(key)` | Marks an on-page text field so typed text, cursor and focus survive a page redraw |
| `inBackground(work) { result -> }` | Runs slow work (many system calls, files) off the main thread, then hands the result back on the main thread only if the screen is still open; errors become a toast |
| `Palette` | Brand colours: BLUE, GREEN, PURPLE, ORANGE, TEAL, PINK, INDIGO, SLATE, RED |
| `BaseActivity` | Sends you back to the PIN screen if the session expired; extends the session on touch |

**Pattern for a screen:** `onResume()` → `render()` → `scrollPage { … }`. After any change, call `render()` again; scroll position and insets are preserved automatically.

**Keep system calls off the main thread.** Every `Mdm` / `DevicePolicyManager` / `PackageManager` question is a call into Android's system process. In `render()`, read each value once (`val owner = m.isOwner`, `val rs = m.restrictions()`), never per row or per switch. Anything that loops over installed apps goes through `inBackground` (Apps list status, export, release).

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
`Blocklist.kt → Blocklist.PRESETS`: `"com.example.app" to "Name",`

### 10.4 Add a website preset or Private DNS provider
- Site presets: `WebFilter.kt → WebFilter.PRESET_SOCIAL / PRESET_VIDEO / PRESET_GAMES`. For a new preset, also add a tile in `WebActivity` (Quick add).
- DNS providers: `PrivateDns.kt → PrivateDns.PROVIDERS` (the host must support DNS-over-TLS on port 853).

### 10.5 Add a Chrome policy
`ChromePolicy.kt → ChromePolicy.apply()`: add a `Bundle` entry named exactly like the Chrome enterprise policy (see chromeenterprise.google/policies). Add a switch in `WebActivity` with `chromeSwitch(...)` and a key constant in `Prefs`.

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
| PIN timeout / lockout | `Prefs.kt → Session` (`TIMEOUT_MS`, `FREE_TRIES`, `BASE_LOCK_MS`, `MAX_LOCK_MS`); PIN length `Prefs.PIN_MIN` / `PIN_MAX` |
| Launcher icon | `res/drawable/ic_launcher_fg.xml`, `res/values/themes.xml → icon_bg` |

### 10.9 Version bump and release build
1. In `app/build.gradle.kts`, increase `versionCode` (it must go up for every update) and `versionName`.
2. **Signing key (already set up, 2026-11-23).** Every build (debug and release) is signed with ONE key:

   | File | Where | In git? |
   |---|---|---|
   | `deviceguard.keystore` (PKCS12, alias `androiddebugkey`) | `MDM_App\keys\` | ❌ git-ignored (`keys/`) |
   | `keystore.properties` (path + password) | `MDM_App\` root | ❌ git-ignored |

   Certificate SHA-256: `E8:92:FC:E3:C9:4D:1E:D0:2A:39:25:2A:E6:8D:EE:17:EF:2D:59:31:FE:38:22:53:4A:90:FE:4A:88:4C:DE:E2`. That's the key of the app installed on the phone, originally the PC's debug key, now with its own strong password. `app/build.gradle.kts` reads `keystore.properties`; without it, builds fall back to `%USERPROFILE%\.android\debug.keystore` (same key, password `android`).

   ⚠️ **Never generate a new key for this app.** Android only installs updates signed with the same key. A different key means uninstalling, releasing Device Owner, and setting it up again (no accounts on the phone, in practice a factory reset). **Keep both files backed up together** in at least two places off this PC.

   **Restore on a new PC:** copy `deviceguard.keystore` to `MDM_App\keys\` (`storeFile=keys/deviceguard.keystore` is relative to `MDM_App`; an absolute path also works), copy `keystore.properties` to the `MDM_App` root, build, then check:
   ```powershell
   & "D:\TV_CLEANUP\android-sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
   ```
   The SHA-256 must start with `e892fce3`. If an install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, you're using the wrong key: stop, don't uninstall the app on the phone.
3. `testOnly="true"` stays on for debug builds (ADB escape hatch, §8). Release builds have it off.

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
| **`setGlobalPrivateDnsModeSpecifiedHost` blocks** | It tests the host over the network, so it's called on a background thread. |
| **System calls are slow on the main thread** | Each DPM/PackageManager call crosses into the system process. The dashboard, Restrictions page and Apps rows read each value once per render; loops over all ~400 packages (Apps status, export, release) run in `inBackground` (P-01). |
| **Maven Central blocked on the development network** | `repo.maven.apache.org` answers 404 (Cloudflare, `Retry-After`). `settings.gradle.kts` lists Google's mirror (`maven-central.storage-download.googleapis.com/maven2`) before `mavenCentral()` for plugins and libraries. |
| **Implicit broadcasts** | `PACKAGE_ADDED` can't be received by a manifest receiver, so `GuardService` (foreground, `specialUse`) registers it at runtime. |
| **"Unaffiliated users" error (Private DNS filter)** | Phone-wide policies such as Private DNS need **every user to be affiliated** with the Device Owner. Private Space can never be affiliated (Android doesn't allow an admin inside it), so the app can't set Private DNS while it exists. **Workarounds that keep Private Space:** (1) set Private DNS **from the PC** (see *Websites → Private Space on the phone* in §7), whole phone; (2) Chrome rules (Chrome only). Check users with `adb shell pm list users`. |
| **vivo ignores "Lock Private DNS"** | The restriction is applied (`dumpsys device_policy` shows `disallow_config_private_dns = true`), but vivo's custom Private DNS screen doesn't check it. Fix: `WRITE_SECURE_SETTINGS` granted over ADB. `GuardService` watches `private_dns_mode`/`private_dns_specifier` with a `ContentObserver` and restores the chosen host (`PrivateDns.enforce`). |
| **"Lock Private DNS" also blocks DeviceGuard** | While `DISALLOW_CONFIG_PRIVATE_DNS` is on, Android's SettingsProvider **silently ignores** writes to `private_dns_*` from every app and from `adb shell settings put` (only the system Settings app is exempt, which is how vivo Settings can switch it off). `PrivateDns.write()` lifts the restriction for a moment, writes, reads it back to check, and re-applies it. To change Private DNS from the PC, first turn the lock off in the app. |
| **Secrets are encrypted** | The NextDNS API key, GitHub token and two-factor secret are stored as `enc1:…` (AES-256-GCM, key in the Android Keystore, never exportable). Read/write them only through `Secrets.get` / `Secrets.put`. Wiping the app's data deletes the Keystore key, so these must be entered again. Profile export never includes them. |
| **One paused/hidden flag, many features** | Android has a single suspended flag and a single hidden flag per app, but manual Apps controls, Approved apps only, Screen time, Allow only Chrome and Blocked apps all use them. `Holds.kt` keeps one list per feature; an app stays paused/hidden while any list has it. Always change pause/hide through `Holds.set(...)`, never `Mdm.setSuspended/setHidden` directly (force stop's brief hide/unhide is the one exception). |
| **Switching away from NextDNS** | Android checks a new Private DNS host by looking its name up through the *current* DNS. NextDNS *Block bypass methods* answers 127.0.0.1 for other DNS services, so the check fails. `PrivateDns.reachable` tests built-in filters by their bootstrap IPs (TLS with the hostname) and `apply` writes the setting directly when Android's check is wrong. |
| **Networks that block port 853** | Strict Private DNS has no fallback, so the internet dies. `SmartDns` tests 853 with bootstrap IPs (no DNS needed) and switches Private DNS to Automatic on that network. The earlier VPN/DoH backup was removed because many apps failed to open through the VPN. |
| **No Device Owner API for force stop or background restriction** | Force stop = `setApplicationHidden(true)` then `false` (hiding kills the app). `GuardService` ignores the resulting "package added" broadcast (`AppControl.isIgnored`). Android's Restricted mode (`RUN_ANY_IN_BACKGROUND` appop + `restricted` standby bucket) is ADB-only, so the app builds the commands. Reading the state needs the `GET_APP_OPS_STATS` grant. Check on each launcher that hide/unhide keeps the home-screen icon in place. |
| **testOnly per build type** | The manifest uses `android:testOnly="${testOnly}"`: `true` for debug (ADB escape hatch), `false` for release (needed for QR provisioning and auto-update, because Android refuses to install testOnly APKs there). |
| **Usage access is ADB/Settings only** | Device Owner can't grant itself usage access: `appops set com.devguard.mdm GET_USAGE_STATS allow` (one-time). |
| **Private GitHub repo** | The auto-updater needs a read-only token. QR provisioning needs a public APK link (the setup wizard can't send a token). |
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
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Different signing key. Restore `deviceguard.keystore` + `keystore.properties` from your backup (§10.9). Don't uninstall the app. |
| Install blocked | Turn off **Block all installs** in the app. |
| Choose filter → "unaffiliated users" | Private Space / App Clone exists. Keep it and set Private DNS from the PC (§7 Websites). |
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
| 2026-11-09 | Modern UI: gradient status card, rounded square tiles, cards, back headers. |
| 2026-11-09 | Bloatware manager (Safe/Caution/Keep/Unknown, `bloat.csv`) and `tools\debloat.ps1`. |
| 2026-11-09 | Phone factory reset; app provisioned as Device Owner. |
| 2026-11-09 | Bloatware: 2×2 tabs (labels were clipped), per-app switches instead of "disable all". |
| 2026-11-09 | Keep scroll position on re-render; Apps list updates only the changed row. |
| 2026-11-09 | Blocked apps (auto-uninstall on install); Play Store Suspend explains the verifier limit and offers Hide; dashboard regrouped. |
| 2026-11-09 | Fixed title overlapping the status bar when returning to a screen (insets re-applied). README rewritten with requirements and developer guide. |
| 2026-11-10 | Private DNS: clear message for the "unaffiliated users" error (caused by Private Space); new restrictions Block Private Space, Block App Clone, Block work profile. Workaround keeping Private Space: Private DNS set over ADB (app now detects and shows it), or VPN filter + family-safe upstream. |
| 2026-11-10 | Private DNS lock that really works on vivo: one-time `WRITE_SECURE_SETTINGS` grant; the app writes Private DNS itself (works with Private Space) and auto-reverts edits. New **Allow only Chrome** switch: hides other browsers now and on install. |
| 2026-11-10 | Fix: auto-revert couldn't write while the lock restriction was on (writes were silently dropped). The app now lifts the lock, writes, checks and re-locks. Checked on the phone: Private DNS `family.adguard-dns.com`, validated, lock re-applied. |
| 2026-11-10 | **Office network backup (Smart DNS):** network watcher, port-853 test on connect, remembered blocking networks, backup VPN filter over DoH, office domains (split DNS), status card. **PIN recovery:** one-time recovery code, "Forgot PIN?" on the lock screen, ADB reset receiver, dashboard reminder, PIN & recovery tile. |
| 2026-11-13 | Fix: switching to the VPN filter cleared the Private DNS filter before the VPN started, so a cancelled or stopped VPN left **no filter at all**. Now the Private DNS filter is only replaced after the VPN really starts. "No website filter is active" warning (dashboard tile turns red). Disable incognito also turns on Allow only Chrome (incognito can't be disabled in other browsers). |
| 2026-11-13 | **Filter guard:** typing puzzle (no paste) + maths + optional cooling-off wait before weakening any website filter (Websites screen only). Code in `Guard.kt`. |
| 2026-11-13 | **Background apps:** force stop any app; per-app "no background running" (stopped 10 s after screen off); Android Restricted mode commands + `tools\background.ps1`; Apps dialog gets "Actions…" (Force stop / Uninstall / Bloatware) and NO-BG/STOPPED tags; dashboard Play Store tile replaced by Background. |
| 2026-11-13 | **v2.0:** Screen time (daily limits, schedules for apps and sites, temporary unlock, 7-day usage); two-factor unlock (TOTP); per-app permission control; Wi-Fi allowlist; system update policy; profile export/import; QR provisioning; NextDNS categories; GitHub Releases auto-update + `tools\release.ps1`; release build (not testOnly, same signing key); `.gitignore` for build outputs. Dashboard: Screen time + Settings tiles. |
| 2026-11-23 | **No VPN backup:** on networks that block the filter's DNS (port 853), Private DNS now switches to **Automatic** instead of starting the VPN (many apps failed to open through it); the filter returns on other networks. Office-domain (split DNS) settings removed from the UI. Filter guard removed from the VPN filter switches. |
| 2026-11-23 | **NextDNS:** filter guard removed from the NextDNS switches; new **Stop using NextDNS** button switches back to AdGuard Family or Cloudflare Family (last used first). |
| 2026-11-23 | Fix: leaving NextDNS failed with "didn't respond as a DNS-over-TLS server". NextDNS *Block bypass methods* blocks the names of other DNS services, so Android's pre-check failed. Built-in filters are now tested by their fixed IPs and written directly. |
| 2026-11-23 | **Auto-fallback on any failure:** if the filter's DNS stops responding on the current network (detected when Android marks the connection not validated), Private DNS switches to Automatic within seconds. On mobile data it's re-checked every 3 minutes and on screen on, and the filter returns as soon as it connects. |
| 2026-11-23 | **Lock screen & branding** (Settings): organization name, lock screen message and support messages, default "Sakthivel J" (`Branding.kt`, applied from `ensureBaseline`). |
| 2026-11-23 | **Signing key:** the app's key moved to `keys\deviceguard.keystore` (in the project, git-ignored) with a strong password (same key, fingerprint `E8:92:FC:E3…`). `keystore.properties` (git-ignored) points to it; debug and release builds both use it. README §10.9 rewritten (the old advice to generate a new key would have broken updates).
| 2026-11-24 | **Fixes from ISSUES.md:** B-01 VPN filter no longer blocks IPv6 (`allowFamily(AF_INET6)`); B-03 the filter guard's cooling-off confirms only the action that started it; S-02 importing a profile goes through the filter guard.
| 2026-11-24 | **B-02 fixed (Holds):** features no longer undo each other's pauses/hides. Each feature has its own list; an app is released only when no feature wants it (e.g. a schedule ending no longer un-pauses an app you paused by hand). Apps/Bloatware tell you who still holds an app. One-time migration recorded existing hides (39 on this phone) as manual. Profile export/import now saves only your own pauses/hides.
| 2026-11-24 | **Group 2 fixes:** B-05 automatic date & time kept on while screen time is in use (policy + direct write + instant revert); S-04 PIN 6–12 digits, PBKDF2 hashes (old hashes upgrade on next unlock), stored growing lockout shared by PIN, recovery and two-factor codes, session on the boot clock; B-06 uninstall/update failures now show a notification (`InstallResultReceiver`); B-08 locked read-modify-write for saved lists and screen-time data; D-01 one notification helper (`Notify.kt`); D-04 one unlock flow and credential reset; R-01/R-02 dead office-DNS/DoH code and unused helpers removed.
| 2026-11-25 | **S-03:** NextDNS API key, GitHub token and two-factor secret are encrypted with an Android Keystore key (`Secrets.kt`); existing plain-text values were encrypted automatically on update. Two-factor check now fails closed if its secret can't be read (recovery code / PC reset still work).
| 2026-11-25 | **P-01:** system calls moved off the main thread. Dashboard ~70 → ~4 calls per open (baseline writes in the background); Restrictions page reads all restrictions once (~100 → ~3 per redraw); Apps rows make no system calls while scrolling (status read in the background); export and Danger zone → Release run in the background, Release behind a dialog that can't be dismissed, with a `releasing` guard so nothing re-hides apps meanwhile. New `inBackground` helper in `Ui.kt`. |
| 2026-11-25 | **P-02, D-02, D-03, B-07:** Bloatware and Background apps load their lists in the background (Bloatware opened in 1.1 s before); new `Apps.kt` for installed apps, cached names and background icons, used by every screen; no raw `Thread {}` left (all use `inBackground`); typed text in Websites → Block this site and Blocked apps → Add an app survives page redraws. |
| 2026-11-25 | **B-09, D-05, B-06:** two-factor codes work only once; the daily update check only counts after GitHub answers (failures retry hourly); a downloaded update is checked (same app, newer version matching the tag, same signing key) before installing; screen time counts an app that was already open at midnight; one DNS-over-TLS test for Private DNS and Smart DNS; ADB commands use the build's app ID; Branding and Wi-Fi allowlist show when Android refuses a change. |
| 2026-11-25 | **A-02:** `Features.kt` (666 lines, 7 features) split into `WebFilter.kt`, `PrivateDns.kt`, `SmartDns.kt`, `ChromePolicy.kt`, `Kiosk.kt`, `Blocklist.kt`, `Approval.kt`; code unchanged. |
| 2026-11-25 | **D-06, A-03:** all preference keys are constants in `Prefs`, and the list of keys never exported (`Prefs.NOT_EXPORTED`) lives next to them; export also skips any encrypted value, so a future secret can't leak. New `DeviceGuardApp` creates notification channels once; screen time reads only new usage events each minute (full re-read every 10 min). |
| 2026-11-25 | **A-04:** 13 unit tests for pure logic (`app\src\test`, `gradlew testDebugUnitTest`); R8 shrinking and obfuscation for release builds (`proguard-rules.pro`), and `-PminifyDebug` to try R8 on the phone. Builds use Google's mirror of Maven Central, which is blocked on the development network. |
| 2026-11-25 | Phone-specific details removed from the project: `DEVICE_INFO.md` (model, serial, addresses, accounts, app list) deleted and git-ignored; README no longer names the phone model or its IP. |
| 2026-11-25 | **R-03: VPN filter removed** (it made many apps fail to open). Your site list is blocked in every app through NextDNS (Send my blocked sites to NextDNS) and in Chrome through the Chrome rules. On update, DeviceGuard clears itself as always-on VPN if an older version set it, and deletes the old VPN settings. About 250 lines and the VPN consent flow are gone. |
| 2026-11-25 | `ISSUES.md` removed from git and git-ignored; it stays on the development PC as the local issue tracker. |

© Sakthivel J
