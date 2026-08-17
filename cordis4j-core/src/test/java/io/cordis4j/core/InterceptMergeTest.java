/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T18: interception metadata merging along the chain (paper Section 5.1.2, right-biased). */
class InterceptMergeTest {

  record Limits(Integer rate, Integer burst) implements InterceptMetadata {
    @Override
    public InterceptMetadata merge(InterceptMetadata nearer) {
      Limits near = (Limits) nearer;
      return new Limits(
          near.rate() != null ? near.rate() : rate(),
          near.burst() != null ? near.burst() : burst());
    }
  }

  interface Billing {}

  @Test
  @DisplayName("T18 全链 InterceptMetadata：从根向查询点合并，近端覆盖远端")
  void mergesAlongChain() {
    Context root = Contexts.create();
    Context tenant = root.fork();
    Context session = tenant.fork();
    root.intercept(ServiceKey.of(Billing.class), new Limits(100, null));
    tenant.intercept(ServiceKey.of(Billing.class), new Limits(null, 5));

    Optional<Object> merged = session.interceptOf(ServiceKey.of(Billing.class));
    assertTrue(merged.isPresent());
    assertEquals(new Limits(100, 5), merged.get(), "rate 取根、burst 取近端（null 视为未设置）");
  }

  @Test
  @DisplayName("T18 混合类型元数据保持 nearest-wins")
  void mixedKindsNearestWins() {
    Context root = Contexts.create();
    Context child = root.fork();
    root.intercept(ServiceKey.of(Billing.class), new Limits(1, 2));
    child.intercept(ServiceKey.of(Billing.class), "flat-metadata");

    assertEquals(Optional.of("flat-metadata"), child.interceptOf(ServiceKey.of(Billing.class)));
  }

  @Test
  @DisplayName("T18 三层 mixed 链：近端 InterceptMetadata 胜出，不再被中层 flat 元数据截断")
  void threeLayerMixedChainKeepsTheNearestMergeable() {
    Context root = Contexts.create();
    Context tenant = root.fork();
    Context session = tenant.fork();
    root.intercept(ServiceKey.of(Billing.class), new Limits(100, null));
    tenant.intercept(ServiceKey.of(Billing.class), "flat-in-the-middle");
    session.intercept(ServiceKey.of(Billing.class), new Limits(null, 5));

    Optional<Object> merged = session.interceptOf(ServiceKey.of(Billing.class));
    assertTrue(merged.isPresent());
    assertEquals(
        new Limits(null, 5), merged.get(), "最近端的 InterceptMetadata 必须胜出：中层 flat 绑定只截断更远的合并，不遮蔽近端");
  }

  @Test
  @DisplayName("T18 无绑定时返回 empty；撤销后恢复为空")
  void emptyAndRevert() {
    Context ctx = Contexts.create();
    ServiceKey<Billing> key = ServiceKey.of(Billing.class);
    assertEquals(Optional.empty(), ctx.interceptOf(key));
    Disposable binding = ctx.intercept(key, new Limits(1, null));
    binding.dispose();
    assertEquals(Optional.empty(), ctx.interceptOf(key));
  }
}
