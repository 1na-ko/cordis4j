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

/** Unit tests for {@link Disposables}. */
class DisposablesTest {

  @Test
  @DisplayName("none() 为可重复调用的 no-op")
  void noneIsNoop() {
    Disposables.none().dispose();
    Disposables.none().dispose();
  }

  @Test
  @DisplayName("of(Runnable) 只执行一次")
  void ofRunsAtMostOnce() {
    int[] count = {0};
    Disposable disposable = Disposables.of(() -> count[0]++);
    disposable.dispose();
    disposable.dispose();
    assertEquals(1, count[0]);
  }

  @Test
  @DisplayName("composite 按参数顺序执行并聚合失败")
  void compositeRunsInOrderAndAggregatesFailures() {
    List<String> order = new ArrayList<>();
    DisposeException error =
        assertThrows(
            DisposeException.class,
            () ->
                Disposables.composite(
                        Disposables.of(() -> order.add("one")),
                        Disposables.of(
                            () -> {
                              order.add("two");
                              throw new IllegalStateException("boom");
                            }),
                        Disposables.of(() -> order.add("three")))
                    .dispose());
    assertEquals(List.of("one", "two", "three"), order, "某部分失败不得中止其余部分");
    assertEquals(1, error.getSuppressed().length);
  }

  @Test
  @DisplayName("composite 拒绝 null 元素")
  void compositeRejectsNull() {
    assertThrows(NullPointerException.class, () -> Disposables.composite(Disposables.none(), null));
  }
}
