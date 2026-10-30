package com.example.myvpn

/** إعدادات تُدمج داخل التطبيق عند البناء. */
object AppConfig {
    /**
     * رابط ملف السيرفرات على GitHub (raw). كل سطر رابط vless:// أو vmess:// أو trojan://
     * (أو الملف كله Base64). عدّل الملف على GitHub وغادي يتحدث التطبيق وحدو.
     * مثال: https://raw.githubusercontent.com/USER/REPO/main/servers.txt
     */
    const val SUB_URL = "https://raw.githubusercontent.com/radwanbns/Redo-VPN/main/servers.txt"

    /** true = المستخدم ما يقدرش يبدل الرابط من الإعدادات. */
    const val LOCK_SUB = true

    /** تفعيل إعلانات جوجل (AdMob). false = لا إعلانات ولا نافذة موافقة. */
    const val ADS_ENABLED = true

    /** معرّفات الإعلانات (الحالية تجريبية من جوجل — بدلها بمعرّفاتك الحقيقية قبل النشر). */
    const val ADMOB_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    const val INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED_ID = "ca-app-pub-3940256099942544/5224354917"

    /** الحد الأدنى بين إعلانين ملء الشاشة (بالثواني). */
    const val INTERSTITIAL_COOLDOWN_SEC = 180

    /** يظهر إعلان ملء الشاشة عند كل N عملية قطع اتصال. */
    const val INTERSTITIAL_EVERY_N_DISCONNECTS = 2
}
