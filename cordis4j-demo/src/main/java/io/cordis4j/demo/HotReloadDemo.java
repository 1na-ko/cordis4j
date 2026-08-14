/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.demo;

import io.cordis4j.core.ComponentEntry;
import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.Loader;
import io.cordis4j.core.LoaderConfig;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration-level hot reload (the "save re-applies" shape of paper Section 5.2.2 HMR, at the
 * configuration level):
 *
 * <p>The declarative configuration reconciles by id-keyed diffing - new entries load, vanished
 * entries unload, changed instances hot-reload - and the whole reconcile is transactional: any
 * failing step rolls back to the previous running configuration.
 */
public final class HotReloadDemo {

  record Feature(String name) {}

  static final class FeaturePlugin implements io.cordis4j.core.Plugin {
    private final String name;
    private final List<String> log;

    FeaturePlugin(String name, List<String> log) {
      this.name = name;
      this.log = log;
    }

    @Override
    public Disposable apply(Context ctx) {
      log.add("load " + name);
      ctx.provide(ServiceKey.of(Feature.class, name), new Feature(name));
      return Disposables.of(() -> log.add("unload " + name));
    }
  }

  public static void main(String[] args) {
    Context app = Contexts.create();
    List<String> log = new ArrayList<>();
    Loader loader = Loader.of(app);

    log.add("v1: greet + search");
    loader.reconcile(
        LoaderConfig.of(
            ComponentEntry.of("greet", new FeaturePlugin("greet", log)),
            ComponentEntry.of("search", new FeaturePlugin("search", log))));

    log.add("v2: search reimplemented, tools added, greet removed");
    loader.reconcile(
        LoaderConfig.of(
            ComponentEntry.of("search", new FeaturePlugin("search-v2", log)),
            ComponentEntry.of("tools", new FeaturePlugin("tools", log))));

    log.add("v3: failing config -> transactional rollback to v2");
    try {
      loader.reconcile(
          LoaderConfig.of(
              ComponentEntry.of("search", new FeaturePlugin("search-v2", log)),
              ComponentEntry.of("tools", new FeaturePlugin("tools", log)),
              ComponentEntry.of(
                  "broken",
                  ctx -> {
                    throw new IllegalStateException("cannot load broken plugin");
                  })));
    } catch (IllegalStateException failure) {
      log.add("reconcile failed: " + failure.getMessage() + " -> rolled back");
    }
    log.add("search still serves: " + app.get(ServiceKey.of(Feature.class, "search-v2")).name());
    loader.dispose();
    log.forEach(System.out::println);
  }

  private HotReloadDemo() {}
}
