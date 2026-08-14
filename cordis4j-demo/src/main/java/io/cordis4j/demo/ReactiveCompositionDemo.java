/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.demo;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayList;
import java.util.List;

/**
 * 反应式组合演示（对标 Koishi 控制台「禁用插件即原地撤销效应」）：
 *
 * <p>缓存组件通过 {@code inject} 声明对数据库的依赖——数据库插件在场时自动上线，被禁用时自动下线 （论文 Algorithm 3/5 的反应式生命周期 + Theorem 63
 * 的排空顺序），无需任何手工编排。
 */
public final class ReactiveCompositionDemo {

  public static void main(String[] args) {
    Context app = Contexts.create();
    List<String> log = new ArrayList<>();

    // 声明式组件：只有数据库在场时才激活（reactive coeffect, paper §3.2.2）
    Disposable cache =
        app.inject(
            Database.class,
            (ctx, db) -> {
              log.add("[cache] online on " + db.name());
              ctx.provide(new Cache(db));
              return Disposables.of(() -> log.add("[cache] offline"));
            });

    log.add("enable database plugin");
    Disposable database = app.plugin(new DatabasePlugin("postgres"));

    log.add("query through cache: " + app.get(Cache.class).query("select 1"));

    log.add("disable database plugin");
    database.dispose(); // withdrawal: the cache drains first (Theorem 63)

    log.add("re-enable database plugin");
    Disposable database2 = app.plugin(new DatabasePlugin("postgres-recovered"));

    database2.dispose();
    cache.dispose();

    log.forEach(System.out::println);
  }

  interface Database {
    String name();
  }

  record Postgres(String name) implements Database {}

  static final class DatabasePlugin implements io.cordis4j.core.Plugin {
    private final String name;

    DatabasePlugin(String name) {
      this.name = name;
    }

    @Override
    public Disposable apply(Context ctx) {
      ctx.provide(ServiceKey.of(Database.class), new Postgres(name));
      return Disposables.none();
    }
  }

  record Cache(Database db) {
    String query(String sql) {
      return "cached(" + db.name() + ") <- " + sql;
    }
  }

  private ReactiveCompositionDemo() {}
}
