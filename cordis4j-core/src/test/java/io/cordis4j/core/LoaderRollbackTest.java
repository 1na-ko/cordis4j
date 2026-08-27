/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T69-T74: the failure half of the transactional reconcile (paper Algorithm 10; boundaries 21 and
 * 32): compensation runs in reverse order, an isolation realm's active count swings back with the
 * restored entry (the realm survives its failed reload), a restoration failure attaches as a
 * suppressed exception instead of being swallowed, freshly created realms are discarded when their
 * reconcile fails, and dispose drains realms all the way to their derived context.
 */
class LoaderRollbackTest {

  /** Marker service type naming isolation realms. */
  static class Marker {}

  /** A plugin whose load/unload land in the shared trace. */
  static final class Traced implements Plugin {
    private final String name;
    private final List<String> trace;

    Traced(String name, List<String> trace) {
      this.name = name;
      this.trace = trace;
    }

    @Override
    public Disposable apply(Context ctx) {
      trace.add("load " + name);
      return Disposables.of(() -> trace.add("unload " + name));
    }
  }

  /** Applies cleanly once; the rollback's re-apply of the same instance fails. */
  static final class FailingOnSecondApply implements Plugin {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public Disposable apply(Context ctx) {
      if (calls.incrementAndGet() > 1) {
        throw new IllegalStateException("restore-boom");
      }
      return Disposables.none();
    }
  }

  private static Plugin broken() {
    return ctx -> {
      throw new IllegalStateException("boom");
    };
  }

  private static ComponentSpec.Isolate realm(String label, ComponentSpec... children) {
    return new ComponentSpec.Isolate(Marker.class, label, List.of(children));
  }

  @Test
  @DisplayName("T69 新增条目中途失败：补偿按装载逆序撤销本次新增，既有集合不动（边界 21 失败半边）")
  void additionsCompensateInReverseOrder() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Loader loader = Loader.of(ctx);
    Traced stable = new Traced("stable", trace);
    loader.reconcile(LoaderConfig.of(ComponentEntry.of("stable", stable)));
    trace.clear();

    assertThrows(
        IllegalStateException.class,
        () ->
            loader.reconcile(
                LoaderConfig.of(
                    ComponentEntry.of("stable", stable),
                    ComponentEntry.of("a", new Traced("a", trace)),
                    ComponentEntry.of("b", new Traced("b", trace)),
                    ComponentEntry.of("bad", broken()))));

    assertEquals(
        List.of("load a", "load b", "unload b", "unload a"),
        trace,
        "补偿必须按装载的逆序撤销本次新增（b 先于 a），既有条目不动");

