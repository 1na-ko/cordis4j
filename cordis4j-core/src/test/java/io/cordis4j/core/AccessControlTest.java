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

/** T14: declaration mediation (paper Algorithm 6): a declarative fiber sees only its own keys. */
class AccessControlTest {

  record Vault(String secret) {}

  record PublicApi() {}

  record Hidden() {}

  record Note(String text) {}

  @Test
  @DisplayName("T14 声明式 fiber 内：声明的键与自供的键可访问，未声明的键抛 InactiveAccessException")
  void declarativeFiberSeesOnlyDeclaredKeys() {
    Context ctx = Contexts.create();
    ctx.provide(new PublicApi());
    ctx.provide(new Hidden());
    ctx.provide(new Vault("s3cret"));
    List<String> trace = new ArrayList<>();

    ctx.inject(
        Vault.class,
        (c, vault) -> {
          trace.add("declared:" + c.get(Vault.class).secret());
          c.provide(new PublicApi()); // the fiber's own supply is visible to it
          trace.add("own:" + (c.find(PublicApi.class).isPresent() ? "visible" : "blocked"));
          try {
            c.get(Hidden.class); // blocked: provided elsewhere, not declared
            trace.add("undeclared:allowed");
          } catch (InactiveAccessException rejected) {
            trace.add("undeclared:blocked");
          }
          return Disposables.none();
        });
    assertEquals(List.of("declared:s3cret", "own:visible", "undeclared:blocked"), trace);
  }

  @Test
  @DisplayName("T14 普通插件（无声明）不受访问中介限制")
  void plainPluginsUnrestricted() {
    Context ctx = Contexts.create();
    ctx.provide(new Vault("s3cret"));
    List<String> trace = new ArrayList<>();
    ctx.plugin(
        c -> {
          trace.add(c.get(Vault.class).secret());
          return Disposables.none();
        });
    assertEquals(List.of("s3cret"), trace);
  }

  @Test
  @DisplayName("T14 find 同样受声明校验（get 与 find 一致）")
  void findAlsoMediated() {
    Context ctx = Contexts.create();
    ctx.provide(new PublicApi());
    List<String> trace = new ArrayList<>();
    ctx.inject(
        Vault.class,
        (c, vault) -> {
          try {
            c.find(PublicApi.class);
            trace.add("find:allowed");
          } catch (InactiveAccessException rejected) {
            trace.add("find:blocked");
          }
          return Disposables.none();
        });
    ctx.provide(new Vault("s3cret"));
    assertEquals(List.of("find:blocked"), trace);
  }

  @Test
  @DisplayName("T14 事件注册与发射不受声明校验影响（只有服务访问被中介）")
  void eventsNotMediated() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.inject(
        Vault.class,
        (c, vault) -> {
          c.on(Note.class, note -> trace.add("heard:" + note.text()));
          c.emit(new Note("hi")); // bubbles to ancestors, no mediation
          return Disposables.none();
        });
    ctx.provide(new Vault("s3cret"));
    assertEquals(List.of("heard:hi"), trace);
  }

  @Test
  @DisplayName("T88 嵌套声明中介深度偏差：子声明 fiber 读父声明的键被拒（§5 偏差 10，保守方向）")
  void nestedDeclarationDoesNotInheritTheParentScope() {
    Context root = Contexts.create();
    root.provide(new Vault("s3cret"));
    root.provide(new PublicApi());
    List<String> trace = new ArrayList<>();

    root.inject(
        Vault.class,
        (c, vault) -> {
          c.inject(
              PublicApi.class,
              (inner, api) -> {
                try {
                  inner.get(Vault.class); // declared by the parent fiber, not by this one
                  trace.add("nested:allowed");
                } catch (InactiveAccessException rejected) {
                  trace.add("nested:blocked");
                }
                return Disposables.none();
              });
          return Disposables.none();
        });
    assertEquals(List.of("nested:blocked"), trace);
  }
}
