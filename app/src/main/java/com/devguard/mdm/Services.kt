// DeviceGuard MDM — Author: Sakthivel J
package com.devguard.mdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local DNS filter. Only DNS traffic to a virtual resolver address enters the tunnel;
 * all other traffic goes out normally. Blocked names get NXDOMAIN, everything else is
 * forwarded to an upstream resolver over a protected socket.
 */
class DnsVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.devguard.mdm.STOP_VPN"
        private const val DNS_IP = "10.111.222.53"

        @Volatile private var blocked: Set<String> = emptySet()
        @Volatile private var upstreams: List<String> = listOf("1.1.1.1", "8.8.8.8")

        fun reload(c: Context) {
            blocked = Prefs.domains(c).map { it.lowercase() }.toSet()
            upstreams = if (Prefs.familyDns(c)) listOf("1.1.1.3", "1.0.0.3") else listOf("1.1.1.1", "8.8.8.8")
        }

        /** Matches the domain itself and every subdomain of a blocked entry. */
        fun isBlocked(name: String): Boolean {
            var n = name.lowercase().trimEnd('.')
            while (n.isNotEmpty()) {
                if (n in blocked) return true
                val i = n.indexOf('.')
                if (i < 0) return false
                n = n.substring(i + 1)
            }
            return false
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Prefs.webBlock(this)) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        reload(this)
        startVpn()
        return START_STICKY
    }

    @Synchronized
    private fun startVpn() {
        if (tun != null) return
        val pfd = Builder()
            .setSession("DeviceGuard web filter")
            .setMtu(4500)
            .addAddress("10.111.222.1", 32)
            .addRoute(DNS_IP, 32)
            .addDnsServer(DNS_IP)
            .setBlocking(true)
            .establish() ?: return
        tun = pfd
        val p = Executors.newFixedThreadPool(8)
        pool = p
        reader = Thread({ loop(pfd, p) }, "dns-tun").also { it.start() }
    }

    @Synchronized
    private fun stopVpn() {
        reader?.interrupt(); reader = null
        runCatching { tun?.close() }; tun = null
        pool?.shutdownNow(); pool = null
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun loop(pfd: ParcelFileDescriptor, p: ExecutorService) {
        val input = FileInputStream(pfd.fileDescriptor)
        val out = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (!Thread.currentThread().isInterrupted) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n <= 0) continue
            val pkt = buf.copyOf(n)
            try { p.execute { handle(pkt, out) } } catch (e: Exception) { break }
        }
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }

    private fun handle(p: ByteArray, out: FileOutputStream) {
        try {
            if (p.size < 28 || ((p[0].toInt() shr 4) and 0xF) != 4 || p[9].toInt() != 17) return
            val ihl = (p[0].toInt() and 0xF) * 4
            if (p.size < ihl + 8 || u16(p, ihl + 2) != 53) return
            val len = minOf(u16(p, ihl + 4) - 8, p.size - ihl - 8)
            if (len < 12) return
            val q = p.copyOfRange(ihl + 8, ihl + 8 + len)
            val (name, qEnd) = question(q) ?: return
            val resp = if (isBlocked(name)) nxdomain(q, qEnd) else (forward(q) ?: return)
            val pkt = wrap(p, ihl, resp)
            synchronized(out) { out.write(pkt) }
        } catch (_: Exception) {
        }
    }

    /** Parses the first question name; returns (name, offset just past the question). */
    private fun question(q: ByteArray): Pair<String, Int>? {
        if (u16(q, 4) < 1) return null
        var o = 12
        val sb = StringBuilder()
        while (true) {
            if (o >= q.size) return null
            val l = q[o].toInt() and 0xFF
            if (l == 0) { o++; break }
            if ((l and 0xC0) != 0 || o + 1 + l > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..l) sb.append((q[o + i].toInt() and 0xFF).toChar())
            o += 1 + l
        }
        return if (o + 4 <= q.size) sb.toString() to (o + 4) else null
    }

    private fun nxdomain(q: ByteArray, qEnd: Int): ByteArray {
        val r = q.copyOf(qEnd)
        r[2] = (0x80 or (q[2].toInt() and 0x79)).toByte() // QR=1, keep opcode + RD
        r[3] = 0x83.toByte()                                // RA=1, RCODE=NXDOMAIN
        put16(r, 4, 1); put16(r, 6, 0); put16(r, 8, 0); put16(r, 10, 0)
        return r
    }

    private fun forward(q: ByteArray): ByteArray? {
        for (server in upstreams) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 3000
                    s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
                    val rb = ByteArray(4096)
                    val dp = DatagramPacket(rb, rb.size)
                    s.receive(dp)
                    return rb.copyOf(dp.length)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Builds the IPv4/UDP reply packet for [req] carrying [payload]. */
    private fun wrap(req: ByteArray, ihl: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val r = ByteArray(total)
        r[0] = 0x45; put16(r, 2, total); r[6] = 0x40; r[8] = 64; r[9] = 17
        System.arraycopy(req, 16, r, 12, 4) // src = original dst
        System.arraycopy(req, 12, r, 16, 4) // dst = original src
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(r, i)
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        put16(r, 10, sum.inv() and 0xFFFF)
        put16(r, 20, 53)
        put16(r, 22, u16(req, ihl))
        put16(r, 24, 8 + payload.size)
        put16(r, 26, 0) // UDP checksum optional on IPv4
        System.arraycopy(payload, 0, r, 28, payload.size)
        return r
    }
}

/** Foreground service that watches new installs (blocked apps + approved-apps mode). */
class GuardService : Service() {
    companion object {
        private const val CHANNEL = "guard"
        fun needed(c: Context) = Prefs.approvalMode(c) || Prefs.blockedPkgs(c).isNotEmpty()
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
            if (Blocklist.onInstalled(c, pkg)) return
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            Approval.onInstalled(c, pkg)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Device protection", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("DeviceGuard MDM is active")
            .setContentText("Watching app installs")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_EXPORTED) else registerReceiver(receiver, f)
        Thread { Blocklist.sweep(this); Approval.sweep(this) }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!needed(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Mdm(c).ensureBaseline()
        GuardService.refresh(c)
        // As device owner the system restarts the always-on VPN itself; otherwise start it here.
        if (Prefs.webBlock(c) && VpnService.prepare(c) == null) {
            runCatching { c.startService(Intent(c, DnsVpnService::class.java)) }
        }
    }
}
