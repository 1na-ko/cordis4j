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

/**
 * T12: the withdrawal drain (paper Algorithm 5, Theorem 63): a provider stops supplying only after
 * its dependents unloaded, and each dependent's teardown still resolves the dependency.
 */
class WithdrawalDrainTest {

  interface Db {}

  record PostgresDb() implements Db {}

  record Derived() {}

  /** A derived service whose teardown reads the dependency while draining. */
  static final class Cache implements Service {
    private final Context ctx;
    private final Db db;
    private final List<String> trace;

    Cache(Context ctx, Db db, List<String> trace) {
      this.ctx = ctx;
      this.db = db;
      this.trace = trace;
    }

    @Override
    public void stop() {
      Db resolved = ctx.find(Db.class).orElse(db); // must still resolve during the drain
      trace.add("cache-stop:db=" + (resolved == db ? "same" : "different"));
    }
  }

  @Test
  @DisplayName("T12 provider 撤销 → dependent 先卸载（teardown 仍可解析依赖）→ provider 效应后撤销")
  void drainOrder() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();

    ctx.inject(
        Db.class,
        (c, db) -> {
          trace.add("dependent-load");
          c.provide(new Cache(c, db, trace));
          return Disposables.none();
        });
    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(ServiceKey.of(Db.class), new PostgresDb());
              return Disposables.of(() -> trace.add("provider-teardown"));
            });
    assertEquals(List.of("dependent-load"), trace);

    provider.dispose();
    assertEquals(
        List.of("dependent-load", "cache-stop:db=same", "provider-teardown"),
        trace,
        "dependent 必须先于 provider 撤销，且其 teardown 中依赖仍可解析（Theorem 63）");
  }

  @Test
  @DisplayName("T12 链式依赖（provider→dependent→granddependent）按依赖逆序排空")
  void chainedDrain() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();

    ctx.inject(
        Db.class,
        (c, db) -> {
          trace.add("dependent-load");
          c.provide(new Derived());
          return Disposables.of(() -> trace.add("dependent-unload"));
        });
    ctx.inject(
        Derived.class,
        (c, derived) -> {
          trace.add("grand-load");
          return Disposables.of(() -> trace.add("grand-unload"));
        });

    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(ServiceKey.of(Db.class), new PostgresDb());
              return Disposables.none();
            });
    assertEquals(List.of("dependent-load", "grand-load"), trace);

    provider.dispose();
    assertEquals(
        List.of("dependent-load", "grand-load", "grand-unload", "dependent-unload"),
        trace,
        "排空必须从最深的 dependent 开始");
  }
}
