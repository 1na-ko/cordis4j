# Contributing to Cordis4j

Thanks for your interest in contributing. This document is the process contract of the project;
the semantic contract lives in [docs/design-contract.md](docs/design-contract.md).

## Development workflow (GitHub Flow)

- `main` is always releasable: green CI, tagged releases only.
- Every change lands through a **feature branch + pull request**:
  1. branch off `main` with a conventional-type prefix (`feat/`, `fix/`, `docs/`, `test/`,
     `chore/`, `ci/`);
  2. push the branch and open a PR; CI runs on the PR;
  3. merge with **squash** only after CI passes; delete the branch.
- `main` is protected by a ruleset: direct pushes are rejected; a PR with a green CI run is
  required to merge.
- Releasing: bump the `<revision>` property in `pom.xml` (the single source of truth for all
  module versions), update `CHANGELOG.md`, commit on `main`, and tag `vX.Y.Z`.

## Commit conventions

- [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`,
  `test:`, `chore:`, `ci:`.
- Messages in English.

## Quality gates

`mvn verify` must pass before a PR is opened or merged. It runs: enforcer (JDK 21+), spotless
(google-java-format + license headers), the unit/semantic tests, JaCoCo (line coverage >= 85%),
javadoc (doclint=all), and dependency analysis (zero undeclared/unused dependencies).

Run `mvn spotless:apply` before committing Java changes.

## Documentation policy

- **Canonical language: English.** Code, Javadoc, commit messages, `CHANGELOG.md`, `README.md`,
  and the `docs/` tree are English.
- **Chinese translations** live in `README.zh-CN.md` and `docs/zh/`. Every translation must
  state the canonical document it translates and its last sync date; when in doubt, the English
  version wins.
- When a canonical English document changes, update its Chinese translation in the same PR - or
  explicitly mark the translation as outdated. Never leave a stale translation unmarked.
- `docs/` is the source of current truth for users; internal working documents and
  assessments stay out of the public repository.
- Third-party documents are cited by link, never vendored into the repository.

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).

## License

MIT. See [LICENSE](LICENSE). By contributing you agree to license your work under MIT.
