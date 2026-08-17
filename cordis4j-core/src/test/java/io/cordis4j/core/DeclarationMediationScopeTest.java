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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T47: declaration mediation (D13) checks against the context the lookup resolves through, not the
 * context the declaration was made in - a realm-declared fiber reading the default key through the
 * root is rejected, and a default-declared fiber reading a realm key through an isolated child is
 * rejected too, while the ordinary in-realm path stays open.
 */
class DeclarationMediationScopeTest {

  static class Config {
    final String name;

    Config(String name) {
      this.name = name;
    }
  }

  @Test
  @DisplayName("T47 realm 声明的 fiber 经 root() 取 default 键被拒（解析基=取数 context）")
  void realmDeclarationCannotReadTheDefaultKeyThroughTheRoot() {
    Context root = Contexts.create();
    root.provide(new Config("default"));
    Context realm = root.isolate(Config.class, "iso");
    List<String> trace = new ArrayList<>();

    realm.inject(
        Set.of(ServiceKey.of(Config.class)),
        ctx -> {
          trace.add("realm activated");
          // Reading through the root: the realm override does not apply there, so the lookup
          // would resolve the DEFAULT key the fiber never declared.
          try {
            ctx.root().get(Config.class);
            trace.add("root read leaked");
          } catch (InactiveAccessException denied) {
            trace.add("root read denied");
          }
          return Disposables.none();
        });
    root.provide(ServiceKey.of(Config.class, "iso"), new Config("isolated"));
    assertEquals(
        List.of("realm activated", "root read denied"),
        trace,
        "跨 context 取数必须按解析链重写后的键与声明集比较（D13）");
  }

  @Test
  @DisplayName("T47 default 声明的 fiber 在 isolate 子 context 上取 realm 键被拒")
  void defaultDeclarationCannotReadARealmKeyThroughTheChild() {
    Context root = Contexts.create();
    root.provide(ServiceKey.of(Config.class, "iso"), new Config("isolated"));
    List<String> trace = new ArrayList<>();

    root.inject(
        Config.class,
        (ctx, config) -> {
          trace.add("default saw " + config.name);
          Context child = ctx.isolate(Config.class, "iso");
          try {
            child.get(ServiceKey.of(Config.class, "iso"));
            trace.add("realm read leaked");
          } catch (InactiveAccessException denied) {
            trace.add("realm read denied");
          }
          return Disposables.none();
        });
    root.provide(new Config("default"));
    assertEquals(
        List.of("default saw default", "realm read denied"),
        trace,
        "子 context 的 realm 重写键不在 default 声明集内，必须拒绝");
  }

  @Test
  @DisplayName("T47 常规路径不收紧：realm 声明在 realm 内取数、default 声明在 root 取数都放行")
  void ordinaryPathsStayOpen() {
    Context root = Contexts.create();
    Context realm = root.isolate(Config.class, "iso");
    List<String> trace = new ArrayList<>();

    realm.inject(
        Set.of(ServiceKey.of(Config.class)),
        ctx -> {
          trace.add("in-realm:" + ctx.get(ServiceKey.of(Config.class, "iso")).name);
          return Disposables.none();
        });
    root.inject(
        Set.of(ServiceKey.of(Config.class)),
        ctx -> {
          trace.add("at-root:" + ctx.get(Config.class).name);
          return Disposables.none();
        });

    root.provide(new Config("default"));
    root.provide(ServiceKey.of(Config.class, "iso"), new Config("isolated"));
    assertTrue(trace.contains("in-realm:isolated"), "realm 内取数必须照常放行");
    assertTrue(trace.contains("at-root:default"), "root 内取数必须照常放行");
    assertEquals(2, trace.size());
  }

  @Test
  @DisplayName("T47 未声明键的拒绝在其他 context 上同样生效")
  void undeclaredKeyIsRejectedWhereverItResolves() {
    Context root = Contexts.create();
    Context realm = root.isolate(Config.class, "iso");
    root.provide(new Config("default"));

    realm.inject(
        Config.class,
        (ctx, config) -> {
          assertThrows(
              InactiveAccessException.class,
              () -> ctx.fork().get(ServiceKey.of(Config.class, "other")),
              "fork 出的 context 上取未声明键同样必须拒绝");
          return Disposables.none();
        });
    root.provide(ServiceKey.of(Config.class, "iso"), new Config("isolated"));
  }
}
