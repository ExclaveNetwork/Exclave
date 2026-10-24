# Exclave

Exclave is a proxy client.

<details>

Features:

- Various proxy protocols
- Group and subscription
- Routing
- Proxy chain

Some supported protocols:

- Shadowsocks (with SIP003 plugin support)
- Shadowsocks 2022 (with SIP003 plugin support)
- Trojan
- Hysteria 2
- AnyTLS
- mieru
- NaïveProxy (as a standalone plugin)
- TUIC
- Juicity
- VMess (with various optional sub-protocols)
- VLESS (with various optional sub-protocols)
- WireGuard (TCP and UDP only)
- TrustTunnel (no ICMP echo support)
- SSH proxy ("dynamic port forwarding")
- HTTP CONNECT tunnel (HTTP/1.1, HTTP/1.1 with TLS, HTTP/2 and HTTP/3)
- SOCKS4, SOCKS4A and SOCKS5

</details>

It is a fork of the archived Android proxy client SagerNet and uses a custom overhauled fork of V2Ray.

## Download

- Exclave

  [Download from GitHub releases](https://github.com/dyhkwong/Exclave/releases)

  [Download from F-Droid](https://f-droid.org/packages/com.github.dyhkwong.sagernet)

  SHA-256 hash of the signing certificate: `e9fe39e1ce254c50c2f9470a757b378c0b7cc536119867f7691405b592e6994b`

- NaïveProxy Plugin

  [Download from GitHub releases](https://github.com/klzgrad/naiveproxy/releases)

  It is distributed and signed by the upstream author.

Starting in September 2026, Google will [block apps from "sideloading"](https://developer.android.com/developer-verification) on [certified Android devices](https://www.android.com/certified/partners/). If you are a user who values digital freedom, we need your voice to [express opposition](https://keepandroidopen.org/). Your support will not only help save this app, but also help defend software freedom and open distribution.

## Explanation of terms

[Exclave wiki](https://github.com/dyhkwong/Exclave/wiki). It contains some subjective comments. Viewer discretion is advised.

## Translation

Is Exclave not in your language, or the translation is incorrect or incomplete? Get involved in the translations on [Hosted Weblate](https://hosted.weblate.org/projects/exclave/).

## Issue tracker

Please report bugs and submit feature requests [here](https://github.com/dyhkwong/Exclave/issues). Do not report to v2ray-core as this project has heavily modified the code of v2ray-core.

- Before creating a new issue, please search for existing ones. Do not create duplicate issues.
- Old versions are not supported. Please ensure that you are using the latest version.
- For crashes, log file using "debug" log level is required. The log file may contain the secret keys used to connect to your servers. Please remove potential sensitive information before posting them publicly.
- For memory leak and high system resource usage, pprof profile is required. Long press "About" - "Version" to enable pprof HTTP server settings and kill and restart the app to take effect.
- Encrypt with [this GPG public key](https://github.com/dyhkwong.gpg) if the issue contains sensitive information or you are reporting a vulnerability.
- Because of the legacy codebase, feature requests are likely not accepted.

## Discussion

- Public [discussions](https://github.com/dyhkwong/Exclave/discussions) are always preferred because they can be viewed by everyone.
- Private [chat group](https://t.me/s/exclavian).

## Code contribution

- Create a [pull request](https://github.com/dyhkwong/Exclave/pulls) to contribute code. New features needs prior communications in the issue tracker, while bug fixes does not.

## License

    Copyright (C) 2023  dyhkwong
    Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

## Notice

Exclave is licensed under the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. However, Exclave optionally incorporates code covered by the GNU General Public License as published by the Free Software Foundation, version 3. If `github.com/dyhkwong/libsagernetcore` is compiled with `with_clash` tag, the GNU General Public License as published by the Free Software Foundation, version 3, applies to all of Exclave.

## Build from source

- Install and configure JDK 21, Go 1.26 and Go Mobile.
- Install and configure Android SDK Platform 36 and 37.0, Android SDK Build-Tools 37.0.0, Android SDK Platform-Tools and Android NDK r29 through Android Studio or Android SDK Command-line Tools.
- Replace `release.keystore` with your own. It can be generated with Java `keytool`.
- Create a new `local.properties` file if it does not exist. Append the following lines to `local.properties`.
```
    KEYSTORE_PASS=your_keystore_pass
    ALIAS_NAME=your_alias_name
    ALIAS_PASS=your_alias_pass
```

- Linux (x64) or macOS (x64/arm64):

  - Build libsagernetcore: `./run lib core` or `./library/core/build.sh`
  - Download assets: `./gradlew :app:downloadAssets`, or update assets to the latest version: `./gradlew :app:updateAssets`
  - Build Exclave: `./gradlew :app:assembleOssRelease`

- Windows (x64):

  - Build libsagernetcore: `./library/core/build.bat`
  - Download assets: `./gradlew.bat :app:downloadAssets`, or update assets to the latest version: `./gradlew.bat :app:updateAssets`
  - Build Exclave: `./gradlew.bat :app:assembleOssRelease`

- APK files are located in `./app/build/outputs/apk/oss/release`

## Acknowledgment

- [Shadowsocks](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet](https://github.com/SagerNet/SagerNet)
- [husi](https://github.com/xchacha20-poly1305/husi)
- Other forks of SagerNet
