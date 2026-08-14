# Cordis4j

[中文文档](README.zh-CN.md)

**Cordis4j** is the JVM implementation of the [Cordis](https://github.com/cordiverse/cordis)
meta-framework of *spatiotemporal composability*: every context mutation carries a tracked
inverse (temporal), and every dependency is declared and reactively resolved (spatial).

> Status: v0.1.0 vertical slice. Semantics follow the formal model in
> [A Programming Paradigm for Spatiotemporal Composability](https://github.com/cordiverse/paper)
> (Sections 3-5); the API is a Java re-imagining, not a line-by-line port of the TypeScript code.
> See [docs/design-contract.md](docs/design-contract.md) for the frozen contract and decision log.

## Requirements

- JDK 21+
- Maven 3.9+

## Modules

- `cordis4j-core` - zero-dependency core library (JPMS module `io.cordis4j.core`)
- `cordis4j-demo` - the end-to-end vertical slice demo

## Quickstart

See `cordis4j-demo/src/main/java/io/cordis4j/demo/QuickStart.java` and run:

```console
mvn install -DskipTests    # install cordis4j-core into the local repository once
mvn -pl cordis4j-demo exec:java
```

Expected output: `alice: hello, hi`, then the root timer value - and no output for
`bob`, because disposing the session reverted its plugin and listeners.

## Build & quality gates

```console
mvn verify   # enforcer + spotless + tests (T1-T10) + jacoco (>= 85%) + javadoc + dependency analysis
```

## Roadmap

- **P2** - declarative dependencies (inject) with the full provider-teardown drain ordering of
  paper Algorithms 3/5, the inertial lifecycle state machine on virtual threads, annotation-based
  injection, event filters, and configuration-level hot reload.
- **P3** - bytecode-level hot module replacement (custom ClassLoader / ModuleLayer evaluation,
  following the OSGi and pf4j precedents), and ecosystem integrations (Spring, Quarkus,
  LangChain4j).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow (GitHub Flow), the quality
gates, and the documentation policy (English is canonical; Chinese translations live in
`README.zh-CN.md` and `docs/zh/`).

## Credits

Cordis4j's semantics are based on the Cordis paper
([github.com/cordiverse/paper](https://github.com/cordiverse/paper)) and the reference
implementations [cordiverse/cordis](https://github.com/cordiverse/cordis) and
`@deepseek-ai/cordis` - both MIT-licensed code. Cordis4j itself is released under the
[MIT License](LICENSE).
