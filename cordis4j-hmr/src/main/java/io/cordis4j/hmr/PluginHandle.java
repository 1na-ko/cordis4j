/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import io.cordis4j.core.Plugin;
import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * One loading of a plugin jar: the instantiated {@link Plugin} and the weak observation of the
 * class loader that defined its code (paper, Section 5.2.2 in the JVM form of Section 6.4: loading
 * code is an effect whose inverse is the loader becoming unreachable).
 *
 * <p>The handle holds the plugin instance strongly only while the handle is current; once the
 * plugin is unloaded or replaced, the instance reference is dropped and the loader is closed (the
 * jar's file handle is released immediately, so the jar file itself can be rewritten on Windows),
 * after which the garbage collector can reclaim the loader and the plugin's classes. {@link
 * #collected()} then reports whether the code is no longer reachable, which the tests use to prove
 * the retraction side of HMR.
 */
public final class PluginHandle {

  // volatile: detach() runs under the registry's monitor while plugin() reads lock-free;
  // unsynchronized publication would let a reader see a stale instance after a swap.
  private volatile Plugin plugin;
  private volatile ClassLoader strongLoader;
  private final WeakReference<ClassLoader> loader;
  private volatile boolean detached;

  PluginHandle(Plugin plugin, ClassLoader loader) {
    this.plugin = plugin;
    this.strongLoader = loader;
    this.loader = new WeakReference<>(loader);
  }

  static PluginHandle of(Plugin plugin, ClassLoader loader) {
    return new PluginHandle(
        Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(loader, "loader"));
  }

  /**
   * Returns the instantiated plugin.
   *
   * @return the plugin instance, never null while this handle is current
   * @throws IllegalStateException if the plugin was unloaded or replaced
   */
  public Plugin plugin() {
    if (detached) {
      throw new IllegalStateException("plugin is no longer loaded");
    }
    return plugin;
  }

  /**
   * Reports whether the plugin's code is no longer reachable: true once the class loader that
   * defined it has been collected (the loading effect has been reverted).
   *
   * @return true when the loader has been garbage-collected
   */
  public boolean collected() {
    return loader.get() == null;
  }

  /**
   * Drops the strong references so the code can be collected: the plugin instance is released and
   * the loader is closed (freeing the jar's file handle) while the weak observation stays usable.
   */
  void detach() {
    detached = true;
    plugin = null;
    if (strongLoader instanceof Closeable closeable) {
      try {
        closeable.close();
      } catch (IOException ignored) {
        // closing is best-effort: classes already loaded stay usable until collected
      }
    }
    strongLoader = null;
  }
}
