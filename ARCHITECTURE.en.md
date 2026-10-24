# Architecture Overview · TangSnow

> Written for **whoever picks this project up next**: structure, data flow, and design trade-offs.
> Division of labour with `README` — README explains **usage** (install, use), this explains **internals**.
> Only **verified** facts are stated (source reads / `settings.gradle.kts` / `AndroidManifest.xml` / gate
> scripts); anything unverified is marked explicitly rather than guessed.
> Chinese version: [`ARCHITECTURE.md`](ARCHITECTURE.md).
> **Please update this document whenever flavor / seam / gate numbering changes.**

---

## 1. One-line positioning

**A privacy-first Android browser with fully local data.** Rendering uses Mozilla GeckoView (158);
business logic ships as either pure Kotlin or Rust (JNI bridge), selected by build flavor.

## 2. Repository layout

| Path | What it is |
|---|---|
| `app/` | the Android app (Kotlin, `minSdk 26` / `targetSdk 37`) |
| `tangsnow-rs/` | Rust core sources (**not part of the Android build**; artifacts enter the APK via the flavor's `jniLibs/`) |
| `tools/` | release gate scripts (`verify_release.py`, `check_zip_padding.py`) |
| `.github/workflows/` | CI (both-flavor unit tests + lint + gate self-test) |
| `gradle/` | version catalog (`libs.versions.toml`) and pinned daemon JVM |

**Credentials are never committed**: signing config lives in `keystore.properties` (gitignored), and every
release task fails outright when credentials are missing (see §10, the three signing gates).

## 3. Runtime layers

```
┌───────────────────────────────────────────────┐
│ UI layer        Activity / Fragment / Controller│  plain Android View (no Compose)
│   ConsentActivity · MainActivity · Settings…   │
├───────────────────────────────────────────────┤
│ Browser layer   browser/ + GeckoHolder         │  the ONLY place that touches GeckoView
│   SessionManager · Tab · delegate interfaces   │
├───────────────────────────────────────────────┤
│ Data layer      data/ + data/repo/             │  local persistence (Room + SharedPreferences)
│   PreferenceStore · BrowserDb · SessionStore    │
│   BookmarkRepo · HistoryRepo · DownloadRepo     │
├───────────────────────────────────────────────┤
│ Utility layer   util/                          │  stateless pure logic (URL/bitmap/redaction/legal text)
├───────────────────────────────────────────────┤
│ Extensions/update  extension/ · update/        │
├───────────────────────────────────────────────┤
│ Engine layer    GeckoView 158 (libxul.so, separate process)│ Mozilla MPL 2.0
└───────────────────────────────────────────────┘
        ↑ the `core` flavor decides the IMPLEMENTATION LANGUAGE of business logic (Kotlin / Rust-JNI)
```

**Dependencies point one way, downward**: UI → browser → data/util. The engine layer is owned exclusively
by the browser layer; no other layer references GeckoView types.

## 4. Packages and responsibilities (57 Kotlin files / 8 packages)

| Package | Files | Responsibility | Representative classes |
|---|---|---|---|
| `tangsnow` (root) | 17 | Activities + top-level controllers | `MainActivity`(3085 lines), `SettingsFragment`, `SuggestionsController`, `FindBarController`, `TangSnowApplication` |
| `tangsnow.browser` | 1 | **The single browser-layer file**: session/tab/permission/content delegates | `SessionManager`(2038 lines; `TabEvents`/`PromptHandler`/`PermissionHandler`/`SelectionHandler`) |
| `tangsnow.data` | 12 | Persistence and global state | `PreferenceStore`, `BrowserDb`, `SessionStore`, `Theme`, `SearchEngines`, `LegalDocs`, `AppHttp` |
| `tangsnow.data.repo` | 4 | Three repositories + app scope | `BookmarkRepo`, `HistoryRepo`, `DownloadRepo`, `ApplicationScope` |
| `tangsnow.util` | 11 | Stateless utilities | `UrlUtils`, `Bitmaps`, `CrashLogger`, `GeckoResultExt`, `SecureScreen` |
| `tangsnow.ui` | 8 | Reusable UI fragments | `Adapters`, `SheetBuilders`, `WebPrompts`, `HomeTiles`, `ScanImageActivity` |
| `tangsnow.extension` | 3 | AMO extension catalog and installation | `ExtensionCatalog`, `ExtInstallCoordinator`, `ExtensionPrompts` |
| `tangsnow.update` | 1 | In-app update check (GitHub Releases) | `UpdateChecker` |

> "root 17 / browser 1" *is* the design stance: **every engine interaction is collected in one
> `SessionManager`** rather than scattered across Activities — which is exactly what makes it pinnable by
> unit/meta tests.

## 5. Engine integration (GeckoView)

- **Sole owners**: `GeckoHolder` (runtime/global settings) + `SessionManager` (one `GeckoSession` per tab).
- **Session-level settings** (`SessionManager.buildSessionSettings`, applied once at creation):
  `usePrivateMode` / `useTrackingProtection` / `allowJavascript` / `userAgentMode` / `viewportMode` / `suspendMediaWhenInactive`.
- **Hot-changeable**: only `allowJavascript` + engine-level `fontSizeFactor`; UA / viewport / tracking-protection
  tier are **deliberately not** hot-changed (mid-flight changes produce garbled input or half-applied state —
  switching rebuilds the session instead).
- **Runtime-level settings are configured in exactly one place** (`SessionManager.runtime()`): content-blocking
  tier, the GPC signal, and fingerprinting protection; the remaining 40-odd options use GeckoView defaults.
- **Safe-browsing remote lookups are disabled** (that capability requires talking to Mozilla/Google — see the
  comment inside `buildContentBlocking`); `libxul.so` is deflate-compressed (`useLegacyPackaging`, matching release gate ⑩) and split per ABI.

## 6. The `core` flavor and the JNI seam (**wired**: W1's 7 bridges + W2's 11 = 18)

The app has a single product-flavor dimension: `core`.

| flavor | Status | Artifacts |
|---|---|---|
| `coreKotlin` | **the actual release channel** (pure Kotlin) | logic lives in `src/main` (e.g. `UrlUtils`, `BookmarkHtml`) |
| `coreRust` | **preview** (same logic via JNI) | `src/coreRust/`: `CoreNative.kt` + `CoreBridge.kt` + `jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libtangsnow_core.so` |

**Verified facts (contrary to a common assumption)**:
- `coreKotlin` has **no** dedicated source set (its logic is just `src/main`); `coreRust` **only adds**
  2 Kotlin files + 3 `.so` files.
- **Nothing in `src/main` references** `CoreNative`/`CoreBridge` ⇒ the seam is a **compile-time
  dispatch, not a runtime `if`**: each flavor provides one `internal object CoreImpl` of the **same
  name and signature**, call sites in `src/main` **change by not a single character** (they keep
  calling facades like `UrlUtils.resolveInfo`), and the **compiler** is the gate (a missing flavor
  is a compile error — no interface, no DI, no runtime branch).
- Where `CoreImpl` lives: `app/src/coreKotlin/java/io/github/tan_sno/tangsnow/core/CoreImpl.kt` and
  `app/src/coreRust/java/io/github/tan_sno/tangsnow/core/CoreImpl.kt`.
- **18 bridges are wired**: W1's seven single-value string functions (`resolveInfo` / `looksLikeUri` /
  `schemeOf` / `isPortableUrl` / `percentEncode` / `searchUrl` / `displayForUrl`) plus W2's eleven with
  on-disk / privacy / round-trip semantics (4 download-filename, 2 crash-log, 3 bookmark-JSON,
  1 history filter, 1 legal-bold ranges).

