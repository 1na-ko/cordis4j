/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.Disposables;
import io.cordis4j.core.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The registry half of T26: handles are evicted, detached, and observed per id. */
class PluginClassRegistryTest {

  private static PluginHandle handle() {
    Plugin plugin = c -> Disposables.none();
    return PluginHandle.of(plugin, new ClassLoader(Plugin.class.getClassLoader()) {});
  }

  @Test
  @DisplayName("T26 install 替换同 id 句柄：旧句柄 detach，旧代码可回收")
  void installReplacesAndDetaches() throws Exception {
    PluginClassRegistry registry = PluginClassRegistry.create();
    PluginHandle first = handle();
    registry.install("p", first);
    assertSame(first, registry.handle("p").orElseThrow());
    assertFalse(first.collected(), "活跃句柄的代码不得被回收");

    PluginHandle second = handle();
    registry.install("p", second);
    assertThrows(IllegalStateException.class, first::plugin, "被替换的句柄必须 detach");
    assertSame(second, registry.handle("p").orElseThrow());

    for (int i = 0; i < 200 && !first.collected(); i++) {
      System.gc();
      Thread.sleep(20);
    }
    assertTrue(first.collected(), "替换并释放引用后旧代码必须可回收");
    assertFalse(second.collected(), "当前句柄仍在运行，不得被回收");
  }

  @Test
  @DisplayName("T26 uninstall 未知 id 为 no-op；未知 id 查询为空；null 参数拒绝")
  void unknownIdsAndNullChecks() {
    PluginClassRegistry registry = PluginClassRegistry.create();
    assertNull(registry.handle("missing").orElse(null));
    registry.uninstall("missing"); // no-op
    assertThrows(NullPointerException.class, () -> registry.install(null, handle()));
    assertThrows(NullPointerException.class, () -> registry.install("p", null));
    assertThrows(NullPointerException.class, () -> registry.handle(null));
    assertThrows(NullPointerException.class, () -> registry.uninstall(null));
  }
}
