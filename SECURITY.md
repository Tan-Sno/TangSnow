**English** | [简体中文](SECURITY.zh-CN.md)

# Security Policy

## Supported versions

Security updates are provided for the latest release only. If you are on an older version, please upgrade to the latest release first and check whether the issue still occurs.

| Version | Security updates |
|---|---|
| 2.1.x | ✅ |
| < 2.1 | ❌ |

> **Note for 2.0.x users:** since 2.1.0 the application ID (`applicationId`) changed from
> `com.tangsnow.tangsnow` to `io.github.tan_sno.tangsnow`, so **2.1.x cannot be installed over 2.0.x**.
> The two versions can co-exist: install the new one, confirm it works, then uninstall the old one.
> Bookmarks, history and settings are not migrated automatically.

## Reporting a vulnerability

**Please do not report security vulnerabilities through a public issue.** Public details put every user at risk until a fix is released. Use the private channel below instead.

Submit through GitHub's private vulnerability reporting form:

<https://github.com/Tan-Sno/TangSnow/security/advisories/new>

That form is visible to the maintainer only. If you cannot use it, you can also contact the maintainer through their GitHub profile <https://github.com/Tan-Sno>, stating in your very first message that it is a security report.

Please include as much of the following as you can:

- Affected version (Settings → About TangSnow)
- Device model and Android version
- Reproduction steps, or a minimal reproducible example
- Impact assessment: what an attacker could do (for example read on-device data, bypass a privacy protection, execute code remotely)
- Proof-of-concept code or a screen recording, if you have one

## Process

- **Acknowledgement**: we reply within 7 days of receiving a report, confirming whether we accept it.
- **Initial assessment**: we share our preliminary judgement (whether it holds, the affected scope, the severity) and say whether we need more information.
- **Fix and release**: confirmed issues are fixed and shipped in a new release, described in
  [Releases](https://github.com/Tan-Sno/TangSnow/releases).
- **Credit**: we credit you in the release notes if you would like to be named; you may also stay anonymous.
- **Not accepted**: if we conclude it is not a security issue, we explain our reasoning.

## Scope

To avoid misunderstandings, the following are **not** security vulnerabilities under this policy:

- **Vulnerabilities in third-party components** (GeckoView, OkHttp, AndroidX and so on) — please report those to the upstream project. We follow up with upgrades as soon as the upstream fix ships.
- Issues that require the device to be **rooted, debugging-enabled, or already compromised** by an attacker with local code execution.
- **Privacy behaviours this app deliberately chooses**, such as collecting no telemetry, disabling the engine's remote Safe Browsing lookups, or bundling no ad-blocking extension. These are product trade-offs; see the [README](README.md) and the [privacy policy](docs/PRIVACY.md).

## User agreement and privacy

- Privacy policy: [docs/PRIVACY.md](docs/PRIVACY.md)
- User agreement: [docs/TERMS.md](docs/TERMS.md)
- Code of conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
