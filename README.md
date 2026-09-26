**English** | [简体中文](README.zh-CN.md)

# TangSnow

A privacy-first Android browser that keeps your data on your device. Built on Mozilla's GeckoView engine.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Engine: GeckoView (MPL 2.0)](https://img.shields.io/badge/Engine-GeckoView%20%C2%B7%20MPL%202.0-orange.svg)](THIRD_PARTY_NOTICES.md)

The app interface is available in English and Simplified Chinese.

## Features

- **Everything stays on your device** — bookmarks, history, download records, home-screen customization and remembered site-permission decisions are stored locally and never uploaded.
- **No account, no analytics, no ads, no tracking SDKs.**
- **Privacy protections** — tracking protection (Standard / Strict / Custom / Off), cross-site cookie isolation, bounce-tracking protection, fingerprinting protection, tracking-parameter stripping, Global Privacy Control (GPC), private browsing.
- **You stay in control** — site-permission prompts offer a "remember my choice" checkbox (regular sessions only; private sessions never persist a permission decision). An optional "clear browsing data on exit" wipes cookies and site data, cache, history and the tab snapshot in one go; bookmarks and download records are unaffected.
- **Minimal permissions** — only Network and Camera (requested on demand, when you scan a QR code). On Android 17 and later, Nearby devices (`ACCESS_LOCAL_NETWORK`) is also needed: it is requested only when you open a local network address, so you can reach devices such as your router, NAS or printer.
- **Mozilla engine** — GeckoView 155, the same Gecko engine that powers Firefox.
- **Extensions** — browse Mozilla's official add-on directory (AMO) and install officially signed `.xpi` packages.
- **Bookmarks you can take with you** — import and export in the Netscape bookmark format, so switching browsers does not mean starting over.
- **Everyday browsing** — tab grid (private tabs are badged), picture-in-picture, find in page, save as PDF, print, QR scanning, custom search engines, IDN domains.
- **Fits into the system** — can be set as your default browser, and accepts links shared from other apps.
- **Library** — history, bookmarks and downloads in one place, searchable by title and URL.
- **Interface** — English and Simplified Chinese; light, dark or follow-system theme.

## Screenshots

<sub>Screenshots show the Simplified Chinese interface.</sub>

<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/home.jpg" width="180" alt="Home: address bar and search entry, with the browsing toolbar at the bottom and the private-mode switch on the right"><br>
      <sub>Home</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/extensions.jpg" width="180" alt="Extension catalogue: installable official Mozilla extensions with their privacy notes"><br>
      <sub>Extension catalogue (official Mozilla source)</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/settings-tracking-protection.jpg" width="180" alt="Settings: custom tracking protection, with per-category switches for trackers, fingerprinting, cryptominers and more"><br>
      <sub>Tracking protection</sub>
    </td>
  </tr>
</table>

## Download

Get the APK from **[Releases](https://github.com/Tan-Sno/TangSnow/releases/latest)**.

| File | Device |
|---|---|
| `app-arm64-v8a-release.apk` | Modern phones — the vast majority of devices (**recommended**) |
| `app-armeabi-v7a-release.apk` | Older 32-bit devices |
| `app-x86_64-release.apk` | Emulators and the few x86 devices |

The three files are identical apart from the CPU architecture. If you are unsure, take `arm64-v8a`.

Requires Android 8.0 (API 26) or later. Allow "install unknown apps" in your system settings before installing.

### Upgrading from 2.0.x?

**The application ID changed in 2.1.0** (`com.tangsnow.tangsnow` → `io.github.tan_sno.tangsnow`), so the new version **cannot be installed over** the old one — the system treats it as a different app.

You do not have to uninstall first: the two versions can **co-exist**. Install the new one, confirm everything works, then uninstall the old one whenever you like. Note that bookmarks, history and settings are **not migrated** and need to be set up again in the new version.

Each release page documents that version's changes and installation requirements.

## Privacy

- No account and no cloud account; uninstalling the app deletes your data.
- Apart from the sites you visit, the app contacts only three external services, each for a single purpose:
  - `addons.mozilla.org` — the extension catalogue, metadata and signed packages (official Mozilla source).
  - Mozilla's official remote settings service (`firefox.settings.services.mozilla.com`) — updates the tracking-protection lists on your device.
  - `api.github.com` — **only when you tap "Settings → Check for updates"** — reads public version information (version number and release notes). That request reads public data only, carries no device identifier, account or browsing record, and is **never issued automatically in the background**.
- The engine's remote "Safe Browsing" lookups (phishing / malware) are **switched off**, so the sites you visit are never sent to a third party.
- Crash logs are written on this device only, can be viewed or deleted inside the app, and are never uploaded.
- Permissions: `INTERNET`, `CAMERA` (on demand) and `ACCESS_LOCAL_NETWORK` (Android 17 and later, requested only when you open a local network address), plus three ordinary permissions declared by the rendering engine (network state, wake lock, audio settings). You can verify them under "App info → Permissions" in system settings.
- One further entry, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, is an app-signed permission generated automatically by AndroidX. It stops other apps from invoking this app's internal dynamic broadcast receivers. It grants access to no data and does not appear in the system's grantable-permissions list. Tools such as `aapt dump permissions` will show it — noted here for completeness.

The full text is in the [privacy policy](docs/PRIVACY.md) and the [user agreement](docs/TERMS.md); both carry the Chinese and English versions on one page.

## Known limitations

- The curated extension catalogue lists privacy and utility extensions only. No ad-blocking extension is bundled or recommended.

## Building from source

Requirements:

- **JDK 25** — the Gradle daemon pins this version through `gradle/gradle-daemon-jvm.properties` and downloads it automatically if it is missing. The produced bytecode targets Java 17.
- Android SDK `platforms;android-37` (API 37, minor level 2)

```bash
./gradlew :app:assembleDebug      # build the debug APK
./gradlew :app:lintDebug          # static analysis
./gradlew :app:testDebugUnitTest  # unit tests
```

Use `:app:assembleRelease` for a release build. The GeckoView native libraries (`libxul.so` and friends, stored uncompressed) make up roughly 86% of the APK, so builds are split per ABI (`arm64-v8a` / `armeabi-v7a` / `x86_64`) and no universal APK is produced; each split gets a distinct `versionCode`, so you can switch architectures on the same device. Signing credentials are not kept in the repository — they come from a local, untracked `keystore.properties` (without that file, a release build falls back to the debug certificate and prints a warning; such artifacts must not be distributed).

## Contributing

Issues and pull requests are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

Please do **not** report vulnerabilities in a public issue. Use the
[private vulnerability report form](https://github.com/Tan-Sno/TangSnow/security/advisories/new) instead.
See [SECURITY.md](SECURITY.md) for the supported versions and the process.

## License

The application's own code is licensed under the [Apache License 2.0](LICENSE).

The rendering engine, Mozilla GeckoView, is licensed under the Mozilla Public License 2.0. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full list of third-party components, versions, copyright and trademark notices — it also contains the source-availability route required by MPL 2.0 §3.2.

TangSnow is an original brand. It is **not a Mozilla product and is not affiliated with Firefox**.
