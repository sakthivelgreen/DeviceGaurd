# DeviceGuard MDM: Issue tracker

Known flaws, cleanups and improvements from the code review on 2026-11-23. Each item has an ID (use it in commit messages, e.g. `Fix B-02: …`), a priority, a status and the files involved.

**Status:** `[ ]` open · `[~]` in progress · `[x]` done · `[-]` won't fix (say why)
**Priority:** **P1** fix soon (data loss, lock-out, broken feature) · **P2** should fix · **P3** nice to have

Paths are relative to `app/src/main/java/com/devguard/mdm/` unless they start with `app/` or the project root.

---

## Summary

| ID | Pri | Status | Title |
|---|---|---|---|
| [S-01](#s-01-release-signing-key-not-backed-up) | P1 | [x] | Signing key: keystore set up, backed up, install verified |
| [B-01](#b-01-vpn-filter-blocks-all-ipv6) | P1 | [~] | VPN filter blocks all IPv6 (likely cause of apps not opening) |
| [B-02](#b-02-suspend--hide-have-no-owner) | P1 | [x] | Suspend / hide flags have no owner; features undo each other |
| [B-03](#b-03-filter-guard-confirmation-not-tied-to-the-action) | P1 | [x] | Filter guard confirmation isn't tied to the action |
| [S-02](#s-02-filter-guard-bypass-paths) | P1 | [x] | Filter guard bypass paths (profile import, Danger zone, NextDNS) |
| [B-04](#b-04-adb-pin-reset-never-tested) | P1 | [x] | ADB PIN reset never tested end-to-end |
| [S-03](#s-03-secrets-stored-in-plain-text) | P2 | [x] | API key, GitHub token and TOTP secret stored in plain text |
| [S-04](#s-04-weak-brute-force-protection) | P2 | [x] | Weak brute-force protection (4-digit PIN, in-memory lockout) |
| [B-05](#b-05-clock-changes-defeat-time-rules) | P2 | [x] | Changing the clock defeats screen time and session timeout |
| [P-01](#p-01-heavy-binder-work-on-the-main-thread) | P2 | [x] | Heavy system calls on the main thread (dashboard, export, release, Apps list) |
| [P-02](#p-02-bloatware-and-background-screens-build-on-the-main-thread) | P2 | [x] | Bloatware (1.1 s) and Background apps (0.6 s) screens build on the main thread |
| [B-06](#b-06-failures-are-hidden) | P2 | [x] | Failures are hidden; uninstall/update results go nowhere |
| [B-07](#b-07-typed-text-lost-on-page-refresh) | P2 | [x] | Typed text lost when a page re-renders |
| [B-08](#b-08-concurrent-preference-writes) | P2 | [x] | Concurrent read-modify-write of preference sets can lose updates |
| [B-09](#b-09-smaller-bugs) | P3 | [ ] | Smaller bugs (usage before midnight, plaintext VPN DNS, update check timer) |
| [R-01](#r-01-dead-code-office-split-dns-and-doh) | P2 | [x] | Remove dead code: office split DNS + DoH |
| [R-02](#r-02-other-unused-code) | P3 | [x] | Remove other unused functions and wrappers |
| [R-03](#r-03-decide-on-the-vpn-filter) | P2 | [ ] | Decide: fix or remove the VPN filter |
| [R-04](#r-04-location-permission-self-grant) | P3 | [-] | Location permission self-grant (kept on request) |
| [D-01](#d-01-notifications) | P2 | [x] | DRY: notification building (7 copies) |
| [D-02](#d-02-installed-apps-and-labels) | P2 | [x] | DRY: installed-app list + label lookups (10 files) |
| [D-03](#d-03-background-threads) | P2 | [x] | DRY: `Thread { runOnUiThread { } }` (28 copies) |
| [D-04](#d-04-unlock-and-credential-logic) | P2 | [x] | DRY: PIN/2FA unlock, credential reset, PIN rules, hashing, lockout |
| [D-05](#d-05-other-duplication) | P3 | [ ] | DRY: DoT test, uninstall, ADB strings, guard helper, `Mdm(c)` |
| [D-06](#d-06-two-preference-styles) | P3 | [ ] | DRY: two preference styles; export key lists can drift |
| [A-01](#a-01-one-reconciler-for-app-state) | P2 | [x] | Architecture: one reconciler for suspended/hidden apps |
| [A-02](#a-02-split-featureskt) | P3 | [ ] | Architecture: split Features.kt (734 lines, 8 features) |
| [A-03](#a-03-application-class-and-workmanager) | P3 | [ ] | Architecture: Application class + WorkManager for periodic jobs |
| [A-04](#a-04-tests-and-build) | P3 | [ ] | Unit tests for pure logic; enable R8 for release |
| [A-05](#a-05-strings-and-ui) | P3 | [ ] | UI: strings.xml, partial re-render instead of full page rebuild |

---

## Security

### S-01 Release signing key not backed up
**P1 · [x] done 2026-11-24** · `app/build.gradle.kts`, `keys/`, `keystore.properties`

Builds were signed with the PC's auto-generated debug keystore, stored in one place only. Losing it means the installed Device Owner app can never be updated (factory reset needed).

- [x] Key moved to `keys/deviceguard.keystore` with a strong password; `keystore.properties` points to it; both git-ignored (2026-11-23)
- [x] Debug and release builds both use it; fingerprint unchanged `E8:92:FC:E3…`
- [x] **Uploaded `keys/deviceguard.keystore` + `keystore.properties` to a private cloud location** (2026-11-24). A second offline copy is still recommended.
- [x] Install-over test on the phone with the new signing config: `adb install -r` succeeded, Device Owner kept (2026-11-24)

### S-02 Filter guard bypass paths
**P1 · [x] fixed 2026-11-24 (import); rest kept by decision** · `Profile.kt:49-59`, `MainActivity.kt` (Danger zone), `WebActivity.kt` (NextDNS)

The guard only protects the Websites screen. Ways around it:
- **Profile import** writes any preference, including `guardOn=false` and `privateDnsHost`, with no guard.
- **Danger zone → Remove Device Owner** releases everything with only the PIN.
- **Use NextDNS → turn categories off** needs no puzzle (by request on 2026-11-23).
- The debug build allows `adb shell run-as` to edit preferences (expected for a dev build).

- [x] **Profile import** now goes through the filter guard (`SettingsActivity` import launcher).
- [-] **Danger zone:** stays unguarded. The guard applies only to filters (user decision, 2026-11-13).
- [-] **NextDNS switches:** stay unguarded (user request, 2026-11-23).

### S-03 Secrets stored in plain text
**P2 · [x] fixed 2026-11-25 (`Secrets.kt`)** · `Prefs.kt`, `NextDns.kt`, `Updater.kt`, `Totp.kt`

The NextDNS API key, GitHub token and TOTP secret are plain SharedPreferences values. The debug build is debuggable, so `run-as` reads them. - [x] `Secrets.kt`: AES-256-GCM with a non-exportable Android Keystore key; stored as `enc1:` + Base64(IV + ciphertext)
- [x] All reads/writes go through `Secrets.get/put` (NextDns, Updater, Totp, Settings, Websites); old plain-text values are encrypted on update (`Mdm.ensureBaseline` → `Secrets.migrate`)
- [x] Verified on the phone: `nextdnsKey` and `totpSecret` changed from plain text to `enc1:…` after the update (2026-11-25)
- [x] `Totp.verify` fails closed if the secret can't be decrypted
- [x] Checked on the phone (2026-11-25): Websites opened 4 times with no errors; two-factor was turned off in Settings, which needs a valid code, so the encrypted secret decrypts
- Limit: with the **debug** build, someone with ADB could still run code as the app and use the Keystore key. Only the release build (not debuggable) closes that.

### S-04 Weak brute-force protection
**P2 · [x] fixed 2026-11-24** · `Prefs.kt:180-218`, `MainActivity.kt:59`

- [x] New PINs: 6–12 digits, no all-same or in-a-row digits (`Prefs.pinProblem`). An existing shorter PIN keeps working.
- [x] PBKDF2-SHA256, 100,000 rounds, for PIN and recovery code; old hashes are verified once and upgraded (`pinAlgo` / `recAlgo`).
- [x] Lockout stored in prefs, shared by PIN, recovery code and two-factor code: 5 free tries, then 30 s doubling up to 1 h. A reboot clears the wait but not the count.
- [x] `Session` uses `SystemClock.elapsedRealtime()`.
- [x] Verified on the phone after unlock (2026-11-24): `pinAlgo = pbkdf2-sha256:100000`, lockout counters 0. The recovery code is still in the old format until it's used or a new one is created (Settings → Create a new recovery code).

---

## Performance

### P-01 Heavy binder work on the main thread
**P2 · [x] fixed 2026-11-25** · `Mdm.kt`, `Ui.kt` (`inBackground`), `MainActivity.kt`, `AppsActivity.kt`, `RestrictionsActivity.kt`, `SettingsActivity.kt`, `Profile.kt`, `Holds.kt`, `Services.kt`

Every DevicePolicyManager / PackageManager question is a call into Android's system process, made on the thread that draws the screen. Measured before the fix (2026-11-25, normal use): 27 slow UI-thread frames, 99th percentile 150 ms. Calls per action (400 packages on the phone):

| Place | Before | After |
|---|---|---|
| Dashboard, each open/return | ~70 on the main thread, 6 of them writes | ~4 reads; writes (`ensureBaseline`, `GuardService.refresh`) in the background |
| Restrictions page, each redraw / switch tap | ~100 (2 per check × 2 checks per switch) | 1 restrictions read + camera/screenshot |
| Apps list | 7 per row each time a row appears | 0 while scrolling; status read in the background on load / return, 4 calls for the one app you change |
| Export profile | ~850 + file write on the main thread | ~400, in the background |
| Danger zone → Release | ~2,400 + undoing every hold, on the main thread (ANR after 5 s; "Close app" stops it halfway) | ~1,200, in the background behind a dialog that can't be dismissed |

- [x] `Mdm` reads (`isHidden`, `isSuspended`, `isUninstallBlocked`) no longer ask `isOwner` first (Android refuses them when we aren't owner; `runCatching` → false). Half the calls everywhere.
- [x] `Mdm.restrictions()` reads all restrictions in one call; `activeRestrictionCount()`; dashboard, Restrictions page and export use it.
- [x] `inBackground(work) { result -> }` in `Ui.kt`: shared thread pool, result only delivered if the screen is still open, errors as a toast. [D-03](#d-03-background-threads) converts the remaining `Thread { }` copies to it.
- [x] Release: `Mdm.releasing` flag. While set, `Holds.set(on = true)` refuses, the package-added receiver ignores unhides, and `ensureBaseline` doesn't re-protect, so nothing re-hides an app or re-blocks DeviceGuard's uninstall while release runs. On failure the flag is cleared (still Device Owner) and the dialog says to tap Release again.
- [x] Built, installed (2026-11-25): still Device Owner, no crash.
- [x] Checked on the phone (2026-11-25, dashboard, Apps with system apps, Websites, Restrictions, export): janky frames 3.25% → 1.20%, 90th percentile 26 → 17 ms, 99th 150 → 97 ms, slow UI-thread frames 2.3% → 0.7% of frames. Screen open times: Apps 0.10–0.15 s, Restrictions 0.22–0.27 s, dashboard 0.27 s. Export: 22 settings, no secrets, 39 manual hides, 2 restrictions (matches `dumpsys device_policy`).
- The one remaining long frame (1.1 s) was the Bloatware screen opening, which P-01 didn't cover: see [P-02](#p-02-bloatware-and-background-screens-build-on-the-main-thread).
- Release is NOT tested on the phone on purpose: it removes Device Owner, which needs a factory reset to get back. Reviewed in code only.

### P-02 Bloatware and Background screens build on the main thread
**P2 · [x] fixed 2026-11-25** · `BloatActivity.kt`, `BackgroundActivity.kt`, `Bloat.kt`

Found while checking P-01 (2026-11-25): opening **Bloatware** took 1.12 s (one 1.1 s frame, 53 frames skipped) and **Background apps** 0.61 s.

- `BloatActivity.onResume` lists ~355 system apps, classifies them, and builds every row at once in a ScrollView: label, hidden state and icon for each, all on the main thread.
- `BackgroundActivity.render` reads installed apps, labels, stopped / OS-restricted state and icons on the main thread, and re-runs after each change.

Fix: read the list and each app's state with `inBackground`, then build the rows (same pattern as the Apps list); load icons lazily or in the background. Overlaps [D-02](#d-02-installed-apps-and-labels).

- [x] Both screens show the last list at once and refresh it in the background; names come from `Apps` (cached), icons load in the background (`Apps.loadIcon`, 2 threads).
- [x] Bloatware sorting no longer reads names and hidden state inside the sort comparator (that was a system call per comparison).
- [ ] Check on the phone: open Bloatware and Background apps, read the open times.

---

## Bugs

### B-01 VPN filter blocks all IPv6
**P1 · [~] code fixed 2026-11-24, phone test pending** · `Services.kt:84` (`DnsVpnService.startVpn`)

The VPN Builder never calls `allowFamily(OsConstants.AF_INET6)`, so Android adds `::/0 unreachable` for every app. This route was seen on the phone's VPN routing table. On IPv6-first networks (Airtel/Jio) this is the likely reason many apps failed to open with the VPN on. DNS over TCP (truncated answers) to the tunnel is also dropped.

- [x] `allowFamily(AF_INET6)` added in `DnsVpnService.startVpn` (2026-11-24)
- [ ] Test on the phone: turn on the VPN filter, check `dumpsys connectivity` no longer shows `::/0 unreachable`, and open the apps that failed before
- [ ] (P3) TCP DNS to the tunnel is still dropped; only matters for very large DNS answers

### B-02 Suspend / hide have no owner
**P1 · [x] fixed 2026-11-24 (Holds.kt)** · `ScreenTime.kt:179-183`, `Features.kt` (Approval, Blocklist, ChromePolicy), `AppsActivity.kt`, `AppControl.kt`

Manual "Suspended", Approval mode, Screen time, Blocklist, Allow-only-Chrome and Force stop all flip the same Android flags. Example: suspend an app by hand, add it to a schedule; when the schedule ends, Screen time un-suspends it. See [A-01](#a-01-one-reconciler-for-app-state) for the fix.

- [x] `Holds.kt`: one list per source; all pause/hide changes go through `Holds.set` (Apps, Play Store, Bloatware, Approval, Screen time, Allow only Chrome, Blocked apps, hide-other-browsers, profile import, release all)
- [x] One-time migration on the phone: 39 hidden apps recorded as manual, no app state changed (2026-11-24)
- [x] Apps / Bloatware say "Still hidden/paused by: …" when another feature holds the app
- [ ] Manual test: pause an app in Apps, put it in a short screen-time schedule, check it stays paused after the schedule ends

### B-03 Filter guard confirmation not tied to the action
**P1 · [x] fixed 2026-11-24** · `Guard.kt:34-72`

`GUARD_WAIT_START` is one global value. Solve the puzzle for a small action ("unblock example.com"), wait, then trigger a bigger one ("turn off the filter guard"): the confirm dialog accepts it. Fixed: `Prefs.GUARD_WAIT_WHAT` stores the action with the start time. A different action shows "Another request is waiting" and can only cancel it, not use it.

### B-04 ADB PIN reset never tested
**P1 · [x] tested 2026-11-24: works on Android 16** · `PinResetReceiver.kt`, manifest (`android.permission.DUMP`)

The ADB reset is the last way back in, and it has never been run on the phone (Android 16). Test it once:
```powershell
.\platform-tools\adb.exe shell am broadcast -a com.devguard.mdm.RESET_PIN -n com.devguard.mdm/.PinResetReceiver
```
Expect the result text "DeviceGuard PIN cleared…", then set a new PIN.

Result 2026-11-24: broadcast returned `result=0` with the expected text; PIN hash and TOTP secret were removed; Device Owner, Private DNS and all policies stayed. The recovery code hash is kept until a new PIN is created (which makes a new code).

### B-05 Clock changes defeat time rules
**P2 · [x] fixed 2026-11-24** (verified on the phone: `auto_time` set to 0 over ADB was switched back to 1 within 4 s, notification shown) · `ScreenTime.kt`, `Prefs.kt` (`Session`)

Daily limits, extra time and day boundaries use wall-clock time; moving the date forward resets limits. Fixed: `Device.enforceAutoTime` (policy `setAutoTimeEnabled` / `setAutoTimeZoneEnabled`, plus a direct `Settings.Global` write for vivo) runs every minute in the screen-time engine and instantly from a settings observer in GuardService, only while screen time has limits or schedules.

### B-06 Failures are hidden
**P2 · [x] fixed 2026-11-24** (result receiver; other ignored results remain, see A-05/next review) · 142 `runCatching` blocks; `Features.kt:641`, `AppsActivity.kt:243`, `Updater.kt:78`

- Uninstall and update results go to `UNINSTALL_RESULT` / `UPDATE_RESULT` broadcasts that no receiver handles, so failures are never shown.
- Many policy calls ignore their result, and the UI reports success anyway.

- [x] `InstallResultReceiver` (Notify.kt) gets the PackageInstaller status for uninstalls (Apps, Blocked apps) and app updates; failures post a notification. Mutable, explicit PendingIntent.
- [ ] Policy calls whose result is still ignored (many `runCatching`): review screen by screen.

### B-07 Typed text lost on page refresh
**P2 · [x] fixed 2026-11-25** · `Ui.kt` (`scrollPage`), all `BaseActivity` screens

Every switch rebuilds the whole page, so half-typed input (e.g. a site in Websites → Block this site) is cleared. Fix: keep input state across renders, or re-render only the changed card. See [A-05](#a-05-strings-and-ui).

Done: `EditText.keepText(key)` marks a field; `scrollPage` copies its text, cursor and focus into the rebuilt page. Used by Websites → Block this site and Blocked apps → Add an app (the only text fields on pages; the rest are in dialogs). PIN fields are cleared on purpose.

### B-08 Concurrent preference writes
**P2 · [x] fixed 2026-11-24** · `Prefs.kt` set helpers, used from UI, GuardService main thread, `work` and `netExec` executors

Read-modify-write on sets (`setAutoSuspended(autoSuspended + pkg)`) can lose an update when two threads write at once. Fixed: `Prefs.updateSet(c, key) { … }` (one lock) for domains, Wi-Fi list, no-background, remembered networks, approved, blocked and kiosk lists; screen-time JSON writes share the engine's lock; Holds lists change only inside `Holds.set` (synchronized).

### B-09 Smaller bugs
**P3 · [ ]**
- [ ] `ScreenTime.usageBetween`: an app already open at midnight isn't counted until it's reopened.
- [ ] VPN filter sends DNS unencrypted (UDP 53) to Cloudflare.
- [ ] `Updater.autoCheck` saves the "last check" time before the check succeeds, so a failure waits 24 h.
- [ ] `Updater.install` doesn't check the downloaded APK's package/version before installing.
- [ ] TOTP codes can be reused within their 90 s window (no replay check).

---

## Remove

### R-01 Dead code: office split DNS and DoH
**P2 · [x] removed 2026-11-24** · `Services.kt:36-45, 136-137, 170-202`, `Features.kt` (`SmartDns.dohUrl`, `plainBackup`), `Prefs.kt` (`officeDomains`, `setOfficeDomains`)

Unused since the VPN backup was removed on 2026-11-23: `office`, `isOffice`, `forwardLocal`, `doh`, `dohUrl`, `SmartDns.dohUrl`, `SmartDns.plainBackup`, `Prefs.officeDomains`.

### R-02 Other unused code
**P3 · [x] done 2026-11-24**
- [ ] `PrivateDns.adbCommand` (`Features.kt`), `ScreenTime.activeSites`
- [ ] `RestrictionsActivity.guardOff`: now just calls its argument
- [ ] `MainActivity.showRecoveryCode`, `MainActivity.pcResetHelp`: one-line wrappers
- [ ] Duplicate KDoc on `WebFilter.enable` (`Features.kt:23-28`)
- [ ] `UNINSTALL_RESULT` / `UPDATE_RESULT` broadcasts: add a receiver ([B-06](#b-06-failures-are-hidden)) or delete them

### R-03 Decide on the VPN filter
**P2 · [ ]** · `Services.kt` (`DnsVpnService`), `Features.kt` (`WebFilter`), `WebActivity.kt`

About 250 lines plus the consent flow and Private-DNS-versus-VPN switching. It was the main source of app breakage, and the NextDNS denylist now covers "my site list in every app". Either remove it, or keep it and fix [B-01](#b-01-vpn-filter-blocks-all-ipv6).

### R-04 Location permission self-grant
**P3 · [-] won't fix for now (kept on request, 2026-11-23)** · `Device.kt:21-30`, manifest

Location is self-granted (and locked with `POLICY_FIXED`) only to show the current Wi-Fi name for the Wi-Fi allowlist. Option later: type names by hand, or ask only when "Add current network" is tapped.

---

## DRY (duplicated logic)

### D-01 Notifications
**P2 · [x] done 2026-11-24 (`Notify.kt`)** · `Features.kt` ×4, `ScreenTime.kt`, `Services.kt`, `SmartDns.enterFallback`

7 copies of create channel → build → notify. Fix: one `Notify.post(channel, title, text, id, ongoing)`; create channels once at app start.

### D-02 Installed apps and labels
**P2 · [x] fixed 2026-11-25 (`Apps.kt`)** · Apps, Background, BlockList, ScreenTime, Bloat, Approval, Profile, `Mdm.releaseAll`

Each loads `getInstalledApplications` + labels on its own thread; 10 files look up an app label. Fix: one cached `AppRepository` (`list()`, `label(pkg)`, `icon(pkg)`).

Done: `Apps.list / packages / userApps / label / icon / loadIcon`. Names are cached per process; the app list itself isn't cached (one system call, always fresh after installs). Every screen and policy loop uses it.

### D-03 Background threads
**P2 · [x] fixed 2026-11-25** · 28 copies in Apps, Background, ScreenTime, Settings, Web activities

`Thread { … runOnUiThread { if (!isFinishing) … } }` with no cancellation. Fix: convert each to `inBackground(work) { result -> }` (`Ui.kt`, added in P-01; Apps force stop already uses it). Later the helper itself can switch to `lifecycleScope` + coroutines in one place.

Done: no `Thread { }` or `runOnUiThread` left in any screen. `GuardService`'s startup sweep runs on its own `work` executor with each step in `runCatching` (an exception in the old raw thread would have crashed the app).

### D-04 Unlock and credential logic
**P2 · [x] done 2026-11-24**
- [ ] PIN → TOTP → unlock flow: `MainActivity.kt:67-73` and `Ui.kt:438-450` → one `unlockFlow(onOk)`
- [ ] `clearPin` + `Totp.disable`: `MainActivity.recoverWithCode` and `PinResetReceiver` → `Prefs.resetCredentials()`
- [ ] PIN rules (4–12 digits, both entries match): `MainActivity.kt:59-60` and `AdminUi.kt:49-50` → one validator
- [ ] Salted-hash store/check written twice (PIN, recovery code) → `storeHash(key)` / `checkHash(key)`
- [ ] Lockout logic in `Session.tryUnlock` and `tryRecovery` → one `attempt { }`

### D-05 Other duplication
**P3 · [ ]**
- [ ] Port-853 TLS test: `PrivateDns.reachable` and `SmartDns.dotReachable` → one function taking a `Network`
- [ ] Silent uninstall: `AppsActivity.uninstall` and `Blocklist.remove` → `AppControl.uninstall`
- [ ] `com.devguard.mdm` hardcoded in 6 ADB command strings → build from `BuildConfig.APPLICATION_ID`
- [ ] `guardOff` helper in `WebActivity` and `RestrictionsActivity`
- [ ] `Mdm(c)` created in 51 places, `isOwner` re-queried each time; `hasRestriction` reads the whole restrictions bundle per key → read once per screen (done for dashboard, Restrictions, export in P-01 via `Mdm.restrictions()`)

### D-06 Two preference styles
**P3 · [ ]** · `Prefs.kt`, `Profile.kt:14-18`

Typed functions (`Prefs.domains`) and raw key constants (`Prefs.getFlag(c, Prefs.CHROME_ON)`) are mixed; `Profile.SECRET_KEYS` repeats key names as literal strings. Fix: one key definition per preference with flags (`secret`, `exportable`), so export/import can't drift.

---

## Architecture and quality

### A-01 One reconciler for app state
**P2 · [x] done 2026-11-24 (`Holds.kt`)** · fixes [B-02](#b-02-suspend--hide-have-no-owner)

Each feature declares which apps it wants suspended or hidden (manual, approval, screen time, blocklist, Chrome-only). One engine combines the sets and applies only the difference. It removes the scattered `setSuspended` / `setHidden` calls and the ownership bugs.

### A-02 Split Features.kt
**P3 · [ ]** · `Features.kt` (734 lines)

One file per feature: `WebFilter`, `PrivateDns`, `SmartDns`, `ChromePolicy`, `Kiosk`, `Blocklist`, `Approval`.

### A-03 Application class and WorkManager
**P3 · [ ]**

- An `Application` subclass for notification channels, the `Mdm` instance and the app repository.
- WorkManager for the daily update check and periodic checks, instead of everything on `TIME_TICK` inside the always-on service. Screen time re-reads all usage since midnight every minute; cache it.

### A-04 Tests and build
**P3 · [ ]**
- [ ] Unit tests for pure logic: `ScreenTime.isActive`, DNS packet parsing (`question`, `nxdomain`), `Totp`, `Blocklist.parse`, `WebFilter.normalize`
- [ ] Enable R8 (`isMinifyEnabled = true`, `shrinkResources`) for release, with keep rules for the admin receiver and services

### A-05 Strings and UI
**P3 · [ ]**
- [ ] Move UI text to `res/values/strings.xml`
- [ ] Re-render only the changed card instead of the whole page (fixes [B-07](#b-07-typed-text-lost-on-page-refresh))
- [x] Cache row status in the Apps list instead of six system calls per row while scrolling (P-01)

---

## Done

| ID | Date | Commit | Note |
|---|---|---|---|
| S-01 (part) | 2026-11-23 | `50a3593`, `b24fdd3` | Dedicated keystore in `keys/` with strong password |
| S-01 (part) | 2026-11-24 | — | Keystore + `keystore.properties` uploaded to private cloud storage |
| B-03 | 2026-11-24 | see git log | Guard wait tied to one action |
| S-02 | 2026-11-24 | see git log | Profile import guarded; Danger zone and NextDNS kept open by decision |
| B-01 (code) | 2026-11-24 | see git log | IPv6 allowed in the VPN filter; phone test pending |
| B-04 | 2026-11-24 | `0676138` | ADB PIN reset verified on the phone |
| B-02 / A-01 | 2026-11-24 | see git log | Holds.kt: per-feature pause/hide lists; migration verified on the phone |
| S-04, B-05, B-06, B-08, R-01, R-02, D-01, D-04 | 2026-11-24 | see git log | Group 2 |
| S-03 | 2026-11-25 | see git log | Secrets encrypted with Android Keystore |
| P-01 | 2026-11-25 | see git log | System calls off the main thread; release in the background with a `releasing` guard |
| P-02, D-02, D-03, B-07 | 2026-11-25 | see git log | `Apps.kt`, background lists and icons, no raw threads, typed text kept |
