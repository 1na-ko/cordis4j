/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The declarative loader (paper, Section 5.2.1 and Algorithm 10, configuration-level form):
 * reconciles a desired configuration against the running set of components by id-keyed diffing -
 * new ids load, vanished ids unload, changed entries reload - and reverts its own changes when a
 * step fails, keeping the previously running configuration effective.
 *
 * <p>The composition DSL (decision D26) feeds the same engine: {@link #reconcileTree} flattens a
 * {@link ComponentSpec} tree - groups prefix their children's ids, isolation realms load their
 * children into derived contexts, and include references inline another configuration source
 * resolved against a base directory - into a flat set that is then reconciled transactionally,
 * exactly like {@link #reconcile(LoaderConfig)}.
 *
 * <p>Bytecode-level hot module replacement (paper Section 5.2.2) is out of scope here: components
 * are plain {@link Plugin} instances, so a reload swaps instances within one process.
 *
 * <p>The loader is itself a {@link Disposable}: disposing it unloads every component it manages.
 */
public final class Loader implements Disposable {

  /** One running entry: what was loaded, into which context, and its isolation realm (if any). */
  private record Loaded(ComponentEntry entry, Context loadContext, IsolatedDomain domain) {}

  /**
   * An isolation realm created by a composition: its derived context and how many of its entries
   * are currently loaded. The derived context is disposed once no entry of the realm remains.
   *
   * <p>Each realm is keyed by its position in the composition tree (prefix, type, realm label) and
   * reused across reconciles, so an unchanged subtree keeps its derived context - and with it its
   * running components - instead of reloading on every reconcile.
   */
  private static final class IsolatedDomain {
    final String key;
    final Context derived;
    int active;

    IsolatedDomain(String key, Context derived) {
      this.key = key;
      this.derived = derived;
    }
  }

  /** One flattened spec: the entry and its load context, before it is loaded. */
  private record Flat(String id, Plugin component, Context loadContext, IsolatedDomain domain) {}

  private final Context ctx;
  private final Map<String, Loaded> running = new LinkedHashMap<>();
  private final Map<String, Disposable> handles = new LinkedHashMap<>();
  private final Map<String, IsolatedDomain> domains = new LinkedHashMap<>();
  private boolean disposed;

  private Loader(Context ctx) {
    this.ctx = ctx;
  }

  /**
   * Creates a loader managing components of the given context.
   *
   * @param context the context components load into
   * @return a new loader, managing nothing yet
   * @throws NullPointerException if {@code context} is null
   */
  public static Loader of(Context context) {
    return new Loader(Objects.requireNonNull(context, "context"));
  }

  /**
   * Reconciles the running configuration into {@code config}: entries with new ids load, entries
   * whose id vanished unload, and entries whose component instance changed reload.
   *
   * <p>The change is transactional (Algorithm 10): if any load fails, the entries this reconcile
   * already removed or replaced are restored before the failure propagates, with restoration
   * failures attached as suppressed exceptions.
   *
   * @param config the desired configuration
   * @throws IllegalStateException if this loader is disposed
   * @throws NullPointerException if {@code config} is null
   * @throws CordisException when a component fails to load, after restoring the previous set
   */
  public synchronized void reconcile(LoaderConfig config) {
    Objects.requireNonNull(config, "config");
    List<ComponentSpec> specs = new ArrayList<>();
    for (ComponentEntry entry : config.entries()) {
      specs.add(new ComponentSpec.Entry(entry.id(), entry.component()));
    }
    reconcileTree(ctx.baseUrl().orElseGet(() -> Path.of("")), specs);
  }

  /**
   * Reconciles the running configuration into a composition tree resolved against this context's
   * base directory (decision D26): {@link ComponentSpec.Group} prefixes its children's ids with
   * {@code groupId + ':'}, {@link ComponentSpec.Isolate} loads its children into a derived {@link
   * Context#isolate(Class, String)} realm (disposed once its entries all unload), and {@link
   * ComponentSpec.Include} inlines the referenced configuration source.
   *
   * <p>The flattened set is reconciled transactionally exactly like {@link
   * #reconcile(LoaderConfig)}.
   *
   * @param specs the desired composition tree
   * @throws IllegalArgumentException if the flattened set contains duplicate ids
   * @throws IllegalStateException if this loader is disposed
   * @throws NullPointerException if {@code specs} is null
   * @throws CordisException when a component fails to load, after restoring the previous set
   */
  public synchronized void reconcileTree(List<ComponentSpec> specs) {
    reconcileTree(ctx.baseUrl().orElseGet(() -> Path.of("")), specs);
  }

  /**
   * Reconciles a composition tree resolved against an explicit base directory; see {@link
   * #reconcileTree(List)}.
   *
   * @param baseUrl the base directory include references resolve against
   * @param specs the desired composition tree
   * @throws IllegalArgumentException if the flattened set contains duplicate ids
   * @throws IllegalStateException if this loader is disposed
   * @throws NullPointerException if {@code baseUrl} or {@code specs} is null
   * @throws CordisException when a component fails to load, after restoring the previous set
   */
  public synchronized void reconcileTree(Path baseUrl, List<ComponentSpec> specs) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(specs, "specs");
    if (disposed) {
      throw new IllegalStateException("Loader is disposed");
    }
    List<IsolatedDomain> createdDomains = new ArrayList<>();
    List<Flat> withDomains = new ArrayList<>();
    flattenToFlats(specs, ctx, "", baseUrl, withDomains, createdDomains);
    Map<String, Flat> desired = new LinkedHashMap<>();
    try {
      for (Flat flat : withDomains) {
        if (desired.put(flat.id(), flat) != null) {
          throw new IllegalArgumentException("duplicate component id: " + flat.id());
        }
      }
    } catch (RuntimeException | Error failure) {
      for (IsolatedDomain domain : createdDomains) {
        discardDomain(domain); // freshly created, nothing reused depends on them
      }
      throw failure;
    }
    List<IsolatedDomain> pendingDisposals = new ArrayList<>();
    List<Runnable> compensation = new ArrayList<>(); // applied in reverse on failure
    try {
      // Removals and reloads first: the old world leaves before the new one arrives.
      for (String id : running.keySet().toArray(new String[0])) {
        Loaded previous = running.get(id);
        Flat wanted = desired.get(id);
        if (wanted == null || !sameEntry(previous, wanted)) {
          unload(id, pendingDisposals);
          Loaded restore = previous;
          compensation.add(
              () -> {
                Disposable handle = restore.loadContext().plugin(restore.entry().component());
                running.put(restore.entry().id(), restore);
                handles.put(restore.entry().id(), handle);
                if (restore.domain() != null) {
                  restore.domain().active++;
                  pendingDisposals.remove(restore.domain());
                }
              });
        }
      }
      // Additions and reload counterparts.
      for (Flat flat : withDomains) {
        Loaded current = running.get(flat.id());
        if (current == null || !sameEntry(current, flat)) {
          Disposable handle = flat.loadContext().plugin(flat.component());
          Loaded loaded =
              new Loaded(
                  new ComponentEntry(flat.id(), flat.component()),
                  flat.loadContext(),
                  flat.domain());
          running.put(flat.id(), loaded);
          handles.put(flat.id(), handle);
          if (flat.domain() != null) {
            flat.domain().active++;
            // A realm drained during the unload phase may be repopulated by this load; it is
            // no longer disposable.
            pendingDisposals.remove(flat.domain());
          }
          compensation.add(
              () -> {
                Disposable reverted = handles.remove(flat.id());
                running.remove(flat.id());
                if (reverted != null) {
                  reverted.dispose();
                }
                if (flat.domain() != null) {
                  flat.domain().active--;
                }
              });
        }
      }
    } catch (RuntimeException | Error failure) {
      for (IsolatedDomain domain : createdDomains) {
        if (domain.active == 0) {
          discardDomain(domain);
        }
      }
      rollback(compensation, failure);
      for (IsolatedDomain domain : pendingDisposals) {
        if (domain.active == 0) { // rollback may have repopulated some realms
          discardDomain(domain);
        }
      }
      throw failure;
    }
    for (IsolatedDomain domain : pendingDisposals) {
      discardDomain(domain);
    }
  }

  /** Whether a running entry and a flattened spec describe the same load. */
  private static boolean sameEntry(Loaded loaded, Flat flat) {
    return loaded.entry().component().equals(flat.component())
        && loaded.loadContext().equals(flat.loadContext());
  }

  /** Unloads every managed component in reverse load order; failures aggregate. */
  @Override
  public synchronized void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    List<Throwable> failures = new ArrayList<>();
    String[] ids = handles.keySet().toArray(new String[0]);
    for (int i = ids.length - 1; i >= 0; i--) {
      try {
        unload(ids[i], null);
      } catch (Throwable failure) { // an Error must not abort the remaining unloads
        failures.add(failure);
      }
    }
    if (!failures.isEmpty()) {
      DisposeException error =
          new DisposeException("Loader disposal failed with " + failures.size() + " error(s)");
      for (Throwable failure : failures) {
        error.addSuppressed(failure);
      }
      throw error;
    }
  }

  private void unload(String id, List<IsolatedDomain> pendingDisposals) {
    Disposable handle = handles.remove(id);
    Loaded loaded = running.remove(id);
    if (handle != null) {
      handle.dispose();
    }
    if (loaded != null && loaded.domain() != null) {
      loaded.domain().active--;
      if (loaded.domain().active == 0) {
        if (pendingDisposals != null) {
          pendingDisposals.add(loaded.domain());
        } else {
          discardDomain(loaded.domain());
        }
      }
    }
  }

  /** Removes a drained realm from the reuse table and disposes its derived context. */
  private void discardDomain(IsolatedDomain domain) {
    domains.remove(domain.key);
    domain.derived.dispose();
  }

  private void rollback(List<Runnable> compensation, Throwable cause) {
    for (int i = compensation.size() - 1; i >= 0; i--) {
      try {
        compensation.get(i).run();
      } catch (RuntimeException | Error restoration) {
        cause.addSuppressed(restoration);
      }
    }
  }

  private void flattenToFlats(
      List<ComponentSpec> specs,
      Context loadContext,
      String prefix,
      Path baseUrl,
      List<Flat> out,
      List<IsolatedDomain> createdDomains) {
    for (ComponentSpec spec : specs) {
      switch (spec) {
        case ComponentSpec.Entry entry ->
            out.add(new Flat(prefix + entry.id(), entry.component(), loadContext, null));
        case ComponentSpec.Group group ->
            flattenToFlats(
                group.children(),
                loadContext,
                prefix + group.id() + ":",
                baseUrl,
                out,
                createdDomains);
        case ComponentSpec.Isolate isolate -> {
          // Two isolate nodes with the same position and label share one realm by declaration;
          // the same position across reconciles keeps it, so unchanged entries do not reload.
          String key = prefix + isolate.type().getName() + '/' + isolate.realm();
          IsolatedDomain domain = domains.get(key);
          if (domain == null) {
            domain = new IsolatedDomain(key, loadContext.isolate(isolate.type(), isolate.realm()));
            domains.put(key, domain);
            createdDomains.add(domain);
          }
          flattenToFlats(isolate.children(), domain.derived, prefix, baseUrl, out, createdDomains);
        }
        case ComponentSpec.Include include -> {
          Path absolute = baseUrl.resolve(include.file());
          flattenToFlats(
              include.resolver().apply(absolute),
              loadContext,
              prefix,
              baseUrl,
              out,
              createdDomains);
        }
      }
    }
  }
}
