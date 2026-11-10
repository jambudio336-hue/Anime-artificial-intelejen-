package ai.maskiplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** IPv4 DNS-only tunnel: intercept UDP/53, forward via protected socket, return DNS response. */
class MasKiplayVpnService : VpnService() {
    companion object {
        const val ACTION_START = "ai.maskiplay.app.vpn.START"
        const val ACTION_STOP = "ai.maskiplay.app.vpn.STOP"
        const val EXTRA_HOST = "resolver_host"
        const val EXTRA_PORT = "resolver_port"
        private const val CHANNEL_ID = "maskiplay_dns_vpn"
        private const val NOTIFICATION_ID = 9401
        private const val TUN_ADDRESS = "10.77.0.1"
        private const val DNS_ADDRESS = "10.77.0.2"
        private const val MAX_DNS_PACKET = 4096
        @Volatile var isRunning: Boolean = false
            private set
    }
    @Volatile private var stopping = false
    @Volatile private var resolverHost = "1.1.1.1"
    @Volatile private var resolverPort = 53
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val writeLock = Any()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel(); stopSelf(); return START_NOT_STICKY }
            ACTION_START, null -> {
                resolverHost = intent?.getStringExtra(EXTRA_HOST)
                    ?.takeIf { isIpv4Literal(it) } ?: "1.1.1.1"
                resolverPort = (intent?.getIntExtra(EXTRA_PORT, 53) ?: 53).coerceIn(1, 65535)
                if (tun == null) startTunnel()
            }
        }
        return START_STICKY
    }

    private fun startTunnel() {
        try {
            startAsForeground()
            val descriptor = Builder().setSession("Mas Kiplay DNS Shield")
                .setMtu(1500).addAddress(TUN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS).addRoute(DNS_ADDRESS, 32)
                .setBlocking(true).establish()
            if (descriptor == null) { stopSelf(); return }
            tun = descriptor
            stopping = false
            isRunning = true
            worker = thread(name = "MasKiplay-DNS-TUN", isDaemon = true) { readTunLoop(descriptor) }
        } catch (_: Exception) { stopTunnel(); stopSelf() }
    }

    private fun startAsForeground() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Mas Kiplay DNS Shield")
            .setContentText("DNS tunnel aktif · resolver $resolverHost")
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pending).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Mas Kiplay DNS VPN",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status tunnel DNS Mas Kiplay"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun readTunLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32767)
        try {
            while (!stopping && !Thread.currentThread().isInterrupted) {
                val count = input.read(buffer)
                if (count > 0) handleIpv4Packet(buffer, count, output)
            }
        } catch (_: Exception) {
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }

    private fun handleIpv4Packet(packet: ByteArray, size: Int, output: FileOutputStream) {
        if (size < 28 || (packet[0].toInt() ushr 4 and 0x0f) != 4) return
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        if (headerLength < 20 || size < headerLength + 8) return
        if ((packet[9].toInt() and 0xff) != OsConstants.IPPROTO_UDP) return
        val destIp = (0..3).joinToString(".") { (packet[16 + it].toInt() and 0xff).toString() }
        if (destIp != DNS_ADDRESS) return
        val sourcePort = readU16(packet, headerLength)
        val destinationPort = readU16(packet, headerLength + 2)
        val udpLength = readU16(packet, headerLength + 4)
        if (destinationPort != 53 || udpLength < 20 || headerLength + udpLength > size) return
        val dnsLength = udpLength - 8
        if (dnsLength > MAX_DNS_PACKET) return
        val query = packet.copyOfRange(headerLength + 8, headerLength + 8 + dnsLength)
        val response = forwardDns(query) ?: return
        val reply = buildUdpReply(packet, sourcePort, response)
        synchronized(writeLock) { try { output.write(reply); output.flush() } catch (_: Exception) {} }
    }

    private fun forwardDns(query: ByteArray): ByteArray? = try {
        val upstream = InetAddress.getByName(resolverHost)
        DatagramSocket().use { socket ->
            if (!protect(socket)) return null
            socket.soTimeout = 4000
            socket.send(DatagramPacket(query, query.size, upstream, resolverPort))
            val data = ByteArray(MAX_DNS_PACKET)
            val packet = DatagramPacket(data, data.size)
            socket.receive(packet)
            data.copyOf(packet.length).takeIf { it.size >= 12 }
        }
    } catch (_: Exception) { null }

    private fun buildUdpReply(request: ByteArray, clientPort: Int, dns: ByteArray): ByteArray {
        val reply = ByteArray(28 + dns.size)
        reply[0] = 0x45.toByte()
        writeU16(reply, 2, reply.size)
        reply[4] = request[4]; reply[5] = request[5]
        reply[6] = 0x40.toByte(); reply[7] = 0
        reply[8] = 64; reply[9] = OsConstants.IPPROTO_UDP.toByte()
        System.arraycopy(request, 16, reply, 12, 4)
        System.arraycopy(request, 12, reply, 16, 4)
        writeU16(reply, 10, checksum(reply, 0, 20))
        writeU16(reply, 20, 53); writeU16(reply, 22, clientPort)
        writeU16(reply, 24, 8 + dns.size); writeU16(reply, 26, 0)
        System.arraycopy(dns, 0, reply, 28, dns.size)
        return reply
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L; var index = offset; val end = offset + length
        while (index + 1 < end) {
            sum += (((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun isIpv4Literal(value: String): Boolean {
        val parts = value.trim().split(".")
        return parts.size == 4 && parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) &&
                (it.toIntOrNull() ?: 256) in 0..255
        }
    }

    private fun stopTunnel() {
        stopping = true; isRunning = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { worker?.interrupt() } catch (_: Exception) {}
        worker = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else { @Suppress("DEPRECATION") stopForeground(true) }
    }

    override fun onRevoke() { stopTunnel(); stopSelf(); super.onRevoke() }
    override fun onDestroy() { stopTunnel(); super.onDestroy() }
}