    loader.reconcile(
        LoaderConfig.of(
            ComponentEntry.of("stable", stable), ComponentEntry.of("a", new Traced("a2", trace))));
    assertEquals(
        List.of("load a", "load b", "unload b", "unload a", "load a2"),
        trace,
        "回滚后的 loader 必须仍可正常调和");
  }

  @Test
  @DisplayName("T70 域内 reload 中途失败：补偿撤销新实例、旧实例恢复进原域，active 计数回摆（边界 21/32 失败半边）")
  void realmReloadFailureRestoresThePreviousEntryInTheSameRealm() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Loader loader = Loader.of(ctx);
    Traced v1 = new Traced("v1", trace);
    List<ComponentSpec> initial = List.of(realm("r", new ComponentSpec.Entry("a", v1)));
    loader.reconcileTree(initial);
    trace.clear();

    assertThrows(
        IllegalStateException.class,
        () ->
            loader.reconcileTree(
                List.of(
                    realm(
                        "r",
                        new ComponentSpec.Entry("a", new Traced("v2", trace)),
                        new ComponentSpec.Entry("bad", broken())))));

    assertEquals(
        List.of("unload v1", "load v2", "unload v2", "load v1"),
        trace,
        "回滚必须逆序：撤销新实例 v2，再把旧实例 v1 恢复进原隔离域");

    loader.reconcileTree(initial);
    assertEquals(
        List.of("unload v1", "load v2", "unload v2", "load v1"),
        trace,
        "恢复后的条目必须仍登记在原域（计数回摆、域未销毁）：同实例二次 reconcile 不重载");
  }

  @Test
  @DisplayName("T71 恢复动作自身失败：以 suppressed 附着在原失败上，而非吞掉（边界 21 失败半边）")
  void restorationFailureIsAttachedAsSuppressed() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    loader.reconcile(LoaderConfig.of(ComponentEntry.of("x", new FailingOnSecondApply())));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                loader.reconcile(
                    LoaderConfig.of(
                        ComponentEntry.of("x", c -> Disposables.none()),
                        ComponentEntry.of("bad", broken()))));
    assertEquals("boom", failure.getMessage(), "主异常必须是装载失败本身");
    assertEquals(1, failure.getSuppressed().length, "恢复失败必须恰好以 suppressed 附着");
    assertTrue(
        failure.getSuppressed()[0] instanceof IllegalStateException
            && "restore-boom".equals(failure.getSuppressed()[0].getMessage()),
        "恢复失败不得被吞掉");

    loader.reconcile(LoaderConfig.of(ComponentEntry.of("x", c -> Disposables.none())));
    // the entry whose restoration failed reloads cleanly once reported
  }

  @Test
  @DisplayName("T72 新建域内首装失败：域即刻丢弃，同 label 后续重建干净（边界 32 失败半边）")
  void freshlyCreatedRealmIsDiscardedWhenItsReconcileFails() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Loader loader = Loader.of(ctx);

    assertThrows(
        IllegalStateException.class,
        () ->
            loader.reconcileTree(List.of(realm("fresh", new ComponentSpec.Entry("bad", broken())))),
        "域内装载失败必须传播");

    loader.reconcileTree(
        List.of(realm("fresh", new ComponentSpec.Entry("ok", new Traced("ok", trace)))));
    assertEquals(List.of("load ok"), trace, "被丢弃的域不得污染其 label：同 label 重建后正常装载");
    loader.dispose();
    assertEquals(List.of("load ok", "unload ok"), trace, "dispose 必须卸载重建域中的条目");
  }

  @Test
  @DisplayName("T73 展平期重复 id：本次新建的域丢弃，既有域保留可复用（边界 21/32）")
  void duplicateIdDiscardsFreshRealmsButKeepsEstablishedOnes() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Loader loader = Loader.of(ctx);
    Traced ok = new Traced("ok", trace);
    Plugin filler = c -> Disposables.none();
    List<ComponentSpec> established = List.of(realm("kept", new ComponentSpec.Entry("ok", ok)));
    loader.reconcileTree(established);
    trace.clear();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            loader.reconcileTree(
                List.of(
                    realm(
                        "kept",
                        new ComponentSpec.Entry("dup", filler),
                        new ComponentSpec.Entry("dup", filler)),
                    realm(
                        "fresh",
                        new ComponentSpec.Entry("dup2", filler),
                        new ComponentSpec.Entry("dup2", filler)))),
        "展平后的重复 id 必须 fail-fast");
    assertEquals(List.of(), trace, "失败必须发生在任何装卸之前：旧集合不动");

    loader.reconcileTree(established);
    assertEquals(List.of(), trace, "既有域必须未被丢弃：同实例二次 reconcile 不重载");

    loader.reconcileTree(List.of(realm("fresh", new ComponentSpec.Entry("n", filler))));
    loader.dispose(); // the discarded fresh realm's label rebuilt cleanly and tears down cleanly
  }

  @Test
  @DisplayName("T74 dispose 排空隔离域：drained realm 的 derived context 确实销毁（边界 32/39）")
  void disposeDrainsRealmsToTheirDerivedContext() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    AtomicReference<Context> derived = new AtomicReference<>();
    loader.reconcileTree(
        List.of(
            realm(
                "drain",
                new ComponentSpec.Entry(
                    "c",
                    c -> {
                      derived.set(c);
                      return Disposables.none();
                    }))));
    assertTrue(derived.get() != null, "装载后必须已进入隔离域的 derived context");

    loader.dispose();
    assertThrows(
        IllegalStateException.class,
        () -> derived.get().baseUrl(),
        "dispose 排空的域必须销毁其 derived context（而非残留僵尸）");
  }

  @Test
  @DisplayName("T84 dispose 遇组件 teardown 失败：逆序卸载不中断其余，失败聚合为 DisposeException（边界 21/39）")
  void disposeAggregatesTeardownFailuresAndUnloadsTheRest() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Loader loader = Loader.of(ctx);
    loader.reconcile(
        LoaderConfig.of(
            ComponentEntry.of("a", new Traced("a", trace)),
            ComponentEntry.of(
                "bad",
                c ->
                    Disposables.of(
                        () -> {
                          trace.add("unload bad");
                          throw new IllegalStateException("teardown-boom");
                        })),
            ComponentEntry.of("c", new Traced("c", trace))));
    trace.clear();

    DisposeException failure = assertThrows(DisposeException.class, loader::dispose);
    assertEquals(
        List.of("unload c", "unload bad", "unload a"),
        trace,
        "dispose 必须逆序卸载全部条目：单个 teardown 失败不得中断其余卸载");
    assertEquals(1, failure.getSuppressed().length, "teardown 失败必须作为 suppressed 聚合传播");
    DisposeException teardown = (DisposeException) failure.getSuppressed()[0];
    assertTrue(
        teardown.getSuppressed().length == 1
            && teardown.getSuppressed()[0] instanceof IllegalStateException
            && "teardown-boom".equals(teardown.getSuppressed()[0].getMessage()),
        "最内层必须是组件 teardown 的原始失败（fiber 域一层、loader 一层，两层聚合都可见）");
  }
}
