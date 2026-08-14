/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T13: supply uniqueness (paper Section 4.2): distinct active fibers may not supply one key. */
class SupplyConflictTest {

  interface Registry {}

  record InMemoryRegistry() implements Registry {}

  @Test
  @DisplayName("T13 两个不同 fiber 提供同键 → SupplyConflictException 且第二个插件整体回滚")
  void conflictingFibersRejected() {
    Context ctx = Contexts.create();
    ctx.plugin(
        c -> {
          c.provide(new InMemoryRegistry());
          return Disposables.none();
        });
    assertThrows(
        SupplyConflictException.class,
        () ->
            ctx.plugin(
                c -> {
                  c.provide(new InMemoryRegistry());
                  return Disposables.none();
                }),
        "不同 fiber 不得同时供给同一 store 键");
    assertEquals(1, ctx.find(InMemoryRegistry.class).stream().count(), "原供给保持不变");
  }

  @Test
  @DisplayName("T13 同一 fiber 内重复 provide 同键允许（覆盖语义）")
  void sameFiberMayOverwriteItself() {
    Context ctx = Contexts.create();
    ctx.plugin(
        c -> {
          c.provide(ServiceKey.of(Registry.class), new InMemoryRegistry());
          c.provide(ServiceKey.of(Registry.class), new InMemoryRegistry());
          return Disposables.none();
        });
    assertEquals(1, ctx.find(Registry.class).stream().count());
  }

  @Test
  @DisplayName("T13 ambient（域外）provide 可覆盖 fiber 的绑定（管理员覆盖语义）")
  void ambientOverwriteAllowed() {
    Context ctx = Contexts.create();
    ctx.plugin(
        c -> {
          c.provide(new InMemoryRegistry());
          return Disposables.none();
        });
    ctx.provide(new InMemoryRegistry()); // ambient overwrite: allowed
    assertEquals(1, ctx.find(InMemoryRegistry.class).stream().count());
  }
}
