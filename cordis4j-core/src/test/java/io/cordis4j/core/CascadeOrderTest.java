/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T6: cross-fiber cascade order (paper Algorithm 4): child effects are reverted before the parent's
 * earlier effects; disposing the root reverts the whole tree.
 */
class CascadeOrderTest {

  @Test
  @DisplayName("T6 父先注册效应 A、后 fork 子（子内注册 B）：父 dispose 时 B 先于 A 撤销")
  void childEffectsRevertBeforeParentsEarlierEffects() {
    Context root = Contexts.create();
    List<String> order = new ArrayList<>();
    root.plugin( // domain A registered first
        pluginCtx -> {
          pluginCtx.provide(new NamedService("A", order));
          return Disposables.none();
        });
    Context child = root.fork(); // fork disposal registered second
    child.plugin( // B lives in the child's own accumulator
        pluginCtx -> {
          pluginCtx.provide(new NamedService("B", order));
          return Disposables.none();
        });

    root.dispose();
    assertEquals(List.of("B", "A"), order, "子效应 B 必须先于父的早先效应 A 撤销");
  }

  @Test
  @DisplayName("T6 根 dispose 恢复整树；disposed 上下文操作抛 IllegalStateException")
  void rootDisposeCascadesToWholeTree() {
    Context root = Contexts.create();
    List<String> order = new ArrayList<>();
    root.plugin(
        pluginCtx -> {
          pluginCtx.provide(new NamedService("root-service", order));
          return Disposables.none();
        });
    Context child = root.fork();
    child.plugin(
        pluginCtx -> {
          pluginCtx.provide(new NamedService("child-service", order));
          return Disposables.none();
        });

    root.dispose();
    assertEquals(List.of("child-service", "root-service"), order, "整树必须被恢复");
    assertThrows(IllegalStateException.class, () -> child.get(NamedService.class));
  }

  private static final class NamedService implements Service {
    private final String name;
    private final List<String> order;

    private NamedService(String name, List<String> order) {
      this.name = name;
      this.order = order;
    }

    @Override
    public void stop() {
      order.add(name);
    }
  }
}
