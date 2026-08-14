## Summary

A concise description of the change and why it is needed.

## Paper / contract reference

- Which paper construct or decision-log entry does this implement or modify
  (e.g. Algorithm 3, D15, boundary clause 14)?
- If this changes semantics: the design-contract.md decision-log entry and
  version bump (required for semantic changes).

## Checklist

- [ ] `mvn verify` passes locally (enforcer, spotless, tests, jacoco >= 85%,
      javadoc doclint, dependency analysis)
- [ ] Behavior is pinned by a test (new or updated T-series test or demo smoke test)
- [ ] Docs updated if semantics changed (design-contract.md is canonical;
      docs/zh/ translations follow with a lag note)
- [ ] CHANGELOG.md entry added under Unreleased (or the release section)
