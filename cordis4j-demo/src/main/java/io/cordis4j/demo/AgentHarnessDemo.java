/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.demo;

import io.cordis4j.core.AsyncPlugin;
import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Agent Harness demo (the DeepSeek Harness "everything is a plugin" pattern, in Java):
 *
 * <ul>
 *   <li>the model, the tool registry, and the session are all cordis4j plugins;
 *   <li>tool plugins declare their dependencies ({@code Model} and {@code ToolRegistry}) with
 *       {@code inject}: when either is unloaded the tool goes offline automatically, and comes back
 *       online when it returns (reactive composition);
 *   <li>the agent loop runs on a virtual thread ({@code pluginAsync}); ending the session diverts
 *       and lands it (a revertible task);
 *   <li>disposing the session reverts every side effect at once (the full spatiotemporal
 *       composability loop).
 * </ul>
 */
public final class AgentHarnessDemo {

  /** A deterministic stand-in for an LLM. */
  record Model(String name) {
    String reply(List<String> tools, String prompt) {
      return "[" + name + " | tools=" + tools + "] " + prompt;
    }
  }

  interface ToolRegistry {
    void register(String tool);

    void unregister(String tool);

    List<String> tools();
  }

  static final class HarnessPlugin implements io.cordis4j.core.Plugin {
    @Override
    public Disposable apply(Context ctx) {
      ctx.provide(ServiceKey.of(Model.class), new Model("dsv4-flash"));
      ToolRegistry registry =
          new ToolRegistry() {
            private final Map<String, Boolean> tools = new ConcurrentHashMap<>();

            @Override
            public void register(String tool) {
              tools.put(tool, Boolean.TRUE);
            }

            @Override
            public void unregister(String tool) {
              tools.remove(tool);
            }

            @Override
            public List<String> tools() {
              return List.copyOf(tools.keySet());
            }
          };
      ctx.provide(ServiceKey.of(ToolRegistry.class), registry);
      return Disposables.none();
    }
  }

  /** A tool plugin: reactive on Model + ToolRegistry; supplies itself into the registry. */
  static final class ToolPlugin implements io.cordis4j.core.Plugin {
    private final String tool;
    private final List<String> log;

    ToolPlugin(String tool, List<String> log) {
      this.tool = tool;
      this.log = log;
    }

    @Override
    public Disposable apply(Context ctx) {
      // The declarative fiber's handle joins this plugin domain: unloading the plugin retires
      // and unloads the fiber
      return ctx.inject(
          ServiceKey.of(Model.class),
          ServiceKey.of(ToolRegistry.class),
          (c, model, registry) -> {
            registry.register(tool);
            log.add("tool up: " + tool + " (backed by " + model.name() + ")");
            return Disposables.of(
                () -> {
                  registry.unregister(tool);
                  log.add("tool down: " + tool);
                });
          });
    }
  }

  public static void main(String[] args) throws Exception {
    List<String> log = new ArrayList<>();
    Context app = Contexts.create();
    app.plugin(new HarnessPlugin());

    // Session (fork isolation): assemble the tools plus the agent loop
    Context session = app.fork();
    Disposable calculator = session.plugin(new ToolPlugin("calculator", log));
    session.plugin(new ToolPlugin("web_search", log));

    CountDownLatch loopStarted = new CountDownLatch(1);
    Disposable agentLoop =
        session.pluginAsync(
            (AsyncPlugin)
                ctx -> {
                  ctx.spawn(
                      () -> {
                        loopStarted.countDown();
                        while (!ctx.currentFiber()
                            .map(io.cordis4j.core.FiberHandle::isDiverted)
                            .orElse(true)) {
                          // one agent-loop round: read the current tool set, hand it to the model
                          ToolRegistry registry = ctx.get(ToolRegistry.class);
                          Model model = ctx.get(Model.class);
                          log.add(model.reply(registry.tools(), "user: 1+1=?"));
                          quietSleep(50);
                        }
                        log.add("agent loop landed");
                      });
                  return Disposables.none();
                });
    loopStarted.await();

    quietSleep(80); // let one loop iteration observe both tools
    calculator.dispose(); // unload a tool live: the registry updates reactively (tool down)
    quietSleep(80); // next iteration sees only web_search

    session.dispose(); // session over: the loop is diverted and lands, the remaining tools go
    // offline, all side effects are reverted at once
    agentLoop.dispose();

    log.forEach(System.out::println);
  }

  private static void quietSleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private AgentHarnessDemo() {}
}
