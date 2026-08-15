# HMR Isolation Upgrade: parent-delegation vs child-first vs ModuleLayer

> Canonical language: **English**. Chinese translation: docs/zh/hmr-isolation-evaluation.zh-CN.md.
> Status: evaluation (reviewer item P2). This weighs upgrading cordis4j-hmr's stage-1 isolation
> (parent-delegating URLClassLoader, per docs/design/hmr-evaluation.md section 5) to a child-first
> loader or ModuleLayer. The conclusion feeds decision D27; code changes follow only if a real
> requirement appears.

## 1. What is on the table

Stage 1 loads each plugin jar into a `URLClassLoader` parented on the cordis4j-core loader. Its
documented boundaries: host classes win (same-named plugin classes are shadowed), plugins cannot
ship their own versions of host dependencies, and there is no module encapsulation. The question
is whether, and when, to upgrade.

## 2. Candidate upgrades

| Dimension | Stage 1 (parent delegation) | Child-first loader | ModuleLayer |
|---|---|---|---|
| Host-type identity | guaranteed (plugins always see the host `Plugin`) | must exclude cordis4j-core packages from child-first | modular plugins `require` the core module - identity preserved |
| Plugin ships its own dependency versions | no | yes (except excluded packages) | yes (per-layer module graphs) |
| Cross-plugin same-name classes | distinct copies, not interchangeable | distinct copies | distinct modules; encapsulation prevents accidental mixing |
| Strong encapsulation / reflective isolation | none | none | yes (`exports`/`opens`) |
| Plugin jar constraints | none | none | modular jar or correct automatic-module manifest; resolution failures abort the layer |
| GC-collection guarantee (T26) | proven | unchanged (loader reachability) | unchanged, plus layers must not be pinned |
| Cost to implement | shipped | medium (custom loader, exclusion list, tests) | high (module graph wiring, reflection surface, classpath/module-path mixing) |

## 3. Requirement scenarios

- **Plugin needs its own version of a library the host also uses**: stage 1 forces the host
  version; child-first with a cordis4j-core exclusion covers this at low cost; ModuleLayer covers
  it with stronger guarantees.
- **Two plugins bundle the same library class with different semantics**: both upgrades keep the
  copies separate; only ModuleLayer prevents accidental cross-plugin type mixing.
- **Untrusted plugins needing confinement**: only ModuleLayer offers encapsulation; on modern JDKs
  this is also the successor of the Security Manager story.

None of these scenarios is currently required by cordis4j's own modules or demos.

## 4. Recommendation

Keep stage 1 (parent delegation) as the shipped default; its boundaries are documented and pinned
by T34. Upgrade path:

1. **Child-first with an exclusion list** (first choice, when a plugin actually needs to bundle a
   dependency): exclude `io.cordis4j.core` and `java.*` from child-first resolution so plugin code
   keeps the host type identity; keep the close-and-collect retraction unchanged.
2. **ModuleLayer** (when encapsulation or per-plugin module graphs are required): the
   stage-2 engine already sketched in docs/design/hmr-evaluation.md section 4.

The decision and its trigger conditions are recorded in the design contract (decision D27); no
code changes are made now.
