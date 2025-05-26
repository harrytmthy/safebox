# Contributing to SafeBox

Thank you for considering contributing! SafeBox welcomes improvements, bug fixes, documentation updates, and feature ideas, especially from Android developers who care about performance and clean architecture.

## Local Setup

```bash
git clone https://github.com/harrytmthy-dev/safebox.git
```

### Update pre-hook path

`scripts/` contains shared pre-hooks for formatting and test validation. To enable it locally:

```bash
git config --local core.hooksPath scripts
chmod +x scripts/pre-commit
chmod +x scripts/pre-push
```

### Run Spotless

```bash
./gradlew spotlessApply --init-script gradle/init.gradle.kts --no-configuration-cache
```

## Commit & PR Guidelines

- Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) (e.g. `feat:`, `fix:`, `docs:`, `refactor:`).
- Keep PRs focused and small.
- If your change affects the public API, update `README.md` or `MIGRATION.md` accordingly.
- GitHub Actions will automatically run tests and checks upon opening or updating a PR.
- Ensure all checks pass before merging.

### Creating a PR

1. Fork the repo and create your feature branch (`git checkout -b feature/amazing-feature`)
2. Push your changes (`git push origin feature/amazing-feature`)
3. Open a Pull Request against the `main` branch of the original repository

## Testing

Run all tests locally with:

```bash
./gradlew testDebugUnitTest
./gradlew connectedAndroidTest
```

CI runs instrumented tests automatically on:
- Android API 26
- Android API 34

## Contributor Etiquette

- Discuss major changes or feature proposals first via [Issues](https://github.com/harrytmthy-dev/safebox/issues).
- Be respectful during reviews! We're building something safe and friendly.
- If anything is unclear, don't hesitate to ask!

## Releasing (For Maintainers)

Only maintainers can perform releases:
- Tag with semantic versioning (`v1.1.0-alpha02`)
- Update `CHANGELOG.md`
- Run: `./gradlew publishToMavenCentral --no-configuration-cache`

---

Thanks again for contributing to SafeBox! You're helping shape a faster, safer Android future.