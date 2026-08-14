# Contributing to Cordis4j

This is a personal project maintained in spare time. Issues and PRs are welcome, but replies
may be slow. The semantic ground truth is [docs/design-contract.md](docs/design-contract.md) -
when in doubt about a design decision, please open an issue and ask before large changes.

Please keep discussions friendly and about the code; that's the whole etiquette policy here.

## Building and testing

- JDK 21+, Maven 3.9+.
- `mvn verify` runs what CI runs: format check, tests, line coverage (>= 85%), javadoc
  (doclint), and dependency analysis.
- `mvn spotless:apply` fixes Java formatting before committing.

## Pull requests

`main` is protected (a PR with a green CI run is required to merge), so:

1. branch off `main` and make the change;
2. open a PR; CI must pass;
3. commit messages in the conventional style (`feat:`, `fix:`, `docs:`, ...), in English.

Small fixes are fine as they are. For anything semantic (the design contract, the public API),
please include a test that pins the behavior.

## Documentation

English documents are canonical; `README.zh-CN.md` and `docs/zh/` are Chinese translations
that follow along. Updating a translation in the same PR as its English original is
appreciated; marking it as outdated is acceptable too.

## License

MIT - see [LICENSE](LICENSE).
