/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T3: event registration is a revertible effect; unregister stops delivery. */
class EventUnregisterTest {

  @Test
  @DisplayName("T3 on 返回的 Disposable 撤销后监听器不再被调用；emit 同步分发")
  void unregisterStopsDelivery() {
    Context ctx = Contexts.create();
    List<String> seen = new ArrayList<>();
    Disposable registration = ctx.on(Ping.class, ping -> seen.add(ping.text()));
    ctx.emit(new Ping("one"));
    registration.dispose();
    ctx.emit(new Ping("two"));
    assertEquals(List.of("one"), seen, "撤销后不得再投递");
  }

  private record Ping(String text) {}
}
