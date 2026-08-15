/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference discipline of plugin unloading: after a plugin domain is disposed, the plugin instance
 * must no longer be strongly reachable from the live context - a prerequisite for bytecode-level
 * hot module replacement (paper Section 5.2.2), whose retract side is class-loader collection.
 */
class PluginUnloadReleaseTest {

  private static boolean settle(WeakReference<?> ref) throws InterruptedException {
    for (int i = 0; i < 150 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    return ref.get() == null;
  }

  @Test
  @DisplayName("插件域卸载后实例引用被释放，可被 GC（引用纪律）")
  void pluginInstanceCollectedAfterUnload() throws Exception {
    Context ctx = Contexts.create();
    // An anonymous class, not a lambda: lambdas are singletons pinned by their defining class.
    Plugin plugin =
        new Plugin() {
          @Override
          public Disposable apply(Context c) {
            c.provide("value");
            return Disposables.none();
          }
        };
    WeakReference<Plugin> ref = new WeakReference<>(plugin);
    Disposable handle = ctx.plugin(plugin);
    plugin = null;

    handle.dispose();
    assertTrue(settle(ref), "卸载后上下文不得再强引用插件实例");
  }
}
