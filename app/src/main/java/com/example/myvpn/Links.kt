package com.example.myvpn

/** روابط القنوات. بدّل YOUR_CHANNEL بقناتك؛ إلا خليتها فارغة أو بقات YOUR_CHANNEL كتتخبى. */
object Links {
    const val TELEGRAM = "https://t.me/YOUR_CHANNEL"
    const val YOUTUBE = "https://youtube.com/@YOUR_CHANNEL"

    fun isSet(url: String): Boolean = url.isNotBlank() && !url.contains("YOUR_CHANNEL")
}
