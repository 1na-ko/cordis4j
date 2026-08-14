/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The declarative loader (paper, Section 5.2.1 and Algorithm 10, configuration-level form):
 * reconciles a desired {@link LoaderConfig} against the running set of components by id-keyed
 * diffing - new ids load, vanished ids unload, changed entries reload - and reverts its own changes
 * when a step fails, keeping the previously running configuration effective.
 *
 * <p>Bytecode-level hot module replacement (paper Section 5.2.2) is out of scope here: components
 * are plain {@link Plugin} instances, so a reload swaps instances within one process. The
 * transactional guarantees follow the paper's three-phase reconcile: apply, and on failure
 * compensate by restoring the previous entries.
 *
 * <p>The loader is itself a {@link Disposable}: disposing it unloads every component it manages.
 */
public final class Loader implements Disposable {

  private final Context ctx;
  private final Map<String, ComponentEntry> running = new LinkedHashMap<>();
  private final Map<String, Disposable> handles = new LinkedHashMap<>();
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
    if (disposed) {
      throw new IllegalStateException("Loader is disposed");
    }
    Map<String, ComponentEntry> next = new LinkedHashMap<>();
    for (ComponentEntry entry : config.entries()) {
      next.put(entry.id(), entry);
    }
    List<Runnable> compensation = new ArrayList<>(); // applied in reverse on failure
    try {
      // Removals and reloads first: the old world leaves before the new one arrives.
      for (String id : running.keySet().toArray(new String[0])) {
        ComponentEntry previous = running.get(id);
        ComponentEntry desired = next.get(id);
        if (desired == null || !desired.equals(previous)) {
          unload(id);
          ComponentEntry restore = previous;
          compensation.add(
              () -> {
                Disposable handle = ctx.plugin(restore.component());
                running.put(restore.id(), restore);
                handles.put(restore.id(), handle);
              });
        }
      }
      // Additions and reload counterparts.
      for (ComponentEntry entry : config.entries()) {
        if (!entry.equals(running.get(entry.id()))) {
          Disposable handle = ctx.plugin(entry.component());
          running.put(entry.id(), entry);
          handles.put(entry.id(), handle);
          compensation.add(
              () -> {
                Disposable loaded = handles.remove(entry.id());
                running.remove(entry.id());
                if (loaded != null) {
                  loaded.dispose();
                }
              });
        }
      }
    } catch (RuntimeException | Error failure) {
      rollback(compensation, failure);
      throw failure;
    }
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
        unload(ids[i]);
      } catch (RuntimeException failure) {
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

  private void unload(String id) {
    Disposable handle = handles.remove(id);
    running.remove(id);
    if (handle != null) {
      handle.dispose();
    }
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
}
