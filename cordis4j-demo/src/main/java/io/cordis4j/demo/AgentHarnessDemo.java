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
 * Agent Harness 演示（对标 DeepSeek Harness 的「一切皆插件」，发挥 4j 优势）：
 *
 * <ul>
 *   <li>模型、工具注册表、会话全部是 cordis4j 插件；
 *   <li>工具插件用 {@code inject} 声明依赖（Model 与 ToolRegistry），任一被卸载则工具自动下线， 回来又自动上线（反应式组合）；
 *   <li>Agent loop 跑在虚拟线程上（{@code pluginAsync}），会话结束即被中断落地（可逆任务）；
 *   <li>会话 {@code dispose} 一次性撤销全部副作用（时空可组合性的完整闭环）。
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
      // 声明式 fiber 的句柄并入本插件域：卸载插件即退役并卸载 fiber
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

    // 会话（fork 隔离）：装配工具 + agent loop
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
                          // 一轮 agent loop：读取当前工具集，交给模型
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
    calculator.dispose(); // 动态卸载工具：注册表反应式更新（tool down）
    quietSleep(80); // next iteration sees only web_search

    session.dispose(); // 会话结束：loop 中断落地、其余工具下线、全部副作用一次性撤销
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
