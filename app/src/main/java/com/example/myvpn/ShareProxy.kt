package com.example.myvpn

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * مشاركة اتصال التطبيق مع أجهزة أخرى (الهوتسبوت أو نفس شبكة Wi-Fi).
 *
 * يفتح منفذاً واحداً على كل واجهات الشبكة يفهم بروتوكولين معاً:
 *  - SOCKS5 (TCP / CONNECT)
 *  - HTTP Proxy (CONNECT للـ HTTPS + طلبات HTTP العادية)
 * ويمرر كل الاتصالات عبر SOCKS5 المحلي الخاص بالنفق (SSH أو V2Ray)،
 * فتخرج حركة الأجهزة المشاركة عبر السيرفر نفسه.
 *
 * الأمان: يُقبل فقط العملاء من الشبكات الخاصة (192.168.x / 10.x / 172.16-31.x ...).
 */
object ShareProxy {

    /** هل المشاركة تعمل الآن؟ (للواجهة) */
    var active by mutableStateOf(false)
        private set
    var port by mutableStateOf(0)
        private set

    @Volatile private var server: ServerSocket? = null
    @Volatile private var upstreamPort = 0

    /** يقرأ الإعدادات وحالة الاتصال ويشغّل أو يوقف المشاركة وفقاً لها. */
    fun sync(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("servers", 0)
        val enabled = p.getBoolean("shareEnabled", false)
        val sharePort = p.getInt("sharePort", 8080)
        val upstream = if (V2rayManager.status == VpnStatus.CONNECTED) 10808 else 0
        thread(isDaemon = true) {
            if (enabled && upstream > 0) start(sharePort, upstream) else stop()
        }
    }

    @Synchronized
    private fun start(listenPort: Int, upstream: Int) {
        if (server != null && port == listenPort && upstreamPort == upstream) return
        stop()
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(listenPort)) // 0.0.0.0
            server = ss
            upstreamPort = upstream
            port = listenPort
            active = true
            val ips = localIps().joinToString(", ").ifBlank { "0.0.0.0" }
            AppLog.add("المشاركة: HTTP/SOCKS5 على $ips:$listenPort")
            thread(isDaemon = true) {
                while (!ss.isClosed) {
                    val c = try { ss.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { handle(c) }
                }
            }
        } catch (e: Exception) {
            server = null
            active = false
            AppLog.add("المشاركة: تعذر فتح المنفذ $listenPort — ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        val ss = server ?: run { active = false; return }
        try { ss.close() } catch (_: Exception) {}
        server = null
        active = false
        AppLog.add("المشاركة: تم الإيقاف")
    }

    /** عناوين IPv4 الخاصة بالهاتف (Wi-Fi / هوتسبوت) التي يتصل بها الأجهزة الأخرى. */
    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }

    // ───────────────────────── معالجة الاتصالات ─────────────────────────

    private fun allowed(a: InetAddress): Boolean =
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
            (a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC)

    private fun handle(c: Socket) {
        try {
            if (!allowed(c.inetAddress)) { c.close(); return }
            c.soTimeout = 20000
            c.tcpNoDelay = true
            val inp = BufferedInputStream(c.getInputStream(), 8192)
            val first = inp.read()
            if (first < 0) { c.close(); return }
            if (first == 5) handleSocks(c, inp) else handleHttp(c, inp, first)
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    // ----- SOCKS5 -----
    private fun handleSocks(c: Socket, inp: InputStream) {
        val out = c.getOutputStream()
        val nMethods = inp.read()
        if (nMethods < 0) return
        readFully(inp, ByteArray(nMethods))
        out.write(byteArrayOf(5, 0)); out.flush()

        val h = ByteArray(4)
        readFully(inp, h)
        if (h[0].toInt() != 5) return
        val cmd = h[1].toInt()
        val host = when (h[3].toInt()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { readFully(inp, it) }).hostAddress
            3 -> String(ByteArray(inp.read()).also { readFully(inp, it) }, Charsets.UTF_8)
            4 -> InetAddress.getByAddress(ByteArray(16).also { readFully(inp, it) }).hostAddress
            else -> null
        }
        val pb = ByteArray(2)
        readFully(inp, pb)
        val dport = ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF)

        if (host == null || cmd != 1) { // نقبل CONNECT (TCP) فقط
            out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush(); return
        }
        val up = try {
            connectUpstream(host, dport)
        } catch (_: Exception) {
            out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush(); return
        }
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        c.soTimeout = 0
        pipe(c, inp, up)
    }

