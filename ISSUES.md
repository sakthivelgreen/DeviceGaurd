# DeviceGuard MDM: Issue tracker

Known flaws, cleanups and improvements from the code review on 2026-11-23. Each item has an ID (use it in commit messages, e.g. `Fix B-02: …`), a priority, a status and the files involved.

**Status:** `[ ]` open · `[~]` in progress · `[x]` done · `[-]` won't fix (say why)
**Priority:** **P1** fix soon (data loss, lock-out, broken feature) · **P2** should fix · **P3** nice to have

Paths are relative to `app/src/main/java/com/devguard/mdm/` unless they start with `app/` or the project root.

---

## Summary

| ID | Pri | Status | Title |
|---|---|---|---|
| [S-01](#s-01-release-signing-key-not-backed-up) | P1 | [~] | Signing key: dedicated keystore set up, cloud backup pending |
| [B-01](#b-01-vpn-filter-blocks-all-ipv6) | P1 | [ ] | VPN filter blocks all IPv6 (likely cause of apps not opening) |
| [B-02](#b-02-suspend--hide-have-no-owner) | P1 | [ ] | Suspend / hide flags have no owner; features undo each other |
| [B-03](#b-03-filter-guard-confirmation-not-tied-to-the-action) | P1 | [ ] | Filter guard confirmation isn't tied to the action |
| [S-02](#s-02-filter-guard-bypass-paths) | P1 | [ ] | Filter guard bypass paths (profile import, Danger zone, NextDNS) |
| [B-04](#b-04-adb-pin-reset-never-tested) | P1 | [ ] | ADB PIN reset never tested end-to-end |
| [S-03](#s-03-secrets-stored-in-plain-text) | P2 | [ ] | API key, GitHub token and TOTP secret stored in plain text |
| [S-04](#s-04-weak-brute-force-protection) | P2 | [ ] | Weak brute-force protection (4-digit PIN, in-memory lockout) |
| [B-05](#b-05-clock-changes-defeat-time-rules) | P2 | [ ] | Changing the clock defeats screen time and session timeout |
| [P-01](#p-01-heavy-binder-work-on-the-main-thread) | P2 | [ ] | Heavy system calls on the main thread (dashboard, export, release, Apps list) |
| [B-06](#b-06-failures-are-hidden) | P2 | [ ] | Failures are hidden; uninstall/update results go nowhere |
| [B-07](#b-07-typed-text-lost-on-page-refresh) | P2 | [ ] | Typed text lost when a page re-renders |
| [B-08](#b-08-concurrent-preference-writes) | P2 | [ ] | Concurrent read-modify-write of preference sets can lose updates |
| [B-09](#b-09-smaller-bugs) | P3 | [ ] | Smaller bugs (usage before midnight, plaintext VPN DNS, update check timer) |
| [R-01](#r-01-dead-code-office-split-dns-and-doh) | P2 | [ ] | Remove dead code: office split DNS + DoH |
| [R-02](#r-02-other-unused-code) | P3 | [ ] | Remove other unused functions and wrappers |
| [R-03](#r-03-decide-on-the-vpn-filter) | P2 | [ ] | Decide: fix or remove the VPN filter |
| [R-04](#r-04-location-permission-self-grant) | P3 | [-] | Location permission self-grant (kept on request) |
| [D-01](#d-01-notifications) | P2 | [ ] | DRY: notification building (7 copies) |
| [D-02](#d-02-installed-apps-and-labels) | P2 | [ ] | DRY: installed-app list + label lookups (10 files) |
| [D-03](#d-03-background-threads) | P2 | [ ] | DRY: `Thread { runOnUiThread { } }` (28 copies) |
| [D-04](#d-04-unlock-and-credential-logic) | P2 | [ ] | DRY: PIN/2FA unlock, credential reset, PIN rules, hashing, lockout |
| [D-05](#d-05-other-duplication) | P3 | [ ] | DRY: DoT test, uninstall, ADB strings, guard helper, `Mdm(c)` |
| [D-06](#d-06-two-preference-styles) | P3 | [ ] | DRY: two preference styles; export key lists can drift |
| [A-01](#a-01-one-reconciler-for-app-state) | P2 | [ ] | Architecture: one reconciler for suspended/hidden apps |
| [A-02](#a-02-split-featureskt) | P3 | [ ] | Architecture: split Features.kt (734 lines, 8 features) |
| [A-03](#a-03-application-class-and-workmanager) | P3 | [ ] | Architecture: Application class + WorkManager for periodic jobs |
| [A-04](#a-04-tests-and-build) | P3 | [ ] | Unit tests for pure logic; enable R8 for release |
| [A-05](#a-05-strings-and-ui) | P3 | [ ] | UI: strings.xml, partial re-render instead of full page rebuild |

---

## Security

### S-01 Release signing key not backed up
**P1 · [~] in progress** · `app/build.gradle.kts`, `keys/`, `keystore.properties`

Builds were signed with the PC's auto-generated debug keystore, stored in one place only. Losing it means the installed Device Owner app can never be updated (factory reset needed).

- [x] Key moved to `keys/deviceguard.keystore` with a strong password; `keystore.properties` points to it; both git-ignored (2026-11-23)
- [x] Debug and release builds both use it; fingerprint unchanged `E8:92:FC:E3…`
- [ ] **Upload `keys/deviceguard.keystore` + `keystore.properties` to a private cloud location** (and ideally a second offline copy)
- [ ] Install-over test on the phone with the new signing config (phone was disconnected)

### S-02 Filter guard bypass paths
**P1 · [ ]** · `Profile.kt:49-59`, `MainActivity.kt` (Danger zone), `WebActivity.kt` (NextDNS)

The guard only protects the Websites screen. Ways around it:
- **Profile import** writes any preference, including `guardOn=false` and `privateDnsHost`, with no guard.
- **Danger zone → Remove Device Owner** releases everything with only the PIN.
- **Use NextDNS → turn categories off** needs no puzzle (by request on 2026-11-23).
- The debug build allows `adb shell run-as` to edit preferences (expected for a dev build).

Fix: put the guard in front of profile import (or skip guard-related keys on import) and the Danger zone. Decide whether the NextDNS path stays open.

### S-03 Secrets stored in plain text
**P2 · [ ]** · `Prefs.kt`, `NextDns.kt`, `Updater.kt`, `Totp.kt`

The NextDNS API key, GitHub token and TOTP secret are plain SharedPreferences values. The debug build is debuggable, so `run-as` reads them. Fix: encrypt with an Android Keystore AES key (or Tink); keep the hash-only approach for PIN and recovery code.

### S-04 Weak brute-force protection
**P2 · [ ]** · `Prefs.kt:180-218`, `MainActivity.kt:59`

- A 4-digit PIN is allowed; 10k SHA-256 rounds is cracked instantly if the file leaks. Use PBKDF2 (or Argon2) with a higher cost, and consider a 6-digit minimum.
- The lockout counter lives in memory; killing the app resets it. Persist it, with growing delays.
- `Session` uses `System.currentTimeMillis()`; use `SystemClock.elapsedRealtime()`.

---

## Bugs

### B-01 VPN filter blocks all IPv6
**P1 · [ ]** · `Services.kt:84` (`DnsVpnService.startVpn`)

The VPN Builder never calls `allowFamily(OsConstants.AF_INET6)`, so Android adds `::/0 unreachable` for every app. This route was seen on the phone's VPN routing table. On IPv6-first networks (Airtel/Jio) this is the likely reason many apps failed to open with the VPN on. DNS over TCP (truncated answers) to the tunnel is also dropped.

Fix: `allowFamily(AF_INET6)`, and answer or forward TCP DNS (or set TC=0 with EDNS). Re-test the apps that failed.

### B-02 Suspend / hide have no owner
**P1 · [ ]** · `ScreenTime.kt:179-183`, `Features.kt` (Approval, Blocklist, ChromePolicy), `AppsActivity.kt`, `AppControl.kt`

Manual "Suspended", Approval mode, Screen time, Blocklist, Allow-only-Chrome and Force stop all flip the same Android flags. Example: suspend an app by hand, add it to a schedule; when the schedule ends, Screen time un-suspends it. See [A-01](#a-01-one-reconciler-for-app-state) for the fix.

### B-03 Filter guard confirmation not tied to the action
**P1 · [ ]** · `Guard.kt:34-72`

`GUARD_WAIT_START` is one global value. Solve the puzzle for a small action ("unblock example.com"), wait, then trigger a bigger one ("turn off the filter guard"): the confirm dialog accepts it. Fix: store the action key with the start time and only confirm the same action.

### B-04 ADB PIN reset never tested
**P1 · [ ]** · `PinResetReceiver.kt`, manifest (`android.permission.DUMP`)

The ADB reset is the last way back in, and it has never been run on the phone (Android 16). Test it once:
```powershell
.\platform-tools\adb.exe shell am broadcast -a com.devguard.mdm.RESET_PIN -n com.devguard.mdm/.PinResetReceiver
```
Expect the result text "DeviceGuard PIN cleared…", then set a new PIN.

### B-05 Clock changes defeat time rules
**P2 · [ ]** · `ScreenTime.kt`, `Prefs.kt` (`Session`)

Daily limits, extra time and day boundaries use wall-clock time; moving the date forward resets limits. Fix: as Device Owner, force network time (`setAutoTimeEnabled(admin, true)`, API 30+) and lock date/time while screen time is in use.

### B-06 Failures are hidden
**P2 · [ ]** · 142 `runCatching` blocks; `Features.kt:641`, `AppsActivity.kt:243`, `Updater.kt:78`

- Uninstall and update results go to `UNINSTALL_RESULT` / `UPDATE_RESULT` broadcasts that no receiver handles, so failures are never shown.
- Many policy calls ignore their result, and the UI reports success anyway.

Fix: add one result receiver (notify on failure); show the result of policy calls that can fail.

### B-07 Typed text lost on page refresh
**P2 · [ ]** · `Ui.kt` (`scrollPage`), all `BaseActivity` screens

Every switch rebuilds the whole page, so half-typed input (e.g. a site in Websites → Block this site) is cleared. Fix: keep input state across renders, or re-render only the changed card. See [A-05](#a-05-strings-and-ui).

### B-08 Concurrent preference writes
**P2 · [ ]** · `Prefs.kt` set helpers, used from UI, GuardService main thread, `work` and `netExec` executors

Read-modify-write on sets (`setAutoSuspended(autoSuspended + pkg)`) can lose an update when two threads write at once. Fix: synchronized update helpers (`Prefs.updateSet(key) { it + pkg }`).

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
**P2 · [ ]** · `Services.kt:36-45, 136-137, 170-202`, `Features.kt` (`SmartDns.dohUrl`, `plainBackup`), `Prefs.kt` (`officeDomains`, `setOfficeDomains`)

Unused since the VPN backup was removed on 2026-11-23: `office`, `isOffice`, `forwardLocal`, `doh`, `dohUrl`, `SmartDns.dohUrl`, `SmartDns.plainBackup`, `Prefs.officeDomains`.

### R-02 Other unused code
**P3 · [ ]**
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
**P2 · [ ]** · `Features.kt` ×4, `ScreenTime.kt`, `Services.kt`, `SmartDns.enterFallback`

7 copies of create channel → build → notify. Fix: one `Notify.post(channel, title, text, id, ongoing)`; create channels once at app start.

### D-02 Installed apps and labels
**P2 · [ ]** · Apps, Background, BlockList, ScreenTime, Bloat, Approval, Profile, `Mdm.releaseAll`

Each loads `getInstalledApplications` + labels on its own thread; 10 files look up an app label. Fix: one cached `AppRepository` (`list()`, `label(pkg)`, `icon(pkg)`).

### D-03 Background threads
**P2 · [ ]** · 28 copies in Apps, Background, ScreenTime, Settings, Web activities

`Thread { … runOnUiThread { if (!isFinishing) … } }` with no cancellation. Fix: `lifecycleScope.launch { withContext(IO) { … } }`.

### D-04 Unlock and credential logic
**P2 · [ ]**
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
- [ ] `Mdm(c)` created in 51 places, `isOwner` re-queried each time; `hasRestriction` reads the whole restrictions bundle per key → read once per screen

### D-06 Two preference styles
**P3 · [ ]** · `Prefs.kt`, `Profile.kt:14-18`

Typed functions (`Prefs.domains`) and raw key constants (`Prefs.getFlag(c, Prefs.CHROME_ON)`) are mixed; `Profile.SECRET_KEYS` repeats key names as literal strings. Fix: one key definition per preference with flags (`secret`, `exportable`), so export/import can't drift.

---

## Architecture and quality

### A-01 One reconciler for app state
**P2 · [ ]** · fixes [B-02](#b-02-suspend--hide-have-no-owner)

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
- [ ] Cache row status in the Apps list instead of six system calls per row while scrolling

---

## Done

| ID | Date | Commit | Note |
|---|---|---|---|
| S-01 (part) | 2026-11-23 | `50a3593`, `b24fdd3` | Dedicated keystore in `keys/` with strong password; backup still pending |
