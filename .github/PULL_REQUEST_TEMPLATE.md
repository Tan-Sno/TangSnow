## What this PR does

<!-- One sentence on the problem it solves. Reference an issue with "Closes #123" if there is one. -->

## Why this way

<!-- Explain the trade-offs and the reasoning. If you tried other approaches, briefly say why they were dropped. -->

## How it was verified

<!-- Reproduction/verification steps, or why manual verification is impractical. Attach screenshots for UI changes. -->

Feel free to write in Chinese if that is easier for you.

## Checklist

- [ ] One PR solves one problem
- [ ] `./gradlew :app:assembleDebug` passes
- [ ] `./gradlew :app:lintDebug` passes with no new issues
- [ ] `./gradlew :app:testDebugUnitTest` passes
- [ ] User-visible strings are maintained in both Chinese (`values/`) and English (`values-en/`), with matching key sets
- [ ] No telemetry, analytics or advertising dependency was introduced
- [ ] Any new network call or permission is explained in the description
- [ ] `docs/PRIVACY.md` and `docs/TERMS.md` were not edited directly (both are exported from `strings.xml` by a script)
- [ ] No credentials or machine-specific content was committed (`keystore.properties`, signing files, passwords, absolute paths)
