# Contributing to cloud-itonami-isco-0310

We welcome contributions to this project! Please read the following guidelines:

## Code of Conduct

This project adheres to the Contributor Covenant [code of conduct](CODE_OF_CONDUCT.md).
By participating, you are expected to uphold this code.

## How to Contribute

1. **Report bugs** by opening an issue with a clear description and reproduction steps.
2. **Suggest enhancements** by opening an issue describing the enhancement and expected behavior.
3. **Submit code changes** by creating a pull request with a clear description of changes.

## Pull Request Process

1. Ensure all tests pass: `kbb -M:test`
2. Update documentation for any behavioral changes.
3. Follow the existing code style (CLJC, idiomatic Clojure).
4. Keep the scope of PRs focused on a single concern.

## Testing

All contributions must pass the test suite:

```bash
kbb -M:test
```

## License

By contributing to this project, you agree that your contributions will be
licensed under its AGPL-3.0-or-later license.