    // ----- HTTP Proxy -----
    private fun handleHttp(c: Socket, inp: InputStream, first: Int) {
        val out = c.getOutputStream()
        val requestLine = readLine(inp, first) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) { respond(out, "400 Bad Request"); return }
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]

        val headers = ArrayList<String>()
        while (true) {
            val l = readLine(inp, -1) ?: return
            if (l.isEmpty()) break
            headers.add(l)
            if (headers.size > 100) return
        }

        if (method.equals("CONNECT", true)) {
            val (host, dport) = splitHostPort(target, 443)
            val up = try { connectUpstream(host, dport) } catch (_: Exception) {
                respond(out, "502 Bad Gateway"); return
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); out.flush()
            c.soTimeout = 0
            pipe(c, inp, up)
            return
        }

        // طلب HTTP عادي بصيغة absolute-URI: GET http://host/path HTTP/1.1
        if (!target.startsWith("http://", true)) { respond(out, "400 Bad Request"); return }
        val rest = target.substring(7)
        val slash = rest.indexOf('/')
        val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else "/"
        val (host, dport) = splitHostPort(hostPort, 80)

        val up = try { connectUpstream(host, dport) } catch (_: Exception) {
            respond(out, "502 Bad Gateway"); return
        }
        val req = ByteArrayOutputStream()
        req.write("$method $path $version\r\n".toByteArray())
        for (h in headers) {
            val name = h.substringBefore(':').trim().lowercase()
            if (name in DROP_HEADERS) continue
            req.write("$h\r\n".toByteArray())
        }
        req.write("Connection: close\r\n\r\n".toByteArray())
        up.getOutputStream().apply { write(req.toByteArray()); flush() }
        c.soTimeout = 0
        pipe(c, inp, up)
    }

    private val DROP_HEADERS = setOf("proxy-connection", "proxy-authorization", "connection", "keep-alive")

    private fun respond(out: java.io.OutputStream, status: String) {
        try {
            out.write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: Exception) {}
    }

    private fun splitHostPort(s: String, defPort: Int): Pair<String, Int> {
        if (s.startsWith("[")) { // IPv6 literal
            val end = s.indexOf(']')
            val host = s.substring(1, end)
            val p = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: defPort
            return host to p
        }
        val i = s.lastIndexOf(':')
        return if (i > 0) s.substring(0, i) to (s.substring(i + 1).toIntOrNull() ?: defPort) else s to defPort
    }

    // ----- الاتصال بالنفق (SOCKS5 المحلي) -----
    private fun connectUpstream(host: String, dport: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", upstreamPort), 10000)
            s.soTimeout = 20000
            s.tcpNoDelay = true
            val o = s.getOutputStream()
            val i = s.getInputStream()
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            val r = ByteArray(2)
            readFully(i, r)
            if (r[0].toInt() != 5 || r[1].toInt() != 0) throw IOException("SOCKS auth")

            val hb = host.toByteArray(Charsets.UTF_8)
            val req = ByteArrayOutputStream()
            req.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte()))
            req.write(hb)
            req.write(byteArrayOf((dport shr 8).toByte(), dport.toByte()))
            o.write(req.toByteArray()); o.flush()

            val h = ByteArray(4)
            readFully(i, h)
            if (h[1].toInt() != 0) throw IOException("SOCKS reply ${h[1]}")
            val skip = when (h[3].toInt()) {
                1 -> 4 + 2
                4 -> 16 + 2
                3 -> i.read() + 2
                else -> throw IOException("SOCKS atyp")
            }
            readFully(i, ByteArray(skip))
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    // ----- أدوات -----
    private fun pipe(a: Socket, aIn: InputStream, b: Socket) {
        val t = thread(isDaemon = true) {
            try { copy(aIn, b.getOutputStream()) } catch (_: Exception) {}
            try { b.shutdownOutput() } catch (_: Exception) {}
        }
        try { copy(b.getInputStream(), a.getOutputStream()) } catch (_: Exception) {}
        try { a.shutdownOutput() } catch (_: Exception) {}
        t.join(5000)
        try { b.close() } catch (_: Exception) {}
    }

    private fun copy(from: InputStream, to: java.io.OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = from.read(buf)
            if (n < 0) break
            to.write(buf, 0, n)
            to.flush()
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    /** يقرأ سطراً حتى \n؛ [first] هو أول بايت إن كان قد قُرئ مسبقاً (أو -1). */
    private fun readLine(inp: InputStream, first: Int): String? {
        val sb = ByteArrayOutputStream()
        var b = if (first >= 0) first else inp.read()
        while (b >= 0 && b != '\n'.code) {
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 16 * 1024) return null
            b = inp.read()
        }
        if (b < 0 && sb.size() == 0) return null
        return sb.toString("UTF-8")
    }
}
