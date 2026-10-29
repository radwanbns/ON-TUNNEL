package com.ontunnel.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * ON TUNNEL starter: remote/manual config manager and UI.
 * A working VPN engine is not included yet; see README.
 */
class MainActivity : Activity() {
    private val navy = Color.rgb(7, 20, 38)
    private val panel = Color.rgb(16, 43, 72)
    private val cyan = Color.rgb(67, 204, 255)
    private val white = Color.WHITE
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("on_tunnel", MODE_PRIVATE) }
    private val servers = mutableListOf<Server>()
    private lateinit var serverSummary: TextView
    private lateinit var status: TextView
    private var selectedProtocol = "VLESS"

    data class Server(val name: String, val protocol: String, val address: String, val config: String, val source: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedServers()
        buildHome()
        refreshRemoteServers()
    }

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(navy)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("ON TUNNEL", 26f, white).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "☰ Menu"; setTextColor(cyan); setBackgroundColor(panel)
            setOnClickListener { showSideMenu() }
        })
        header.addView(Button(this).apply {
            text = "⚙"; setTextColor(cyan); setBackgroundColor(panel)
            contentDescription = "Settings"
            setOnClickListener { showSettings() }
        })
        root.addView(header)
        root.addView(label("SECURE VPN CONNECTION", 12f, cyan))
        root.addView(View(this).apply { minimumHeight = dp(24) })

        root.addView(Button(this).apply {
            text = "⏻   CONNECT"; textSize = 20f; setTextColor(white)
            setBackgroundColor(Color.rgb(18, 59, 98)); setPadding(dp(16), dp(22), dp(16), dp(22))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("VPN engine not integrated")
                    .setMessage("This starter manages server configs, but a V2Ray/SSH engine and Android VpnService still need to be integrated and tested. A saved config alone does not establish a VPN connection.")
                    .setPositiveButton("OK", null).show()
            }
        }, matchWrap())
        status = label("●  Disconnected", 14f, Color.LTGRAY).apply { gravity = Gravity.CENTER }
        root.addView(status, matchWrap())
        root.addView(sectionTitle("Connection"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val protocols = listOf("VLESS", "VMess", "Trojan", "Shadowsocks", "SSH")
        row.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, protocols)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedProtocol = protocols[position]
                }
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(Button(this).apply {
            text = "+ Add"; setTextColor(white); setBackgroundColor(panel); setOnClickListener { showAddServer() }
        })
        root.addView(row)
        serverSummary = label("No server selected", 14f, white).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(panel)
        }
        root.addView(serverSummary, matchWrap())
        root.addView(sectionTitle("Manage servers"))
        root.addView(actionButton("Refresh remote servers") { refreshRemoteServers() })
        root.addView(actionButton("View / select servers") { showServers() })
        root.addView(actionButton("Import config from text") { importConfigText() })
        root.addView(actionButton("App updates (GitHub Releases)") { showAppUpdates() })
        root.addView(actionButton("Settings & diagnostics") { showSettings() })
        root.addView(label("Remote source can be changed in Settings.", 12f, Color.LTGRAY))
        updateSummary()
    }

    /** Quick side-menu actions for community links, sharing, and Android tethering settings. */
    private fun showSideMenu() {
        val items = arrayOf(
            "✈ Telegram channel",
            "▶ YouTube channel",
            "↗ Share ON TUNNEL app",
            "📶 Internet sharing / Hotspot",
            "⚙ Settings"
        )
        AlertDialog.Builder(this)
            .setTitle("ON TUNNEL • Quick menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openCommunityLink("telegram_url", DEFAULT_TELEGRAM_URL, "Telegram")
                    1 -> openCommunityLink("youtube_url", DEFAULT_YOUTUBE_URL, "YouTube")
                    2 -> shareApp()
                    3 -> openInternetSharingSettings()
                    4 -> showSettings()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openCommunityLink(key: String, defaultUrl: String, service: String) {
        val url = prefs.getString(key, defaultUrl) ?: defaultUrl
        if (url.contains("YOUR_", ignoreCase = true)) {
            AlertDialog.Builder(this).setTitle(service + " link")
                .setMessage("Add your $service channel URL in the app settings/code before using this button.")
                .setPositiveButton("OK", null).show()
            return
        }
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { toast("Could not open $service") }
    }

    private fun shareApp() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ON TUNNEL")
            putExtra(Intent.EXTRA_TEXT, "Try ON TUNNEL: ${prefs.getString("releases_url", DEFAULT_RELEASES_URL)}")
        }
        startActivity(Intent.createChooser(send, "Share ON TUNNEL"))
    }

    private fun openInternetSharingSettings() {
        // Android controls hotspot/tethering. Opening its settings is supported more widely
        // than trying to enable tethering silently, which ordinary apps cannot do reliably.
        try {
            startActivity(Intent("android.settings.TETHER_SETTINGS"))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
            catch (_: Exception) { toast("Open Android Settings > Hotspot & tethering") }
        }
        toast("Enable Hotspot in Android settings. VPN sharing depends on your phone and Android version.")
    }

    private fun showAddServer() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val name = EditText(this).apply { hint = "Server name"; setTextColor(white); setHintTextColor(Color.LTGRAY) }
        val config = EditText(this).apply {
            hint = if (selectedProtocol == "SSH") "SSH host:port / details" else "Paste $selectedProtocol URI"
            setTextColor(white); setHintTextColor(Color.LTGRAY); minLines = 3
        }
        box.addView(name); box.addView(config)
        AlertDialog.Builder(this).setTitle("Add $selectedProtocol server").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val raw = config.text.toString().trim()
                if (raw.isNotEmpty()) {
                    servers.add(Server(name.text.toString().ifBlank { "$selectedProtocol ${servers.size + 1}" },
                        selectedProtocol, extractAddress(raw), raw, "manual"))
                    saveManualServers(); updateSummary(); toast("Server config saved")
                } else toast("Enter a config first")
            }.show()
    }

    private fun showServers() {
        if (servers.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Servers").setMessage("No servers loaded. Refresh remote servers or add one manually.")
                .setPositiveButton("Add server") { _, _ -> showAddServer() }.setNegativeButton("Close", null).show()
            return
        }
        val items = servers.map { "${it.name} • ${it.protocol} • ${it.source}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose server").setItems(items) { _, index ->
            prefs.edit().putString("selected_config", servers[index].config)
                .putString("selected_name", servers[index].name).apply()
            serverSummary.text = "${servers[index].name}\n${servers[index].protocol} • ${servers[index].address}"
            toast("Selected ${servers[index].name}")
        }.setNeutralButton("Clear manual servers") { _, _ ->
            servers.removeAll { it.source == "manual" }; saveManualServers(); updateSummary()
        }.setNegativeButton("Close", null).show()
    }

    private fun refreshRemoteServers() {
        val endpoint = prefs.getString("servers_url", DEFAULT_SERVERS_URL) ?: DEFAULT_SERVERS_URL
        status.text = "●  Loading remote servers…"
        executor.execute {
            try {
                val body = downloadText(endpoint)
                val parsed = parseRemote(body)
                runOnUiThread {
                    servers.removeAll { it.source == "remote" }
                    servers.addAll(parsed)
                    prefs.edit().putString("remote_cache", body).apply()
                    status.text = "●  Remote list updated (${parsed.size})"
                    updateSummary()
                    toast("Remote entries loaded: ${parsed.size}")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "●  Remote update failed"
                    toast("Remote update failed; using cached list if available")
                    updateSummary()
                }
            }
        }
    }

    private fun parseRemote(body: String): List<Server> = body.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexedNotNull { index, line ->
            val protocol = when {
                line.startsWith("vless://", true) -> "VLESS"
                line.startsWith("vmess://", true) -> "VMess"
                line.startsWith("trojan://", true) -> "Trojan"
                line.startsWith("ss://", true) -> "Shadowsocks"
                line.startsWith("ssh://", true) -> "SSH"
                else -> return@mapIndexedNotNull null
            }
            Server(uriName(line, "$protocol ${index + 1}"), protocol, extractAddress(line), line, "remote")
        }

    private fun importConfigText() {
        val input = EditText(this).apply {
            hint = "Paste one URI per line"; setTextColor(white); setHintTextColor(Color.LTGRAY); minLines = 4
        }
        AlertDialog.Builder(this).setTitle("Import configs").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ ->
                val imported = parseRemote(input.text.toString()).map { it.copy(source = "manual") }
                if (imported.isNotEmpty()) {
                    servers.addAll(imported); saveManualServers(); updateSummary(); toast("Imported ${imported.size} configs")
                } else toast("No supported URI found")
            }.show()
    }

    private fun showSettings() {
        val urlField = EditText(this).apply {
            setText(prefs.getString("servers_url", DEFAULT_SERVERS_URL))
            setTextColor(white); setHintTextColor(Color.LTGRAY); hint = "HTTPS servers.txt URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val releasesField = EditText(this).apply {
            setText(prefs.getString("releases_url", DEFAULT_RELEASES_URL))
            setTextColor(white); setHintTextColor(Color.LTGRAY); hint = "GitHub Releases URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        box.addView(label("Remote server list URL", 13f, cyan)); box.addView(urlField)
        box.addView(label("App releases URL", 13f, cyan)); box.addView(releasesField)
        AlertDialog.Builder(this).setTitle("ON TUNNEL Settings").setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("servers_url", urlField.text.toString().trim())
                    .putString("releases_url", releasesField.text.toString().trim()).apply()
                toast("Settings saved")
            }.setNeutralButton("Open Releases") { _, _ ->
                openUrl(releasesField.text.toString().trim())
            }.setNegativeButton("Close", null).show()
    }

    private fun showAppUpdates() {
        val url = prefs.getString("releases_url", DEFAULT_RELEASES_URL) ?: DEFAULT_RELEASES_URL
        AlertDialog.Builder(this).setTitle("App updates")
            .setMessage("Current version: 1.0.0\n\nOpen GitHub Releases to check and download a newer APK. Android requires user approval to install an APK. This starter does not silently install updates.")
            .setPositiveButton("Open GitHub Releases") { _, _ -> openUrl(url) }
            .setNegativeButton("Close", null).show()
    }

    private fun loadSavedServers() {
        try {
            val arr = JSONArray(prefs.getString("manual_servers", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                servers.add(Server(o.optString("name"), o.optString("protocol"), o.optString("address"), o.optString("config"), "manual"))
            }
        } catch (_: Exception) {}
        prefs.getString("remote_cache", null)?.let { servers.addAll(parseRemote(it)) }
    }

    private fun saveManualServers() {
        val arr = JSONArray()
        servers.filter { it.source == "manual" }.forEach { s ->
            arr.put(JSONObject().put("name", s.name).put("protocol", s.protocol).put("address", s.address).put("config", s.config))
        }
        prefs.edit().putString("manual_servers", arr.toString()).apply()
    }

    private fun updateSummary() {
        if (!::serverSummary.isInitialized) return
        val name = prefs.getString("selected_name", null)
        val config = prefs.getString("selected_config", null)
        serverSummary.text = if (name != null && config != null) "$name\n${extractAddress(config)}" else
            "${servers.size} server entries available\nChoose a server to view details"
    }

    private fun extractAddress(config: String): String = try {
        val uri = Uri.parse(config)
        val host = uri.host ?: return config.take(90)
        if (uri.port > 0) "$host:${uri.port}" else host
    } catch (_: Exception) { config.take(90) }

    private fun uriName(config: String, fallback: String): String = try {
        Uri.decode(Uri.parse(config).fragment).takeIf { !it.isNullOrBlank() } ?: fallback
    } catch (_: Exception) { fallback }

    private fun downloadText(endpoint: String): String {
        require(endpoint.startsWith("https://")) { "HTTPS required" }
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("User-Agent", "ON-TUNNEL-Android")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun openUrl(url: String) {
        if (!url.startsWith("https://")) { toast("Enter a valid HTTPS URL"); return }
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { toast("No browser available") }
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(white); setBackgroundColor(panel)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) }
    }

    private fun sectionTitle(text: String) = label(text, 17f, white).apply {
        typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(18), 0, dp(8))
    }
    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() { executor.shutdown(); super.onDestroy() }

    companion object {
        const val DEFAULT_SERVERS_URL = "https://raw.githubusercontent.com/radwanbns/Redo-VPN/refs/heads/main/main/servers.txt"
        const val DEFAULT_RELEASES_URL = "https://github.com/radwanbns/ON-TUNNEL/releases"
        // Replace these placeholders with the real community links before publishing.
        const val DEFAULT_TELEGRAM_URL = "https://t.me/radouane_tech"
        const val DEFAULT_YOUTUBE_URL = "https://youtube.com/@radouane_tech"
    }
}
