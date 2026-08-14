/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import io.cordis4j.core.ComponentEntry;
import io.cordis4j.core.Context;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.Loader;
import io.cordis4j.core.LoaderConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bridges the core {@link Loader}'s transactional reconcile (Algorithm 10, configuration level)
 * with the bytecode engine: components are loaded from plugin jars, and a reload re-imports the
 * changed jar, swaps the stale entry's fiber, and rolls back on failure - the JVM form of paper
 * Section 5.2.2 with the jar as the module vertex.
 *
 * <p>The reload discipline is built in: on a successful reload the old fiber is disposed by the
 * core reconcile and the old handle is detached, so the replaced code becomes collectable; on a
 * failed reload the core restores the previous fiber set and the freshly loaded code is detached
 * without disturbing the running system.
 *
 * <p>Threading follows the core (decision D19): this loader synchronizes its own state only, and
 * all core operations run user code outside their monitors.
 */
public final class HotReloadingLoader implements Disposable {

  private final Context ctx;
  private final Loader loader;
  private final PluginClassRegistry registry;
  private final Map<String, Source> sources = new LinkedHashMap<>();
  private final Disposable guard;
  private boolean disposed;

  private record Source(Path jar, String mainClass) {}

  private HotReloadingLoader(Context ctx) {
    this.ctx = ctx;
    this.loader = Loader.of(ctx);
    this.registry = PluginClassRegistry.create();
    this.guard = Disposables.of(this::unloadAll);
  }

  /**
   * Creates a hot-reloading loader managing components of the given context.
   *
   * @param context the context components load into
   * @return a new loader, managing nothing yet
   * @throws NullPointerException if {@code context} is null
   */
  public static HotReloadingLoader of(Context context) {
    return new HotReloadingLoader(Objects.requireNonNull(context, "context"));
  }

  /**
   * Loads a plugin jar as a new component under {@code id}: the jar's unique {@link
   * io.cordis4j.core.Plugin} implementation is discovered and applied. The change is transactional:
   * a failing load leaves the previous configuration running and detaches the failed code.
   *
   * @param id the component id
   * @param jar the plugin jar
   * @return the handle of the loaded plugin
   * @throws IllegalArgumentException if {@code id} is already loaded or the jar declares zero or
   *     several plugin implementations
   * @throws io.cordis4j.hmr.Cordis4jPluginException if the jar cannot be loaded
   * @throws io.cordis4j.core.CordisException when the plugin fails to apply, after restoring the
   *     previous set
   * @throws IllegalStateException if this loader is disposed
   * @throws NullPointerException if {@code id} or {@code jar} is null
   */
  public synchronized PluginHandle load(String id, Path jar) {
    return load(id, jar, null);
  }

