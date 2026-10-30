# ON Tunnel — V2Ray فقط

تطبيق VPN يعمل بمحرك V2Ray (VLESS / VMess / Trojan) فقط، مع تحديث السيرفرات من رابط GitHub.

## تحديث السيرفرات من GitHub
1. أنشئ ملف `servers.txt` على GitHub (انظر `servers.example.txt`).
2. افتح الملف واضغط **Raw** وانسخ الرابط.
3. ضعه في `app/src/main/java/com/example/myvpn/AppConfig.kt` داخل `SUB_URL`.
4. كل ما تعدّل الملف على GitHub، يتحدث التطبيق وحده عند فتحه (أو بزر ⟳).

## قبل النشر
- `Links.kt`: بدّل `YOUR_CHANNEL` بقنواتك.
- `AppConfig.kt` و`AndroidManifest.xml`: بدّل معرّفات AdMob التجريبية بالحقيقية.

## البناء
GitHub Actions (`.github/workflows/build.yml`) يبني محرك V2Ray ونفق hev ثم الـ APK.
