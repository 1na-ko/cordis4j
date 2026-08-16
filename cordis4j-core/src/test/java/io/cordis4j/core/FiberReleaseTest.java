/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T36: registry release of fibers that never ran a full unload - reactively drained, failed before
 * landing, or never satisfied. Disposing the declaration of such a fiber must remove it from the
 * registry, or the fiber (and through its owner the whole context subtree, class loaders included)
 * stays strongly reachable forever.
 */
class FiberReleaseTest {

  record Clock(String name) {}

  private static boolean settle(WeakReference<?> ref) throws InterruptedException {
    for (int i = 0; i < 150 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    return ref.get() == null;
  }

  @Test
  @DisplayName("T36 reactive 卸载成 INACTIVE 后 dispose 声明，fiber 必须离开注册表")
  void reactivelyDrainedFiberReleasedOnRetire() throws Exception {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    BiFunction<Context, Clock, Disposable> dependent =
        new BiFunction<>() {
          @Override
          public Disposable apply(Context c, Clock clock) {
            trace.add("activated");
            return Disposables.none();
          }
        };
    WeakReference<BiFunction<Context, Clock, Disposable>> ref = new WeakReference<>(dependent);
    Disposable declaration = ctx.inject(Clock.class, dependent);
    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(new Clock("main"));
              return Disposables.none();
            });

    provider.dispose(); // drains the dependent into INACTIVE (it stays indexed, re-activatable)
    declaration.dispose(); // retires it: the fiber must leave the registry here

    dependent = null;
    assertTrue(settle(ref), "非 ACTIVE fiber 的 dispose 也必须注销注册（回调闭包不得被注册表钉住）");
  }

  @Test
  @DisplayName("T36 激活失败的 fiber dispose 声明后必须离开注册表")
  void failedFiberReleasedOnRetire() throws Exception {
    Context ctx = Contexts.create();
    BiFunction<Context, Clock, Disposable> dependent =
        new BiFunction<>() {
          @Override
          public Disposable apply(Context c, Clock clock) {
            throw new RuntimeException("boom");
          }
        };
    WeakReference<BiFunction<Context, Clock, Disposable>> ref = new WeakReference<>(dependent);
    Disposable declaration = ctx.inject(Clock.class, dependent);
    ctx.plugin(
        c -> {
          c.provide(new Clock("main"));
          return Disposables.none();
        }); // activation fails and is routed to unload (D14); the declaration returns normally

    declaration.dispose(); // the failed fiber must leave the registry here

    dependent = null;
    assertTrue(settle(ref), "failed fiber 的 dispose 必须注销注册（回调闭包不得被注册表钉住）");
  }

  @Test
  @DisplayName("T36 依赖从未满足的 fiber dispose 声明后必须离开注册表")
  void unsatisfiedFiberReleasedOnRetire() throws Exception {
    Context ctx = Contexts.create();
    BiFunction<Context, Clock, Disposable> dependent =
        new BiFunction<>() {
          @Override
          public Disposable apply(Context c, Clock clock) {
            return Disposables.none();
          }
        };
    WeakReference<BiFunction<Context, Clock, Disposable>> ref = new WeakReference<>(dependent);
    Disposable declaration = ctx.inject(Clock.class, dependent); // no provider ever appears

    declaration.dispose(); // the never-activated fiber must leave the registry here

    dependent = null;
    assertTrue(settle(ref), "从未激活的 fiber 的 dispose 必须注销注册（回调闭包不得被注册表钉住）");
  }
}
