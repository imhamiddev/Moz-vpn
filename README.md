# Moz VPN

Moz VPN is an unofficial, open-source Android VPN client built with Kotlin and Jetpack Compose (Material 3).

It signs in with a Firefox account, obtains a proxy pass from Mozilla's VPN service and routes the
device's traffic through the selected server using a local SOCKS5/HTTP proxy bridged to the VPN tunnel.

## Features

- Quick connect from the home screen, a Quick Settings tile and a home-screen widget
- Server list with live latency and a "Recommended" fastest server
- Network handover with make-before-break reconnect
- Light, dark and system themes
- Per-app split tunneling: bypass selected apps, or tunnel only selected apps
- New "Moz Aurora" design system with a floating bottom navigation bar

## Build

Requirements: Android Studio, JDK 17, Gradle 8.10.2 (wrapper included), Android NDK 26.1.10909125.

```bash
git clone https://github.com/imhamiddev/Moz-vpn.git
cd Moz-vpn
./gradlew assembleDebug
```

The native `hev-socks5-tunnel` sources are fetched automatically on the first build.

## Disclaimer

Moz VPN is not affiliated with or endorsed by Mozilla. It uses the free monthly traffic included with
a Firefox account; no subscription is required.

## License

MIT - see [LICENSE](LICENSE).
