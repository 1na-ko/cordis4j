/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T5: unloading a plugin reverts everything its apply registered, LIFO (paper Algorithm 4). */
class PluginUnloadTest {

  @Test
  @DisplayName("T5 插件 apply 内注册的服务/事件/清理在域 dispose 时全部 LIFO 撤销")
  void unloadRevertsAllRegistrationsLifo() {
    Context ctx = Contexts.create();
    List<String> order = new ArrayList<>();
    Disposable domain =
        ctx.plugin(
            pluginCtx -> {
              pluginCtx.provide(new TrackingService("service", order)); // tracked first
              pluginCtx.on(Note.class, note -> {}); // tracked second (silent)
              return Disposables.of(() -> order.add("cleanup")); // tracked third
            });

    assertNotNull(ctx.get(TrackingService.class), "加载期间服务必须可用");

    domain.dispose();
    assertEquals(List.of("cleanup", "service:stop"), order, "卸载必须按 LIFO：最后登记的清理先执行，服务逆序撤销");
    assertThrows(NoSuchServiceException.class, () -> ctx.get(TrackingService.class));
  }

  private static final class TrackingService implements Service {
    private final String name;
    private final List<String> order;

    private TrackingService(String name, List<String> order) {
      this.name = name;
      this.order = order;
    }

    @Override
    public void stop() {
      order.add(name + ":stop");
    }
  }

  private record Note(String text) {}
}