**`guard` contract (`CoreNative`)**: `available` is evaluated eagerly at class init
(`loadLibrary` failure ⇒ false, never throws); when `available=false` every `*Impl` returns a safe default and
never touches native; callers use `nullIsResult` to distinguish "legitimately empty" from "failed".
The `CoreImpl` layer **asks `available` first** and returns `null` (= does not take over) when it is
false ⇒ on the JVM, coreRust and coreKotlin behave **byte for byte alike** — which is what lets the
shared unit tests run under both flavors.

## 7. Rust core (`tangsnow-rs/`)

- **Role**: a source archive that does **not** take part in the Android build; its artifacts enter the flavor
  only via `app/src/coreRust/jniLibs/`.
- **13 modules**: `url_utils` `percent_encode` `search_engines` `download_filename` `bookmark_html` `history`
  `legal_text` `bitmaps` `dt_prompts` `crash_logger` `update_logic` + `jni` (export layer) + `lib`.
- **Exports**: 34 `Java_io_github_tan_1sno_tangsnow_core_CoreNative_*` symbols (3 are known orphans; any new
  one outside the whitelist turns the gate red).
- **Tests**: 15 `tests/*.rs` (14 test binaries + the shared reader `common/mod.rs`), including per-module
  `*_parity.rs` (Kotlin↔Rust behavior alignment), `jni_boundary_parity`, `properties_fuzz`.
  `cargo test --offline` = **169 cases**. **Ten pairs** now read a **JSON fixture shared by both sides**
  (`tests/fixtures/`, the Kotlin side reads the very same files ⇒ changing a fixture turns both sides
  red); the rest are deliberately **one-sided** cases (source-level sentinels, properties, regression
  batches) — see `tangsnow-rs/README.md`.
