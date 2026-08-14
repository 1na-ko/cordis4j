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

/** T17: supertype dispatch and per-listener filters; strict registration order. */
class EventHierarchyFilterTest {

  sealed interface Event permits Ping, Pong {}

  record Ping(String text) implements Event {}

  record Pong(int n) implements Event {}

  record Msg(int value) {}

  @Test
  @DisplayName("T17 父类型监听器收到子类型事件；同上下文内严格按注册顺序分发")
  void supertypeDispatchInOrder() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.on(Event.class, event -> trace.add("event-first"));
    ctx.on(Ping.class, ping -> trace.add("ping"));
    ctx.on(Event.class, event -> trace.add("event-second"));

    ctx.emit(new Ping("x"));
    assertEquals(List.of("event-first", "ping", "event-second"), trace, "全部匹配的监听器按注册顺序执行（与注册类型无关）");

    trace.clear();
    ctx.emit(new Pong(1));
    assertEquals(List.of("event-first", "event-second"), trace, "Pong 只匹配 Event 监听器");
  }

  @Test
  @DisplayName("T17 过滤器在监听器前执行，未通过的监听器不触发")
  void filtersRunBeforeListener() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.on(Msg.class, msg -> msg.value() > 0, msg -> trace.add("positive:" + msg.value()));
    ctx.on(Msg.class, msg -> trace.add("all"));

    ctx.emit(new Msg(5));
    ctx.emit(new Msg(-1));
    assertEquals(List.of("positive:5", "all", "all"), trace);
  }

  @Test
  @DisplayName("T17 子上下文的过滤器监听器随注册一并撤销")
  void filteredListenerUnregisters() {
    Context root = Contexts.create();
    Context child = root.fork();
    List<String> trace = new ArrayList<>();
    Disposable registration = child.on(Msg.class, msg -> msg.value() == 1, msg -> trace.add("one"));

    child.emit(new Msg(1));
    registration.dispose();
    child.emit(new Msg(1));
    assertEquals(List.of("one"), trace);
  }
}
