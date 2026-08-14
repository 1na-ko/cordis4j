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
 * Reactive composition demo (the Koishi-console pattern: disabling a plugin reverts its effects in
 * place):
 *
 * <p>The cache component declares its database dependency via {@code inject}: it comes online while
 * the database plugin is present and goes offline when that plugin is disabled - the reactive
 * lifecycle of paper Algorithms 3/5 plus the Theorem 63 drain order - with no manual orchestration.
 */
public final class ReactiveCompositionDemo {

  public static void main(String[] args) {
    Context app = Contexts.create();
    List<String> log = new ArrayList<>();

    // Declarative component: active only while the database is present (paper Section 3.2.2)
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
