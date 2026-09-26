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
- Android SDK `platforms;android-37` (API 37, minor level 2)
- Android Studio 2026.1 or later (only for GUI development; command-line builds do not need it)

```bash
./gradlew :app:assembleDebug      # build
./gradlew :app:lintDebug          # static analysis
./gradlew :app:testDebugUnitTest  # unit tests
```

Make sure all three pass before submitting, and that lint stays at **zero issues**.
Note that `checkAllWarnings` is enabled (`app/build.gradle.kts`), so **warnings are reported too** —
please do not silence a whole category of checks to get rid of one warning; if a suppression is
genuinely warranted, follow the existing style and state the reason for each one.

## Code conventions

- Language and UI framework: Kotlin + the Android View system (no Compose)
- **Colours** always use semantic names (`@color/…` or `?attr/colorXxx`) — never hard-code a colour value
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