- Kotlin↔Rust equivalence is guarded by meta tests: `CrossLanguageLiteralParityTest`,
  `PercentEncodeSetParityTest`, `BridgeSignatureParityTest`.

## 8. Data and privacy boundary

- **Fully local**: bookmarks/history/download records/home customization/site-permission decisions all live on
  device (Room + SharedPreferences); no account, no analytics, no tracking SDK.
- **Exactly three outbound endpoints** (each single-purpose):
  `addons.mozilla.org` (extension catalog/metadata/xpi), `firefox.settings.services.mozilla.com`
  (on-device update of the tracking-protection list), `api.github.com` (**only** when the user taps
  "check for updates" — never in the background).
- **Cleartext prohibition**: `res/xml/network_security_config.xml` has `cleartextTrafficPermitted="false"` —
  ⚠️ this binds the app's **own OkHttp only** and has **no effect on GeckoView** (the engine ships its own
  network stack and must be able to open http sites).
- **DNS is plaintext** (no TRR/DoH configured): DoH would add an outbound endpoint, conflicting with the
  "exactly three endpoints" promise, so it is deliberately unused.
- **Crash logs** are written locally only, viewable and deletable; `CrashLogger` runs **three redaction
  layers** before writing: schemeless forms (`mailto:` / `data:` etc., which previously wrote a full
  e-mail address or embedded content to disk), credentials that do not form a URL
  (`token=…` / `password: …` / `Bearer …`), and `scheme://…` URLs collapsed to `scheme://host[:port]/…`
  (userinfo stripped); **bare paths are deliberately kept** — they are not URLs and are more valuable
  for triage (see the workspace's `../notes-tangsnow/RESEARCH-2026-10-19-crashlogger-redaction-audit.md` — **outside the repo**, which carries code only).

## 9. Security surface

| Surface | Fact |
|---|---|
| Permissions | only `INTERNET` / `CAMERA` (on demand) / `ACCESS_LOCAL_NETWORK` (API 37+, on demand) |
| `exported=true` | only `ConsentActivity` (LAUNCHER) and `MainActivity` (http/https + SEND text/plain) |
| GeckoView clipboard provider | added in 157, **removed in 158** (exported surface 4 → 3); the merge rule in `AndroidManifest.xml` keeps the `DUMP` guard as defence |
| FileProvider | `exported=false`; `file_paths.xml` exposes a single `Download/` location (`DownloadRepo.externalizableUri`) |
| WebView attack surface | **no WebView** (GeckoView instead) ⇒ zero `addJavascriptInterface` hits |
| Update-chain validation | `UpdateChecker.isTrustedApkTarget`: rejects backslashes + strict `host==github.com` (so `github.com.evil` cannot slip through) |

## 10. Build and release chain

- **JDK 25** (the daemon is pinned by `gradle/gradle-daemon-jvm.properties`), bytecode target Java 17;
  Android SDK API 37.
- **Packaging**: `packaging{ jniLibs{ useLegacyPackaging=true } }`; 3-way ABI split (no universal APK);
  locale filter en/zh/zh-CN/zh-HK/zh-TW.
  > `useLegacyPackaging=true` is a **required fix**: otherwise AGP writes 16K-alignment padding into the `.so`
  > local-header extra, and the installer's `libziparchive` rejects the APK (res=-113, "problem parsing the
  > package") while gates ①–⑨ all stay green.
- **Task names must carry a flavor** (the `core` dimension made bare `lintDebug`/`assembleRelease` ambiguous) —
  **both flavors must be run**.
- **Three signing gates** (`app/build.gradle.kts`, fail-closed): missing file / missing or empty key /
  key still a placeholder ⇒ any release task **fails immediately**; it never silently produces a debug-signed
  package.
- **Release gate** `tools/verify_release.py` — **12 checks** (clean tree / version declarations /
  package name+version+ABI / permission baseline / certificate fingerprint + v2 / freshness / dependency
  digests / `libxul.so` build id / `exported` whitelist / `lib/*.so` must be deflated / own `.so`
  `p_align>=16384` / **no build-machine path or identity inside the artifacts** — the APK is what
  you publish, so an absolute build path or username inside it is a disclosure; only *our* bytes
  are scanned (GeckoView's own `.so` files carry Mozilla's build paths, which are not ours to fix));
  `--selftest` has 25 cases (it does not replace a real package).
- **CI** `.github/workflows/ci.yml`: both-flavor unit tests + both-flavor lint + gate self-test;
  it **deliberately** does not run `assembleRelease` and does not reference secrets.

## 11. Test system

| Layer | Location | Size |
|---|---|---|
| Shared unit tests | `app/src/test` (45 files) | **252** (coreKotlin) / **259** (coreRust) |
| Flavor-specific unit tests | `app/src/testCoreRust` (2 files / 7 cases) | runs only in the coreRust compilation unit |
| Rust unit tests | `tangsnow-rs/tangsnow-core/tests` (15 `.rs` files + 10 shared fixtures) | **169** |
| Gate self-test | `verify_release.py --selftest` | 25 |
| **Instrumented tests** | `app/src/androidTestCoreRust` (2 files / 12 cases) | ⚠️ **coreRust flavor only** (coreKotlin has none) |

**Meta-test pins** (changing source obliges changing these): `TestCountCommentTest` (pins the count comment in
build.gradle), `ReleaseGateConsistencyTest` (pins byte-identical `dist/jniLibs` + five invariants of
`build-android.sh`), `BridgeSignatureParityTest` (pins Kotlin `external` ↔ Rust exports, **including
artifact-level ELF symbol checks**), `PolicyConsistencyTest`, `ProguardLogRuleConsistencyTest`,
`TouchTargetSentinelTest`, `KdocAdjacencyTest`.

**Build notes**: unit tests must be run with `--rerun-tasks` (otherwise `FROM-CACHE` is a false green);
lint `checkAllWarnings=true` is a hard gate.

## 12. Current state and known limits

1. **Instrumented tests cover coreRust only**: `app/src/androidTestCoreRust` holds 12 cases
   (W2's nine bridges, W1's two, plus a real round-trip), but **coreKotlin — the line that
   actually ships — has none**, so UI/on-device behavior still has no automated coverage.
   Those 12 cases also **have not run on this machine**: the 16KB AVD boots, but the APK
   carrying GeckoView's native libraries does not install (`adb install` times out, then the
   shell stops answering) ⇒ a device that can install that package is required.
