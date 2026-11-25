# SENNET VPN Android v1.0

Final Android release package set for `com.sennetvpn`.

Artifacts:
- `SENNET-VPN-v1.0-universal-release.apk`
- `SENNET-VPN-v1.0-arm64-v8a-release.apk`
- `SENNET-VPN-v1.0-armeabi-v7a-release.apk`
- `SENNET-VPN-v1.0-x86-release.apk`
- `SENNET-VPN-v1.0-x86_64-release.apk`
- `SENNET-VPN-v1.0-release.aab`

Build notes:
- Flavor: `alphaRelease`
- Version name: `1.0`
- Version code: `100`
- Application ID: `com.sennetvpn`

Signing note:
- This workspace does not include `signing.properties`, so `release` currently falls back to the debug signing config.
- Before publishing to Google Play or distributing as a production-signed build, add a real `signing.properties` file and rebuild `app:assembleAlphaRelease` / `app:bundleAlphaRelease`.
