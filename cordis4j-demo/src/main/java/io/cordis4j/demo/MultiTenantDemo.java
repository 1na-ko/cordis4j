/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.demo;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.Plugin;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayList;
import java.util.List;

/**
 * 多租户隔离演示（对标 DeepSeek Harness 的会话沙箱 / 论文 §3.2.2 isolate 派生）：
 *
 * <p>根上下文提供共享基础设施；每个租户是 {@code isolate} 派生的子树——同一服务类型在各自 realm 内解析到各自的实现，互不可见；销毁租户即整体丢弃，根不受影响。
 */
public final class MultiTenantDemo {

  interface Secrets {
    String tenantKey();
  }

  record TenantSecrets(String tenant, String key) implements Secrets {
    @Override
    public String tenantKey() {
      return key;
    }
  }

  public static void main(String[] args) {
    List<String> log = new ArrayList<>();
    Context app = Contexts.create();

    // 租户 alice：隔离域内提供自己的 Secrets；查询自动路由到该 realm
    Context alice = app.isolate(Secrets.class, "alice");
    alice.plugin(new SecretsPlugin("alice"));
    // 租户 bob：同名服务类型，独立 realm
    Context bob = app.isolate(Secrets.class, "bob");
    bob.plugin(new SecretsPlugin("bob"));

    log.add("alice sees: " + alice.get(Secrets.class).tenantKey());
    log.add("bob   sees: " + bob.get(Secrets.class).tenantKey());
    log.add("app   sees secrets? " + app.find(Secrets.class).isPresent());

    // 会话内再 fork 一层：孙上下文继承租户 realm
    Context aliceSession = alice.fork();
    log.add("alice session inherits: " + aliceSession.get(Secrets.class).tenantKey());

    alice.dispose(); // 整棵租户子树丢弃
    log.add("after alice.dispose, bob still: " + bob.get(Secrets.class).tenantKey());

    log.forEach(System.out::println);
  }

  static final class SecretsPlugin implements Plugin {
    private final String tenant;

    SecretsPlugin(String tenant) {
      this.tenant = tenant;
    }

    @Override
    public Disposable apply(Context ctx) {
      ctx.provide(ServiceKey.of(Secrets.class), new TenantSecrets(tenant, "key-of-" + tenant));
      return Disposables.none();
    }
  }

  private MultiTenantDemo() {}
}
