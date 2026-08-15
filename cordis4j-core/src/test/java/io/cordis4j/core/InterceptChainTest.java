/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T31: intercept-chain consumption (decision D23) - the Java form of upstream's resolveConfig: the
 * metadata bound along the tree is collected root-first, nearest-last, and callers merge it with
 * any policy; the {@code interceptOf} result is the nearer-wins monoid over that list.
 */
class InterceptChainTest {

  record Marker(String value) {}

  record Conf(int level) implements InterceptMetadata {
    @Override
    public InterceptMetadata merge(InterceptMetadata nearer) {
      return nearer;
    }
  }

  @Test
  @DisplayName("T31 intercepts 沿树收集（根→最近）；interceptOf 保持 nearest-wins")
  void collectsRootFirstNearestLast() {
    Context root = Contexts.create();
    Context child = root.fork();
    Context grandchild = child.fork();
    ServiceKey<Marker> key = ServiceKey.of(Marker.class);

    root.intercept(key, new Conf(1));
    child.intercept(key, new Conf(2));
    grandchild.intercept(key, new Conf(3));

    assertEquals(
        List.of(new Conf(1), new Conf(2), new Conf(3)), grandchild.intercepts(key), "链必须根→最近排列");
    assertEquals(
        new Conf(3), grandchild.interceptOf(key).orElseThrow(), "interceptOf 保持 nearest-wins");
    assertEquals(new Conf(2), child.interceptOf(key).orElseThrow(), "interceptOf 保持 nearest-wins");
  }

  @Test
  @DisplayName("T31 无绑定为空列表；消费端合并链与 interceptOf 的幺半群一致")
  void emptyChainAndConsumerMerge() {
    Context root = Contexts.create();
    Context child = root.fork();
    ServiceKey<Marker> key = ServiceKey.of(Marker.class);

    assertEquals(List.of(), child.intercepts(key), "无绑定必须为空列表");

    root.intercept(key, new Conf(1));
    child.intercept(key, new Conf(2));
    List<Object> chain = child.intercepts(key);
    Object merged = chain.get(0);
    for (int i = 1; i < chain.size(); i++) {
      merged = ((InterceptMetadata) merged).merge((InterceptMetadata) chain.get(i));
    }
    assertEquals(child.interceptOf(key).orElseThrow(), merged, "消费端幺半群合并与 interceptOf 一致");

    // mixed kinds keep nearest-wins over the raw list
    Context leaf = child.fork();
    leaf.intercept(key, Map.of("kind", "raw"));
    assertEquals(3, leaf.intercepts(key).size(), "混合类型也进入消费列表");
  }

  @Test
  @DisplayName("T31 null 键与 dispose 后访问拒绝")
  void guards() {
    Context ctx = Contexts.create();
    assertThrows(NullPointerException.class, () -> ctx.intercepts(null));
    ctx.dispose();
    assertThrows(IllegalStateException.class, () -> ctx.intercepts(ServiceKey.of(Marker.class)));
  }
}
