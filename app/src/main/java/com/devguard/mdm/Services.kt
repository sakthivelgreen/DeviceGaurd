// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

/** Foreground service that watches new installs (blocked apps + approved-apps mode). */
class GuardService : Service() {
    companion object {
        fun needed(c: Context) = Prefs.approvalMode(c) || Prefs.blockedPkgs(c).isNotEmpty() ||
            Prefs.getFlag(c, Prefs.CHROME_ONLY) || SmartDns.enabled(c) || SmartDns.fallbackActive(c) ||
            Prefs.noBackgroundPkgs(c).isNotEmpty() || ScreenTime.needed(c) || Prefs.getFlag(c, Prefs.UPD_AUTO) ||
            (Prefs.privateDnsHost(c) != null && PrivateDns.canWrite(c)) || SearchBlocker.active(c)
        fun start(c: Context) { c.startForegroundService(Intent(c, GuardService::class.java)) }
        /** Start or stop the watcher depending on current settings. */
        fun refresh(c: Context) {
            if (needed(c) && Mdm(c).isOwner) runCatching { start(c) }
            else c.stopService(Intent(c, GuardService::class.java))
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            if (i.action == Intent.ACTION_PACKAGE_CHANGED) return
            if (Mdm.releasing) return // Release all is unhiding apps: don't re-hide or uninstall them
            if (AppControl.isIgnored(pkg)) return // unhide after a force stop, not a real install
            if (Blocklist.onInstalled(c, pkg)) return
            if (pkg == ChromePolicy.CHROME || ChromePolicy.isBrowser(c, pkg)) runCatching { ChromePolicy.apply(c) } // new browser gets the policy profile
            if (ChromePolicy.onInstalled(c, pkg)) return
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            Approval.onInstalled(c, pkg)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = Notify.build(this, Channel.GUARD, "DeviceGuard MDM is active", "Enforcing device policies", ongoing = true)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_EXPORTED) else registerReceiver(receiver, f)
        runCatching { Holds.migrate(this) } // before screen time / approval act on pauses (B-02)
        work.execute { // a crash here used to kill the whole service (uncaught in a raw Thread)
            runCatching { Blocklist.sweep(this) }
            runCatching { if (Prefs.getFlag(this, Prefs.CHROME_ONLY)) ChromePolicy.otherBrowsers(this).forEach { ChromePolicy.onInstalled(this, it) } }
            runCatching { Approval.sweep(this) }
        }

        // Private DNS lock: revert any edit (vivo Settings ignores the "Lock Private DNS" restriction).
        for (key in listOf("private_dns_mode", "private_dns_specifier")) {
            contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(key), false, dnsObserver)
        }
        // Automatic date & time lock while screen time is in use (B-05): switched back on the moment it's turned off.
        for (key in listOf(android.provider.Settings.Global.AUTO_TIME, android.provider.Settings.Global.AUTO_TIME_ZONE)) {
            contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(key), false, timeObserver)
        }
        WebFilter.removeLegacyVpn(this) // R-03: clear an always-on VPN left by an older version
        runCatching { SearchBlocker.removeService(this) } // the accessibility service was removed
        PrivateDns.enforce(this)

        // Smart DNS: check every network the moment it connects or changes.
        runCatching { connectivity.registerDefaultNetworkCallback(netCallback) }
        runCatching { registerReceiver(wifiReceiver, IntentFilter(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)) }

        // Background apps: stop listed apps shortly after the screen turns off.
        val sf = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_TICK)        // screen time: every minute while awake
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, sf, RECEIVER_EXPORTED) else registerReceiver(screenReceiver, sf)
        work.execute { runCatching { ScreenTime.evaluate(this) } }
    }

    /** Separate worker for screen time and updates (netExec is busy with network checks / force stops). */
    private val work = Executors.newSingleThreadExecutor()

    // ---- Filter health check (SmartDns.checkNow) ----
    private val DNS_CHECK_MS = 3 * 60_000L
    @Volatile private var lastDnsCheck = 0L

    // ---- Background apps: stop on screen off ----
    private val stopListedRunnable = Runnable {
        netExec.execute { runCatching { AppControl.stopListed(this) } }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_SCREEN_OFF) {
                // Every minute / screen on / clock change: screen time, plus a daily update check.
                work.execute {
                    runCatching { ScreenTime.evaluate(c) }
                    if (i.action == Intent.ACTION_SCREEN_ON) runCatching { Updater.autoCheck(c) }
                }
                // Reconnect check while a Wi-Fi network has the filter on Automatic (every 3 minutes and on screen on).
                val now = android.os.SystemClock.elapsedRealtime()
                if (SmartDns.enabled(c) && SmartDns.fallbackActive(c) && SmartDns.onWifi(c) &&
                    (i.action == Intent.ACTION_SCREEN_ON || now - lastDnsCheck >= DNS_CHECK_MS)
                ) {
                    lastDnsCheck = now
                    netExec.execute { runCatching { SmartDns.checkNow(c) } }
                }
                if (i.action == Intent.ACTION_TIME_TICK) return
            }
            netHandler.removeCallbacks(stopListedRunnable)
            if (i.action == Intent.ACTION_SCREEN_OFF &&
                !Prefs.getFlag(c, Prefs.BG_SCREEN_OFF_OFF) &&
                Prefs.noBackgroundPkgs(c).isNotEmpty()
            ) netHandler.postDelayed(stopListedRunnable, 10_000) // screen back on within 10 s cancels it
        }
    }

    // ---- Smart DNS network watcher ----
    private val connectivity by lazy { getSystemService(android.net.ConnectivityManager::class.java) }
    private val netExec = Executors.newSingleThreadExecutor()
    private val netHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingCheck: Runnable? = null

    private fun scheduleCheck(n: android.net.Network, delayMs: Long, force: Boolean = false) {
        pendingCheck?.let { netHandler.removeCallbacks(it) }
        val r = Runnable { netExec.execute { runCatching { SmartDns.check(this, n, force) } } }
        pendingCheck = r
        netHandler.postDelayed(r, delayMs)
    }

    /** Wi-Fi turned off: enforce the filter at once (don't wait for the next network to settle). */
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, -1)
            if (state == android.net.wifi.WifiManager.WIFI_STATE_DISABLED || state == android.net.wifi.WifiManager.WIFI_STATE_DISABLING)
                netExec.execute { runCatching { SmartDns.enforce(c) } }
        }
    }

    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = scheduleCheck(network, 300)
        // Default network gone (e.g. Wi-Fi disconnected with no mobile data): enforce the filter right away.
        override fun onLost(network: android.net.Network) { netExec.execute { runCatching { SmartDns.enforce(this@GuardService) } } }
        override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) = scheduleCheck(network, 800)
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
            // With a strict Private DNS filter, Android drops VALIDATED when the filter can't be reached:
            // check right away (no 60 s de-dupe) so the phone switches to Automatic within seconds.
            val lost = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !SmartDns.fallbackActive(this@GuardService)
            scheduleCheck(network, if (lost) 3000 else 800, force = lost)
        }
    }

    private val dnsObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (PrivateDns.enforce(this@GuardService)) notifyDnsLocked()
        }
    }

    private val timeObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            work.execute {
                if (runCatching { Device.enforceAutoTime(this@GuardService) }.getOrDefault(false)) {
                    Notify.post(this@GuardService, Channel.TIME_LOCK, 43, "Automatic date & time stays on",
                        "Screen time needs the correct time, so automatic date & time was switched back on.")
                }
            }
        }
    }

    private fun notifyDnsLocked() =
        Notify.post(this, Channel.DNS_LOCK, 42, "Private DNS is locked", "Your administrator set this filter. The change was undone.")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!needed(this)) { stopSelf(); return START_NOT_STICKY }
        PrivateDns.enforce(this)
        connectivity.activeNetwork?.let { scheduleCheck(it, 300) }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { contentResolver.unregisterContentObserver(dnsObserver) }
        runCatching { contentResolver.unregisterContentObserver(timeObserver) }
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
        runCatching { unregisterReceiver(wifiReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        netHandler.removeCallbacks(stopListedRunnable)
        netExec.shutdownNow()
        work.shutdownNow()
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Mdm(c).ensureBaseline()
        GuardService.refresh(c)
    }
}
