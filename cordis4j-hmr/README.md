# cordis4j-hmr

Bytecode-level hot module replacement for Cordis4j (paper Section 5.2.2, stage 1 of
`docs/design/hmr-evaluation.md`): plugin jars load into per-plugin class loaders, a reload swaps
the stale entry's fiber through the core Loader's transactional reconcile, and the replaced code
becomes garbage-collectable.

- `BytecodePluginLoader` — loads a plugin jar (the unique `Plugin` implementation is discovered,
  or the main class named explicitly).
- `PluginHandle` — holds the code strongly only while current; detaching closes the loader (the
  jar's file handle is released immediately, so jars are rewritable on Windows) and keeps a weak
  observation of collection.
- `PluginClassRegistry` — evicts and observes entries by id.
- `HotReloadingLoader` — bridges the core `Loader`: reload re-imports the jar, swaps fibers, and
  rolls back on failure.

## Known isolation boundaries

Stage 1 loads every plugin jar into a plain `URLClassLoader` whose parent is the cordis4j-core
loader — standard parent delegation (decision D27, `docs/design/hmr-evaluation.md` section 5):

- **Host classes win.** A class the host classpath already provides (all of cordis4j-core, the
  JDK, and the application classpath) resolves from the host loader; a same-named class packaged
  inside a plugin jar is shadowed and never loaded. Plugin code therefore always implements the
  host's `Plugin` type — never a copy — which is what makes the identity and GC-collection
  guarantees hold (pinned by T34).
- **Plugins cannot ship their own versions of host dependencies.**
- **Cross-plugin same-name classes** are distinct copies from sibling per-jar loaders and are not
  interchangeable.
- **There is no module encapsulation** — reflective access into plugin code is unrestricted.

Not supported in stage 1, by design: inter-plugin class arbitration and per-plugin dependency
versions (the child-first upgrade, with a cordis4j-core exclusion) and strong encapsulation (the
ModuleLayer upgrade). Both are evaluated and reserved in
`docs/design/hmr-isolation-evaluation.md`; they are code changes only for a real requirement.
