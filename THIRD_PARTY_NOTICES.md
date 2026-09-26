**English** | [简体中文](THIRD_PARTY_NOTICES.zh-CN.md)

# Third-Party Notices

This file lists the third-party open-source components, icon assets and related notices redistributed
in the source and the built packages of TangSnow.
The application's own code is licensed under the Apache License 2.0 (see the root `LICENSE`); this file
covers third-party content only and does not modify the LICENSE.

## Open-source components (runtime dependencies)

| Component | Version | License | Purpose |
|---|---|---|---|
| Mozilla GeckoView | 155.0.20260903215306 | Mozilla Public License 2.0 | Web rendering engine (based on Gecko) |
| OkHttp | 5.5.0 | Apache License 2.0 | HTTP requests for the extension catalogue, metadata and in-app update checks |
| ZXing core | 3.5.4 | Apache License 2.0 | QR code decoding |
| ZXing Android Embedded | 4.3.0 | Apache License 2.0 | Scanner viewfinder and camera-permission handling |
| AndroidX (activity / appcompat / core / recyclerview / preference, etc.) | see version catalog | Apache License 2.0 | UI and system components |
| Google Material Components (Material 3) | 1.14.0 | Apache License 2.0 | UI components and theming |
| Kotlin / kotlinx-coroutines | — | Apache License 2.0 | Language and coroutines |

- GeckoView is a Mozilla open-source project. Full MPL 2.0 text: https://www.mozilla.org/en-US/MPL/2.0/

### MPL 2.0 §3.2 — source availability when distributing in executable form

This application is distributed in executable form (APK) and includes **GeckoView**, which is covered
by MPL 2.0. Under section 3.2 of the MPL 2.0, anyone distributing an executable form must also inform
recipients **how to obtain the corresponding Source Code Form** (at no more than the cost of
distribution):

- **GeckoView source**: <https://github.com/mozilla/gecko-dev> — or the
  [Maven repository](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/) published by
  Mozilla, which carries the exact artifact for the version this application uses,
  `155.0.20260903215306`.
- This application has **not modified** GeckoView's source (it only calls its public APIs as a
  dependency), so no Modifications exist in the sense of MPL section 3.1. The application's own code
  is a Larger Work under the Apache License 2.0 and is not subject to the MPL 2.0 copyleft terms.
- The licence and copyright notices inside GeckoView's source (MPL 2.0 §3.4) have not been removed or
  altered.
- Full Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0

## Icons and image assets

- Some interface vector icons are based on Google **Material Icons** (Apache License 2.0,
  https://fonts.google.com/icons / https://github.com/google/material-design-icons); copyright
  remains with Google.
- The application icon and home-screen artwork are original TangSnow designs.
- `res/drawable-nodpi/ic_engine_*.png`: official icons of the search engines (Baidu, Bing, Sogou,
  360, Shenma and others). They identify the services they belong to and nothing more; their copyright
  rests with their respective owners, and their use implies no endorsement or affiliation.
- `res/drawable-nodpi/ic_ext_*.png`: official icons of browser extensions (Dark Reader, Tampermonkey,
  Bitwarden, Stylus, Simple Translate and others; Privacy Badger and ClearURLs have no bundled official
  icon and fall back to a letter avatar). They identify entries in the extension catalogue only; their
  copyright rests with the respective authors or projects, and their use implies no endorsement or
  affiliation.

## Trademarks

- "棠雪" and "TangSnow" are the original brand of this project and are unrelated to any third party.
- This project is built on the Mozilla GeckoView engine but is **not a Mozilla product and is not
  affiliated with Firefox**. "Firefox", "Mozilla" and their logos are trademarks of the Mozilla
  Foundation, referenced here solely to describe the engine's origin.
- The names and icons of the search engines and extensions are the trademarks of their respective
  owners, referenced here for identification only.