  /**
   * Loads a plugin jar as a new component under {@code id} with an explicit plugin class.
   *
   * @param id the component id
   * @param jar the plugin jar
   * @param mainClass the binary name of the class implementing {@link io.cordis4j.core.Plugin}
   * @return the handle of the loaded plugin
   * @throws IllegalArgumentException if {@code id} is already loaded or {@code mainClass} is not an
   *     instantiable plugin class
   * @throws io.cordis4j.hmr.Cordis4jPluginException if the jar cannot be loaded
   * @throws io.cordis4j.core.CordisException when the plugin fails to apply, after restoring the
   *     previous set
   * @throws IllegalStateException if this loader is disposed
   * @throws NullPointerException if {@code id} or {@code jar} is null
   */
  public synchronized PluginHandle load(String id, Path jar, String mainClass) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(jar, "jar");
    requireOpen();
    if (sources.containsKey(id)) {
      throw new IllegalArgumentException("component already loaded: " + id + "; use reload()");
    }
    Source source = new Source(jar, mainClass);
    PluginHandle handle = loadCode(source);
    registry.install(id, handle);
    sources.put(id, source);
    try {
      loader.reconcile(config());
      return handle;
    } catch (RuntimeException | Error failure) {
      sources.remove(id);
      registry.uninstall(id);
      throw failure;
    }
  }

  /**
   * Re-imports the recorded jar of {@code id} and swaps the component's fiber for a fresh
   * instantiation; the replaced code is detached and becomes collectable. On failure - an
   * unloadable jar or a failing plugin apply - the previous fiber set stays running and the fresh
   * code is detached (paper Algorithm 10, transactional reload).
   *
   * <p>On Windows the recorded jar may be file-locked by the running plugin's class loader; prefer
   * {@link #reload(String, Path)} with a newly written jar there.
   *
   * @param id the component id
   * @return the handle of the reloaded plugin
   * @throws IllegalStateException if {@code id} is not loaded or this loader is disposed
   * @throws io.cordis4j.hmr.Cordis4jPluginException if the jar cannot be loaded
   * @throws io.cordis4j.core.CordisException when the plugin fails to apply, after restoring the
   *     previous set
   * @throws NullPointerException if {@code id} is null
   */
  public synchronized PluginHandle reload(String id) {
    Objects.requireNonNull(id, "id");
    requireOpen();
    Source source = sources.get(id);
    if (source == null) {
      throw new IllegalStateException("component not loaded: " + id);
    }
    return reload(id, source.jar, source.mainClass);
  }

  /**
   * Re-imports {@code id} from a newly written jar (its unique {@link io.cordis4j.core.Plugin}
   * implementation discovered) and swaps the component's fiber; transactional as {@link
   * #reload(String)}.
   *
   * @param id the component id
   * @param jar the replacement plugin jar
   * @return the handle of the reloaded plugin
   * @throws IllegalStateException if {@code id} is not loaded or this loader is disposed
   * @throws IllegalArgumentException if the jar declares zero or several plugin implementations
   * @throws io.cordis4j.hmr.Cordis4jPluginException if the jar cannot be loaded
   * @throws io.cordis4j.core.CordisException when the plugin fails to apply, after restoring the
   *     previous set
   * @throws NullPointerException if {@code id} or {@code jar} is null
   */
  public synchronized PluginHandle reload(String id, Path jar) {
    return reload(id, jar, null);
  }

  /**
   * Re-imports {@code id} from a newly written jar with an explicit plugin class and swaps the
   * component's fiber; transactional as {@link #reload(String)}.
   *
   * @param id the component id
   * @param jar the replacement plugin jar
   * @param mainClass the binary name of the class implementing {@link io.cordis4j.core.Plugin}
   * @return the handle of the reloaded plugin
   * @throws IllegalStateException if {@code id} is not loaded or this loader is disposed
   * @throws IllegalArgumentException if {@code mainClass} is not an instantiable plugin class
   * @throws io.cordis4j.hmr.Cordis4jPluginException if the jar cannot be loaded
   * @throws io.cordis4j.core.CordisException when the plugin fails to apply, after restoring the
   *     previous set
   * @throws NullPointerException if {@code id} or {@code jar} is null
   */
  public synchronized PluginHandle reload(String id, Path jar, String mainClass) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(jar, "jar");
    requireOpen();
    Source source = sources.get(id);
    if (source == null) {
      throw new IllegalStateException("component not loaded: " + id);
    }
    Source next = new Source(jar, mainClass);
    PluginHandle fresh = loadCode(next);
    try {
      loader.reconcile(configWith(id, fresh.plugin()));
      sources.put(id, next); // the replacement jar is the new source of the id
      return registry.install(id, fresh); // detaches the replaced handle
    } catch (RuntimeException | Error failure) {
      fresh.detach(); // the core restored the previous fiber set; release the fresh code
      throw failure;
    }
  }

  /**
   * Unloads a component: its fiber is disposed first (recovering everything it installed), then its
   * code is detached. The detached handle stays observable through {@link
   * PluginHandle#collected()}.
   *
   * @param id the component id
   * @throws IllegalStateException if {@code id} is not loaded or this loader is disposed
   * @throws NullPointerException if {@code id} is null
   */
  public synchronized void unload(String id) {
    Objects.requireNonNull(id, "id");
    requireOpen();
    if (!sources.containsKey(id)) {
      throw new IllegalStateException("component not loaded: " + id);
    }
    sources.remove(id);
    loader.reconcile(config());
    registry.uninstall(id);
  }

  /**
   * Returns the current handle of a component.
   *
   * @param id the component id
   * @return the handle, or empty when the id is unknown
   * @throws NullPointerException if {@code id} is null
   */
  public synchronized Optional<PluginHandle> handle(String id) {
    Objects.requireNonNull(id, "id");
    return registry.handle(id);
  }

  /**
   * Returns the loaded component ids in load order.
   *
   * @return an immutable snapshot of the loaded ids
   */
  public synchronized List<String> ids() {
    return List.copyOf(sources.keySet());
  }

  /**
   * Unloads every component (fibers first, then the code) and closes this loader. Idempotent; the
   * context itself is left untouched.
   */
  @Override
  public void dispose() {
    guard.dispose();
  }

  private void unloadAll() {
    List<String> ids;
    synchronized (this) {
      if (disposed) {
        return;
      }
      disposed = true;
      ids = new ArrayList<>(sources.keySet());
      sources.clear();
    }
    loader.dispose(); // unloads every fiber it manages
    for (String id : ids) {
      registry.uninstall(id);
    }
  }

  private void requireOpen() {
    if (disposed) {
      throw new IllegalStateException("HotReloadingLoader is disposed");
    }
  }

  private PluginHandle loadCode(Source source) {
    return source.mainClass == null
        ? BytecodePluginLoader.load(source.jar)
        : BytecodePluginLoader.load(source.jar, source.mainClass);
  }

  private LoaderConfig config() {
    List<ComponentEntry> entries = new ArrayList<>();
    for (String id : sources.keySet()) {
      entries.add(new ComponentEntry(id, registry.handle(id).orElseThrow().plugin()));
    }
    return new LoaderConfig(entries);
  }

  private LoaderConfig configWith(String swappedId, io.cordis4j.core.Plugin fresh) {
    List<ComponentEntry> entries = new ArrayList<>();
    for (String id : sources.keySet()) {
      entries.add(
          new ComponentEntry(
              id, id.equals(swappedId) ? fresh : registry.handle(id).orElseThrow().plugin()));
    }
    return new LoaderConfig(entries);
  }

  @Override
  public String toString() {
    return "HotReloadingLoader{ctx=" + ctx + ", ids=" + sources.keySet() + "}";
  }
}
