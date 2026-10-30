package com.example.myvpn

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** سيرفر V2Ray: الاسم + الرابط (vless/vmess/trojan) + مصدره (رابط GitHub أو فارغ = يدوي). */
data class Server(
    val name: String,
    val config: String,
    val sub: String = ""
)

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED }

class VpnViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("servers", 0)

    val servers = mutableStateListOf<Server>()
    var selected by mutableStateOf(0)
    private var localError by mutableStateOf<String?>(null)

    val status: VpnStatus get() = V2rayManager.status
    val error: String? get() = localError ?: V2rayManager.error

    // مشاركة الإنترنت (HTTP proxy)
    var shareEnabled by mutableStateOf(prefs.getBoolean("shareEnabled", false))
    var sharePort by mutableStateOf(prefs.getInt("sharePort", 8080))

    // التحديث من GitHub
    private fun initialSubUrl(): String {
        val saved = prefs.getString("subUrl", "") ?: ""
        return if (AppConfig.SUB_URL.isNotBlank() && (AppConfig.LOCK_SUB || saved.isBlank())) {
            AppConfig.SUB_URL
        } else saved
    }

    var subUrl by mutableStateOf(initialSubUrl())
    var subAuto by mutableStateOf(prefs.getBoolean("subAuto", true))
    var subBusy by mutableStateOf(false)
    var subMessage by mutableStateOf<String?>(null)
    var lastUpdated by mutableStateOf(prefs.getLong("lastUpdate", 0L))
    /** يزداد عند تحديث ناجح فيه سيرفرات جديدة (للافتة Update Success). */
    var updateSuccessTick by mutableStateOf(0)
    var updateNewCount by mutableStateOf(0)

    // Ping (config -> ms، -1 = فشل)
    val pings = mutableStateMapOf<String, Int>()
    var pinging by mutableStateOf(false)

    init {
        loadServers()
        if (subAuto && subUrl.isNotBlank()) updateSubscription()
    }

    fun saveSubscription(url: String, auto: Boolean) {
        val locked = AppConfig.LOCK_SUB && AppConfig.SUB_URL.isNotBlank()
        subUrl = if (locked) AppConfig.SUB_URL else url.trim()
        subAuto = auto
        prefs.edit().putString("subUrl", subUrl).putBoolean("subAuto", subAuto).apply()
    }

    fun updateSubscription() {
        val url = subUrl.trim()
        if (url.isBlank()) { subMessage = "Enter the GitHub link first"; return }
        if (!url.startsWith("https://", true)) { subMessage = "The link must start with https://"; return }
        if (subBusy) return
        subBusy = true
        subMessage = "Updating..."
        AppLog.add("GitHub: جارٍ تحديث السيرفرات")
        viewModelScope.launch {
            try {
                val links = withContext(Dispatchers.IO) { fetchLinks(url) }
                val prevKey = servers.getOrNull(selected)?.config
                val old = servers.filter { it.sub == url }.toSet()
                val newCount = links.count { it !in old }
                servers.removeAll { it.sub == url }
                servers.addAll(0, links)
                selected = servers.indexOfFirst { it.config == prevKey }.takeIf { it >= 0 } ?: 0
                saveServers()
                lastUpdated = System.currentTimeMillis()
                prefs.edit().putLong("lastUpdate", lastUpdated).apply()
                subMessage = null
                if (newCount > 0) {
                    updateNewCount = newCount
                    updateSuccessTick++
                }
                AppLog.add("GitHub: تم تحميل ${links.size} سيرفر ($newCount جديد)")
            } catch (e: Exception) {
                subMessage = "Update failed: ${e.message}"
                AppLog.add("GitHub: فشل التحديث — ${e.message}")
            } finally {
                subBusy = false
            }
        }
    }

    private fun fetchLinks(url: String): List<Server> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "ONTunnel/1.0")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { s ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 2_000_000) throw IOException("File too large")
                    out.write(buf, 0, n)
                }
            }
            val list = SubParser.parse(String(out.toByteArray(), Charsets.UTF_8), url)
            if (list.isEmpty()) throw IOException("No valid servers in the file")
            return list
        } finally {
            conn.disconnect()
        }
    }

    // ───────────── Ping ─────────────
    fun pingAll() {
        if (pinging || servers.isEmpty()) return
        pinging = true
        val list = servers.toList()
        viewModelScope.launch {
            val results = coroutineScope {
                list.map { s -> async(Dispatchers.IO) { s.config to tcpPing(s.config) } }.awaitAll()
            }
            results.forEach { (k, v) -> pings[k] = v }
            pinging = false
        }
    }

    private fun tcpPing(link: String): Int {
        val hp = V2rayManager.hostPortOf(link) ?: return -1
        return try {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(hp.first, hp.second), 3000) }
            ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
        } catch (_: Exception) { -1 }
    }

    fun saveShare(enabled: Boolean, port: Int) {
        var p = port.coerceIn(1024, 65535)
        while (p == 10808) p++ // لا يتعارض مع منفذ المحرك الداخلي
        shareEnabled = enabled
        sharePort = p
        prefs.edit().putBoolean("shareEnabled", enabled).putInt("sharePort", p).apply()
        ShareProxy.sync(getApplication<Application>())
    }

    fun select(index: Int) {
        if (index !in servers.indices) return
        selected = index
        prefs.edit().putString("selKey", servers[index].config).apply()
    }

    fun connect() {
        val server = servers.getOrNull(selected)
        if (server == null) { localError = "Add or update servers first"; return }
        localError = null
        V2rayManager.connect(getApplication<Application>(), server.config, server.name)
    }

    fun disconnect() {
        if (V2rayManager.status != VpnStatus.DISCONNECTED) {
            viewModelScope.launch(Dispatchers.IO) { V2rayManager.disconnect() }
        }
    }

    fun onPermissionDenied() {
        localError = "VPN permission was not granted"
    }

    fun addServer(s: Server) {
        if (!V2rayManager.isSupportedLink(s.config)) {
            localError = "Invalid link. Use vless:// or vmess:// or trojan://"
            return
        }
        localError = null
        servers.add(s.copy(name = s.name.ifBlank { V2rayManager.nameOf(s.config).ifBlank { "Server ${servers.size + 1}" } }))
        saveServers()
    }

    fun removeServer(index: Int) {
        if (index !in servers.indices) return
        servers.removeAt(index)
        if (selected >= servers.size) selected = (servers.size - 1).coerceAtLeast(0)
        saveServers()
    }

    private fun saveServers() {
        val arr = JSONArray()
        servers.forEach {
            arr.put(JSONObject().put("name", it.name).put("config", it.config).put("sub", it.sub))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun loadServers() {
        val raw = prefs.getString("list", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cfg = o.optString("config")
                if (!V2rayManager.isSupportedLink(cfg)) continue // يتجاهل سيرفرات SSH/WG القديمة
                servers.add(Server(o.optString("name").ifBlank { "Server ${servers.size + 1}" }, cfg, o.optString("sub")))
            }
        } catch (_: Exception) { }
        val key = prefs.getString("selKey", null)
        selected = servers.indexOfFirst { it.config == key }.takeIf { it >= 0 } ?: 0
    }
}
