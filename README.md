# ON TUNNEL — Kotlin Android starter

Includes a dark ON TUNNEL interface, remote server-list refresh, manual server entry, URI import, local storage, and a GitHub Releases link for app updates.

## Important
This is a **UI and server/configuration manager starter**, not yet a functioning VPN tunnel. The Connect button intentionally explains that the V2Ray/SSH engine and Android `VpnService` are not integrated yet. Real V2Ray/SSH support must be implemented and tested before calling this a working VPN.

## Build APK using GitHub from your phone
1. Create a new GitHub repository named `ON-TUNNEL`.
2. Upload all project files and folders to the repository root, keeping their paths.
3. Open **Actions** and choose **Build ON TUNNEL APK**.
4. Tap **Run workflow** (or push a commit to `main`).
5. When the workflow succeeds, open the run and download `ON-TUNNEL-debug-apk` under Artifacts.
6. Extract the ZIP and install `app-debug.apk`. Android may ask to allow installs from the app used to open it.

## Server source
Default URL: `https://raw.githubusercontent.com/radwanbns/Redo-VPN/refs/heads/main/main/servers.txt`
You can change it in in-app Settings. One supported URI per line: `vless://`, `vmess://`, `trojan://`, `ss://`, or `ssh://`. Blank lines and `#` comments are ignored. The last successful response is cached.

## App updates
The app opens the configured GitHub Releases page. Publish new APKs as release assets. The default assumes `radwanbns/ON-TUNNEL`; change it if your repository differs. Updates are user-driven via browser, not silently installed.

## Production release
GitHub Actions builds a debug APK for testing. For public distribution, add a signed release build using a keystore stored as GitHub Actions secrets. Never commit signing keys or passwords.

## Quick menu additions
The home header includes a Menu with Telegram and YouTube links, Android app sharing,
and a shortcut to Android hotspot/tethering settings. Replace `DEFAULT_TELEGRAM_URL`
and `DEFAULT_YOUTUBE_URL` in `MainActivity.kt` with your real channel links. Android
controls hotspot activation; sharing a VPN tunnel through hotspot depends on the device,
Android version, and VPN implementation. This starter still does not include a working
V2Ray/SSH VPN engine.
