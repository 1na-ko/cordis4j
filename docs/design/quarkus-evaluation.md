# Quarkus Integration: Evaluation

> Canonical language: **English**. Chinese translation: docs/zh/quarkus-evaluation.zh-CN.md.
> Status: evaluation (roadmap P3 item d, Quarkus half). This records the integration paths and the
> recommendation; it is a proposal to the maintainer, not a decision-log entry. A future
> implementation lands in a separate module and keeps cordis4j-core zero-dependency.

## 1. What the integration must express

The paradigm's two dimensions over Quarkus's programming model:

- **Temporal**: a cordis4j `Context` (typically one per session or application scope) whose
  disposal reverts every plugin, service, and child it carries - mapped onto the CDI context
  lifecycle (application/shutdown, session/destroy).
- **Spatial**: Quarkus beans declaring themselves as cordis4j services, resolvable and reactive
  (satisfaction/notify/refresh) through the context tree, while remaining ordinary CDI beans.

The Spring module (cordis4j-spring, T27) already demonstrates the exact shape of this integration:
a context bean whose destruction disposes the context, and annotated beans provided into it with
reverse-order withdrawal. The Quarkus question is which of its extension mechanisms carries that
shape best.

## 2. Candidate mechanisms

| Mechanism | What it gives | Cost |
|---|---|---|
| Plain CDI producers (`@Produces`) | A `@Produces @ApplicationScoped Context` plus a producer method returning the root; `@CordisService` beans provided through a producer observer or a bean-managed registrar | Small; portable across CDI implementations; no Quarkus-specific build step |
| CDI portable extension (`Extension`) | Container lifecycle hooks (BeforeBeanDiscovery/AfterDeploymentValidation) to discover `@CordisService` beans and register a context bean programmatically | Medium; standard CDI; needs bean-manager interaction (creational contexts) |
| Full Quarkus extension (`BuildStep`, `BeanDefiningAnnotation`) | Build-time discovery, `BeanDefiningAnnotation` makes `@CordisService` a bean-defining annotation, extension metadata (`quarkus-extension.yaml`), and a processor run | High; Quarkus-specific infrastructure, build-time only, requires the extension to be published |

## 3. Recommendation

Implement the Quarkus half as a **plain CDI module** (the first row), not a full Quarkus
extension: a `@Produces`-based context bean plus a CDI `Extension` that observes
`@CordisService`-annotated beans and registers them into the produced `Context`, mirroring
cordis4j-spring's registrar. This keeps the module portable across CDI runtimes, needs no
build-time step, and matches the precedent already shipped. A full Quarkus extension (build-time
`BeanDefiningAnnotation`) is a possible follow-up if build-time discovery becomes a requirement.

The work is deferred, not blocked: cordis4j-spring covers the same integration pattern today, and
the core contract is unaffected either way (integrations live in separate modules). Revisit when
a concrete Quarkus deployment needs the bridge.

## 4. References

- cordis4j-spring (the shipped precedent): cordis4j-spring module, T27
- CDI specification: https://jakarta.ee/specifications/cdi/
- Quarkus extensions guide: https://quarkus.io/guides/writing-extensions
