package com.example.myvpn

/**
 * يحوّل ملف السيرفرات (نص أو Base64) إلى قائمة سيرفرات V2Ray.
 * كل سطر: vless://...  أو  vmess://...  أو  trojan://...
 */
object SubParser {

    fun parse(raw: String, subUrl: String): List<Server> {
        var body = raw.removePrefix("\uFEFF").trim()
        if (!body.contains("://")) {
            body = try { V2rayManager.b64(body) } catch (_: Exception) { body }
        }
        val result = ArrayList<Server>()
        val seen = HashSet<String>()
        body.lines().map { it.trim() }.forEach { line ->
            if (line.isEmpty() || !V2rayManager.isSupportedLink(line) || !seen.add(line)) return@forEach
            result.add(
                Server(
                    name = V2rayManager.nameOf(line).ifBlank { "Server ${result.size + 1}" },
                    config = line,
                    sub = subUrl
                )
            )
        }
        return result
    }
}
