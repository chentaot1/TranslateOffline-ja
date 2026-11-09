# Contributing to TranslateOffline-ja

Thanks for helping. TranslateOffline-ja is an independent, 100% offline manga translator for Android. Opening a short issue before submitting a major change saves everyone time.

## Ways to Help

- **Report a bug** with the [bug report form](https://github.com/chentaot1/TranslateOffline-ja/issues/new?template=bug_report.yml). TranslateOffline runs every ML model on-device, so your device model, Android version, and RAM matter most.
- **Suggest a feature** with the [feature request form](https://github.com/chentaot1/TranslateOffline-ja/issues/new?template=feature_request.yml).
- **Report a security vulnerability** privately through [GitHub Security Advisories](https://github.com/chentaot1/TranslateOffline-ja/security/advisories/new).

## Building from Source

TranslateOffline-ja uses JDK 17 and Android SDK 34–36. Follow the instructions in [Building from Source](README.md#-building-from-source).

Clone the repository with submodules:
```bash
git clone --recursive https://github.com/chentaot1/TranslateOffline-ja.git
```

## Making a Change

1. **Start from an issue.** Open one, or comment on an existing one, describing the behavior and how you will know it works.
2. **Branch** as `feat/<issue>-<short-name>` or `fix/<issue>-<short-name>`.
3. **Write the failing test first**, then make it pass.
4. **Commit** with [Conventional Commits](https://www.conventionalcommits.org/), for example: `fix(pipeline): retain dialogue order across multi-tier pages`.
5. **Open a PR** referencing the issue.

Before pushing, verify the unit tests pass:
```bash
./gradlew :pipeline:testDebugUnitTest :offline:testJapaneseDebugUnitTest :ml:testDebugUnitTest :core:test
```

## Privacy & Offline Invariant

TranslateOffline-ja enforces a **zero-network invariant** (`android.permission.INTERNET = 0`). Pull requests that introduce network calls, cloud dependencies, external telemetry, or disk caching of screen captures will not be accepted.

## License

TranslateOffline-ja is licensed under [GNU AGPLv3](LICENSE). By contributing, you agree that your contribution is licensed the same way.
