**English** | [简体中文](CONTRIBUTING.zh-CN.md)

# Contributing

Thanks for your interest in contributing to TangSnow.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Language

The repository's outward-facing documents (this file, `README`, `SECURITY`, `CODE_OF_CONDUCT`,
`THIRD_PARTY_NOTICES`) are bilingual, with **English as the default**. This file has a
[Simplified Chinese version](CONTRIBUTING.zh-CN.md).

**Code comments, commit messages and the maintainer's internal notes are written in Chinese.** That
is the project's working language and is not a barrier we can remove cheaply, so please expect it.
Issues and pull requests may be written in **Chinese or English** — whichever you prefer; the
templates say so explicitly.

## Reporting issues

Please file them under [Issues](https://github.com/Tan-Sno/TangSnow/issues). The repository ships issue
templates — just fill in the fields, which mirror the checklist below:

> **Please do not report security vulnerabilities in a public issue.** Use the
> [private vulnerability report form](https://github.com/Tan-Sno/TangSnow/security/advisories/new)
> instead — see [SECURITY.md](SECURITY.md).

Please include as much of the following as you can:

- TangSnow version (Settings → About TangSnow)
- Device model and Android version
- Reproduction steps, plus the expected and actual results
- If rendering is involved, a URL that reproduces the problem
- Screenshots or a screen recording

## Feature requests

Also via Issues. Describe the scenario and the problem to be solved, not just the feature itself.
Proposals that add a permission, a network call or a third-party dependency must additionally explain
the privacy impact.

## Development environment

- **JDK 25** — the Gradle daemon pins this version through `gradle/gradle-daemon-jvm.properties` and
  downloads it automatically if it is missing. The produced bytecode targets Java 17.
- Android SDK `platforms;android-37.2` (API 37.2; minor-level SDKs are separate package ids)
- Android Studio 2026.1 or later (only for GUI development; command-line builds do not need it)

```bash
./gradlew :app:assembleCoreKotlinDebug        # build (one flavor; see the note below)
./gradlew :app:lintCoreKotlinDebug :app:lintCoreRustDebug            # static analysis, both flavors
./gradlew :app:testCoreKotlinDebugUnitTest :app:testCoreRustDebugUnitTest   # unit tests, both flavors
```

The `app` module has a `core` product-flavor dimension (`coreKotlin` — the shipped pure-Kotlin
engine; `coreRust` — the same logic behind a JNI bridge, still preview-only). With a flavor
dimension the unflavored names are **gone**: `:app:lintDebug` and `:app:testDebugUnitTest` now
fail as ambiguous task names, so always name the flavor — and run **both**, because checking only
one lets the other flavor's compile errors, lint findings and tests reach `main` unnoticed (that
is what CI does).

Building a release (`assembleCoreKotlinRelease`, or `bundleCoreKotlinRelease`) requires
`keystore.properties` at the repo root (not distributed with the repository); without usable
credentials release builds **fail fast** — three gates cover "file absent", "key missing or
empty" and "key still a `<<placeholder>>`". This prevents silently producing a debug-signed
artifact that only looks distributable. Android Studio's *Generate Signed Bundle / APK* wizard is
recognised and exempt (it injects its own credentials).
For local verification of the packaging pipeline only, pass
`-Ptangsnow.allowDebugSignedRelease` explicitly to skip the gate — the artifact still
uses the debug certificate and must not be distributed or published.
Release artifacts are one flavor at a time on purpose: both flavors derive from the same
`versionCode` base, so a mixed publication would silently swap the engine under a user. (Since A1
they are also **separate app identities** — `coreKotlin` is `io.github.tan_sno.tangsnow`,
`coreRust` is `io.github.tan_sno.tangsnow.rust` — so they can coexist on one device; the Rust
line ships under its own `v<ver>-rust-preview` tag — and that release must be marked
**pre-release**. The in-app updater reads `/releases/latest` and matches assets by flavor first, then by ABI
(since 2026-10-23 `UpdateChecker.pickAsset` filters on the `app-<flavor>-` prefix), so a Rust asset is
never offered to the stable line. The `-rust-preview` tag + pre-release discipline is kept so the
two implementation lines stay visibly separate in the releases list.)

Make sure all of the commands above pass before submitting, and that lint stays at **zero issues**.
Note that `checkAllWarnings` is enabled (`app/build.gradle.kts`), so **warnings are reported too** —
please do not silence a whole category of checks to get rid of one warning; if a suppression is
genuinely warranted, follow the existing style and state the reason for each one.

The `coreRust` line has Rust-side commands of its own (run them inside `tangsnow-rs/`):

```bash
cargo test --offline                                     # pure logic, on the host
cargo check --offline --features jni -p tangsnow-core    # required after touching src/jni.rs
```

The second one is not redundant: `lib.rs` hides `pub mod jni` behind `#[cfg(feature = "jni")]`, so
**a green `cargo test` does not prove that those 34 JNI exports compile** (parameter types and the
shape of `JClass` all live in that layer). And it still only covers the type level — JNI matches by
**name and descriptor at runtime**, which only a rebuilt `.so` on a device can prove
(`BridgeSignatureParityTest` compares symbol names and the *number* of parameters, not their types).

## Code conventions

- Language and UI framework: Kotlin + the Android View system (no Compose)
- **Colors** always use semantic names (`@color/…` or `?attr/colorXxx`) — never hard-code a color value
- **Dimensions** (dp/sp) move into `values/dimens.xml` only when the same semantic value repeats in
  several places or has already drifted; a value used once stays in its layout. The same number often
  means different things in different places (a 40dp touch target versus a 40dp margin), and merging
  them only creates false coupling. See the comment at the top of `dimens.xml` for the criteria and
  existing examples
- User-visible strings must be maintained in both Chinese (`values/`) and English (`values-en/`), with
  matching key sets
- New network requests must state their purpose; this project follows a "data never leaves the device"
  principle and does not accept telemetry, analytics or advertising dependencies
- `docs/PRIVACY.md` and `docs/TERMS.md` are exported from `strings.xml` by `tools/export_legal_docs.py`.
  **Do not edit those two files directly**; after changing legal text, re-export them and bump
  `POLICY_VERSION` if the change is substantive
- **Formatting is decided by tooling, not by people**: `.editorconfig` (LF, final newline, trailing
  whitespace stripped — with `.md` exempt because there two trailing spaces mean a line break),
  `.gitattributes` (`* text=auto eol=lf`, CRLF only for `.bat`/`.cmd`, binaries listed) and the
  committed `.idea/codeStyles` are the single source of truth. Indentation is 4 spaces for
  `kt`/`kts`/`py`/`yml`/`toml`/`rs` and 2 for `js`/`ts`/`json`. **Please do not reformat anything
  beyond your actual change** — it buries the diff that matters
- **The Rust side is not rustfmt-clean, on purpose**: please do not run `cargo fmt`. The `.rs` files
  in this repo have never been laid out by rustfmt (measured 2026-10-18: `cargo fmt --check` reports
  114 diffs across 19 files, most unrelated to any recent change — that number moves, so don't treat
  it as a baseline). If a reformat is genuinely wanted, make it its own commit, never part of a fix

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <short description>

[optional body: why this change is needed]
```

Common `type` values: `feat`, `fix`, `perf`, `refactor`, `docs`, `build`, `test`, `chore`.

Keep the description line short and in the imperative mood. The body explains *why* only — the diff
already shows what changed.

## Submitting a pull request

1. One PR solves one problem, which makes review and rollback easier
2. Explain what changed, why, and how you verified it
3. Attach screenshots for any UI or interaction change
4. If the PR fixes an issue, reference that issue number in the description

## License

By contributing you agree to license your contribution under the [Apache License 2.0](LICENSE).
