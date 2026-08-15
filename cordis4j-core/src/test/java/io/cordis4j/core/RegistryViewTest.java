/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T32: the registry view (decision D24) - {@code services()} snapshots the bindings a context
 * provides, keyed by the effective store key; ancestors are not included.
 */
class RegistryViewTest {

  record Marker(String name) {}

  @Test
  @DisplayName("T32 services() 快照本 context 绑定；覆盖与移除实时反映；不含祖先绑定")
  void snapshotsProvidedBindings() {
    Context root = Contexts.create();
    Context child = root.fork();
    root.plugin(
        c -> {
          c.provide(new Marker("root-only"));
          return Disposables.none();
        });

    assertEquals(Map.of(), child.services(), "子 context 快照不得包含祖先绑定");
    assertEquals(1, root.services().size());

    Disposable removal = child.provide(new Marker("mine"));
    assertEquals(1, child.services().size(), "本 context 绑定必须出现在快照");
    assertEquals(
        "mine", ((Marker) child.services().get(ServiceKey.of(Marker.class))).name(), "快照必须携带绑定值");

    Disposable overwrite = child.provide(new Marker("replaced"));
    assertEquals(
        "replaced",
        ((Marker) child.services().get(ServiceKey.of(Marker.class))).name(),
        "覆盖后快照反映当前值");
    overwrite.dispose();
    assertTrue(child.services().isEmpty(), "移除后快照必须清空");
    removal.dispose();
  }

  @Test
  @DisplayName("T32 isolate realm 重定向体现在快照键上")
  void snapshotUsesEffectiveStoreKeys() {
    Context ctx = Contexts.create();
    ctx.isolate(Marker.class, "tenant-a");
    ctx.provide(ServiceKey.of(Marker.class, "tenant-a"), new Marker("tenant"));

    Map<ServiceKey<?>, Object> snapshot = ctx.services();
    assertEquals(1, snapshot.size());
    ServiceKey<?> key = snapshot.keySet().iterator().next();
    assertEquals("tenant-a", key.qualifier(), "快照键必须是 realm 重定向后的 storeKey");
  }

  @Test
  @DisplayName("T32 快照不可变；dispose 后访问拒绝")
  void snapshotIsImmutableAndGuarded() {
    Context ctx = Contexts.create();
    ctx.provide(new Marker("m"));
    Map<ServiceKey<?>, Object> snapshot = ctx.services();
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.put(ServiceKey.of(Marker.class), new Marker("x")),
        "快照必须不可变");

    ctx.dispose();
    assertThrows(IllegalStateException.class, ctx::services, "dispose 后必须拒绝");
  }
}
