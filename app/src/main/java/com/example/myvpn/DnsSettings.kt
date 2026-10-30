package com.example.myvpn

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.InetAddress

/** إعدادات DNS وحظر QUIC. كتتخزن فـ SharedPreferences("dns") وكتتطبق عند الاتصال التالي. */
object DnsConfig {
    class Preset(val id: String, val label: String, val a: String, val b: String)

    const val AUTO = "auto"
    const val CUSTOM = "custom"

    val presets = listOf(
        Preset(AUTO, "Auto (Cloudflare + Google)", "1.1.1.1", "8.8.8.8"),
        Preset("google", "Google (8.8.8.8)", "8.8.8.8", "8.8.4.4"),
        Preset("cloudflare", "Cloudflare (1.1.1.1)", "1.1.1.1", "1.0.0.1"),
        Preset("quad9", "Quad9 (9.9.9.9)", "9.9.9.9", "149.112.112.112"),
        Preset("adguard", "AdGuard (blocks ads)", "94.140.14.14", "94.140.15.15")
    )

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("dns", 0)

    fun mode(ctx: Context): String = p(ctx).getString("mode", AUTO) ?: AUTO
    fun custom1(ctx: Context): String = p(ctx).getString("c1", "") ?: ""
    fun custom2(ctx: Context): String = p(ctx).getString("c2", "") ?: ""

    /** حظر QUIC (UDP 443) باش المتصفح يرجع لـ TCP — كيصلح مواقع جوجل. مفعّل افتراضياً. */
    fun blockQuic(ctx: Context): Boolean = p(ctx).getBoolean("blockQuic", true)

    fun save(ctx: Context, mode: String, c1: String, c2: String) {
        p(ctx).edit().putString("mode", mode).putString("c1", c1.trim()).putString("c2", c2.trim()).apply()
    }

    fun setBlockQuic(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("blockQuic", on).apply()
    }

    fun reset(ctx: Context) {
        p(ctx).edit().clear().apply()
    }

    fun summary(ctx: Context): String = servers(ctx).joinToString(" · ")

    fun isValidIp(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.contains(':')) { // IPv6 (حرفي فقط، بدون استعلام DNS)
            if (!Regex("^[0-9a-fA-F:.]+$").matches(t)) return false
            return try { InetAddress.getByName(t) != null } catch (_: Exception) { false }
        }
        val parts = t.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() in 0..255
        }
    }

    /** السيرفرات اللي غادي تتحط فـ VPN. كترجع للافتراضي إلا كان المخصص غير صالح. */
    fun servers(ctx: Context): List<String> {
        val m = mode(ctx)
        if (m == CUSTOM) {
            val l = listOf(custom1(ctx), custom2(ctx)).map { it.trim() }.filter { isValidIp(it) }
            if (l.isNotEmpty()) return l
        }
        val preset = presets.firstOrNull { it.id == m } ?: presets[0]
        return listOf(preset.a, preset.b)
    }
}

/**
 * قسم DNS داخل نافذة الإعدادات — مستقل بذاته (كيحفظ وحدو)، كيتحط بسطر واحد:
 *     DnsSettingsSection()
 */
@Composable
fun DnsSettingsSection() {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(DnsConfig.mode(ctx)) }
    var c1 by remember { mutableStateOf(DnsConfig.custom1(ctx)) }
    var c2 by remember { mutableStateOf(DnsConfig.custom2(ctx)) }
    var quic by remember { mutableStateOf(DnsConfig.blockQuic(ctx)) }

    fun select(id: String) {
        mode = id
        DnsConfig.save(ctx, mode, c1, c2)
    }

    Column {
        Text("DNS", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Applied on the next connection", fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))

        (DnsConfig.presets.map { it.id to it.label } + (DnsConfig.CUSTOM to "Custom")).forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { select(id) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == id, onClick = { select(id) })
                Text(label, fontSize = 13.sp)
            }
        }

        if (mode == DnsConfig.CUSTOM) {
            OutlinedTextField(
                c1, { c1 = it.trim(); DnsConfig.save(ctx, mode, c1, c2) },
                label = { Text("Primary DNS (e.g. 8.8.8.8)") }, singleLine = true,
                isError = c1.isNotEmpty() && !DnsConfig.isValidIp(c1)
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                c2, { c2 = it.trim(); DnsConfig.save(ctx, mode, c1, c2) },
                label = { Text("Secondary DNS (optional)") }, singleLine = true,
                isError = c2.isNotEmpty() && !DnsConfig.isValidIp(c2)
            )
            if ((c1.isNotEmpty() && !DnsConfig.isValidIp(c1)) || (c2.isNotEmpty() && !DnsConfig.isValidIp(c2))) {
                Text("Invalid IP — default DNS will be used", fontSize = 12.sp, color = Color(0xFFEF4444))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(quic, { quic = it; DnsConfig.setBlockQuic(ctx, it) })
            Spacer(Modifier.width(8.dp))
            Text("Block QUIC (fixes Google in browsers)", fontSize = 13.sp)
        }
        Text(
            "Browsers use QUIC (UDP 443) with Google. If it cannot pass through the server the site " +
                "will not load. Blocking it makes the browser fall back to TCP.",
            fontSize = 12.sp
        )
    }
}