2. **The packaged `.so` must be rebuilt whenever the Rust sources change**: the shipped `.so`
   and `tangsnow-rs/` are **two sources of truth** (`ReleaseGateConsistencyTest` only checks that
   `dist/` and the flavor copy are byte-identical). Change Rust without rebuilding ⇒ the
   published behavior is still the old one. Rebuilt for all three ABIs with the local NDK r30
   on 2026-10-21 (`p_align` = `0x4000` throughout).
3. **The two flavors are two independent app identities**: `coreKotlin` =
   `io.github.tan_sno.tangsnow`, `coreRust` = `io.github.tan_sno.tangsnow.rust`
   (`applicationIdSuffix = ".rust"`) ⇒ they can coexist on one device with isolated data and
   independent upgrades; but they still **derive from the same versionCode base** (42 ⇒
   421/422/423 per ABI in this version), and the release discipline stays **one flavor at a
   time** (the gate rejects the other flavor's APKs as "unexpected artifacts").

## 13. Sources

- Code: `app/src/**`, `tangsnow-rs/**`, `settings.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`
- Gates and CI: `tools/verify_release.py`, `tools/check_zip_padding.py`, `.github/workflows/ci.yml`
- Prose: `README.md`, `CONTRIBUTING.md`

> Every checkable claim above can be independently reproduced from these files and from git history;
> please keep this document in sync when structure (flavor / seam / gate numbering) changes.