/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T89-T93: the semantic-drift review batch - runtime parity probes against the cordis mainline
 * (4.0.0-rc.9 @ b912d39, verified by the drift probes in the review notes). Each test pins one
 * verified upstream difference as a declared deviation (design-contract.md Section 5, items 11-14)
 * or an already-contracted equivalence: T89 synchronous activation (deviation 11), T90 fully serial
 * LIFO unload across effect groups (deviation 12), T91 the withdrawal store timing (equivalent to
 * upstream's snapshot mechanism, boundary 14), T92 the failed-fiber recovery path (deviation 13),
 * T93 the event-visibility matrix (deviation 14, decision D3).
 */
class UpstreamDriftParityTest {

  // ── T89: synchronous activation (upstream defers the body behind a microtask) ──

  @Test
  @DisplayName("T89 plugin() 返回即已激活：副作用同步可见（主线 rc.9 至少延迟一个微任务，§5 偏差 11）")
  void pluginActivatesSynchronously() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.plugin(
        c -> {
          trace.add("body");
          return Disposables.none();
        });
    assertEquals(List.of("body"), trace, "plugin() 返回时 effect 函数必须已执行完毕");
    ctx.dispose();
  }

  @Test
  @DisplayName("T89 inject() 声明时依赖已满足则同步激活，handle 返回前 body 已落地")
  void injectActivatesSynchronouslyWhenSatisfied() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.provide(new Engine());
    Disposable declaration =
        ctx.inject(
            Engine.class,
            (c, engine) -> {
              trace.add("body");
              return Disposables.none();
            });
    assertEquals(List.of("body"), trace, "满足即激活：声明调用本身完成激活");
    declaration.dispose();
    ctx.dispose();
  }

  // ── T90: fully serial LIFO unload across top-level effect groups (upstream runs them
  // concurrently via Promise.all; within one effect group both are LIFO-serial) ──

  @Test
  @DisplayName(
      "T90 fiber 域顶层各组 disposer 全量串行 LIFO：extra 运行时监听仍生效；provide 撤销(含 stop)整体完成后监听才消失（§5 偏差 12）")
  void topLevelEffectGroupsUnloadSeriallyLifo() {
    Context ctx = Contexts.create();
    List<String> timeline = new ArrayList<>();
    Stoppable service = new Stoppable(timeline);
    Disposable handle =
        ctx.plugin(
            c -> {
              c.on(Note.class, note -> timeline.add("listener")); // top-level group 1
              c.provide(service); // top-level group 2 (stop() runs on its removal)
              return Disposables.of(
                  () -> {
                    // group 3 (the returned extra, tracked last): while it reverts, the earlier
                    // groups' effects must still be fully live - upstream's concurrent
                    // Promise.all unload would interleave the groups instead
                    c.emit(new Note("x"));
                    timeline.add("extra");
                  });
            });
    handle.dispose();
    assertEquals(
        List.of("listener", "extra", "stop"),
        timeline,
        "顶层各组必须严格串行：extra 运行时监听器仍在，provide 的撤销完全完成(含 stop)");
    assertTrue(ctx.find(Stoppable.class).isEmpty(), "卸载后 binding 必须离店");
    ctx.dispose();
  }

  // ── T91: withdrawal store timing - the dependent resolves the binding during teardown, the
  // provider's own later disposers still see its binding, and only after the unload completes is
  // the binding gone for third parties (equivalent to upstream's delete-then-snapshot order,
  // boundary 14 / D20) ──

  @Test
  @DisplayName("T91 撤销排空期间：依赖者 teardown 读到依赖，提供者后续 disposer 读到自身 binding，完成后第三方不可见")
  void withdrawalKeepsBindingResolvableThroughTeardown() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(new Engine());
              // the extra disposer is tracked after the provide, so it reverts before the
              // provide's removal (LIFO) while the provider's own binding is still resolvable
              return Disposables.of(
                  () -> trace.add("late:" + (c.find(Engine.class).isPresent() ? "bound" : "gone")));
            });
    Disposable dependent =
        ctx.inject(
            Engine.class,
            (c, engine) ->
                Disposables.of(
                    () -> {
                      // dependent teardown: the withdrawn binding is still resolvable
                      trace.add(
                          "dependent:" + (c.find(Engine.class).isPresent() ? "bound" : "gone"));
                    }));
    provider.dispose(); // withdraws the supply: dependent drains first, effects revert after
    assertEquals(
        List.of("dependent:bound", "late:bound"),
        trace,
        "排空次序：依赖者先卸且可解析依赖；提供者后续 disposer 运行时自身 binding 仍在");
    assertTrue(ctx.find(Engine.class).isEmpty(), "卸载完成后第三方必须不可见");
    dependent.dispose();
    ctx.dispose();
  }

  // ── T92: a failed fiber never re-enters (boundary 13); recovery is re-declaration, not an
  // update() - upstream clears _error and restarts through fiber.update() (deviation 13) ──

  @Test
  @DisplayName("T92 failed fiber 终态无 update 恢复；等价路径 = 撤销声明后重新声明新 fiber 正常激活（§5 偏差 13）")
  void failedFiberRecoversOnlyThroughRedeclaration() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Disposable supply =
        ctx.plugin(
            c -> {
              c.provide(new Engine());
              return Disposables.none();
            });
    Disposable failed =
        ctx.inject(
            Engine.class,
            (c, engine) -> {
              trace.add("attempt");
              throw new IllegalStateException("boom");
            });
    assertEquals(List.of("attempt"), trace, "满足即激活，失败一次");
    supply.dispose();
    Disposable resupplied =
        ctx.plugin(
            c -> {
              c.provide(new Engine());
              return Disposables.none();
            });
    assertEquals(List.of("attempt"), trace, "依赖重新满足不得重入 failed fiber（边界 13）");
    failed.dispose(); // retire the failed declaration
    Disposable recovered =
        ctx.inject(
            Engine.class,
            (c, engine) -> {
              trace.add("recovered");
              return Disposables.none();
            });
    assertEquals(
        List.of("attempt", "recovered"), trace, "恢复等价路径：重新声明的 fiber 正常激活（主线为 update() 清错重启）");
    recovered.dispose();
    resupplied.dispose();
    ctx.dispose();
  }

  // ── T93: event-visibility matrix - per-context buses with child-to-root bubbling (D3).
  // Upstream rc.9 keeps one shared bus: any emit reaches listeners registered on any context;
  // here a sibling never sees a sibling and the root never sees a child (deviation 14) ──

  @Test
  @DisplayName("T93 事件可见性矩阵：child.emit→root.on 见、root.emit→child.on 不见、sibling 互不见（§5 偏差 14，D3）")
  void eventVisibilityMatrix() {
    Context root = Contexts.create();
    Context child1 = root.fork();
    Context child2 = root.fork();
    List<String> trace = new ArrayList<>();
    root.on(Note.class, note -> trace.add("root"));
    child1.on(Note.class, note -> trace.add("child1"));
    child2.on(Note.class, note -> trace.add("child2"));

    child1.emit(new Note("a"));
    assertEquals(List.of("child1", "root"), trace, "子 emit 冒泡到根");

    trace.clear();
    root.emit(new Note("b"));
    assertEquals(List.of("root"), trace, "根 emit 不得下行到子总线");

    trace.clear();
    child2.emit(new Note("c"));
    assertEquals(List.of("child2", "root"), trace, "兄弟子树互不可见（主线共享总线则互见）");
    root.dispose();
  }

  // ── fixtures ──

  private record Note(String text) {}

  private static final class Engine {}

  private static final class Stoppable implements Service {

    private final List<String> timeline;

    Stoppable(List<String> timeline) {
      this.timeline = timeline;
    }

    @Override
    public void stop() {
      timeline.add("stop");
    }
  }
}
