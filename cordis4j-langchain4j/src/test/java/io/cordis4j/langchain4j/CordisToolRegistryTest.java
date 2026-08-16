/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.DisposeException;
import io.cordis4j.core.Inject;
import io.cordis4j.core.Injects;
import io.cordis4j.core.ServiceKey;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T25: the LangChain4j tool bridge - a session's tool set follows the reactive-coeffect lifecycle:
 * a declared tool appears when its binding resolves, vanishes when the relied binding is withdrawn,
 * and is replaced on re-provision; handles execute the current binding.
 */
class CordisToolRegistryTest {

  record EchoTool(String name, String tag) implements CordisTool {
    @Override
    public ToolSpecification toolSpecification() {
      return ToolSpecification.builder().name(name).description("echo").build();
    }

    @Override
    public String execute(String arguments) {
      return tag + ":" + arguments;
    }
  }

  static class BrokenSpecTool implements CordisTool {
    @Override
    public ToolSpecification toolSpecification() {
      throw new IllegalStateException("broken specification");
    }

    @Override
    public String execute(String arguments) {
      return "unreachable";
    }
  }

  static final class InjectedTool implements CordisTool {
    @Inject Record dependency;

    @Override
    public ToolSpecification toolSpecification() {
      return ToolSpecification.builder().name("injected").description("injected tool").build();
    }

    @Override
    public String execute(String arguments) {
      return dependency == null ? "no dependency" : dependency.value();
    }
  }

  record Record(String value) {}

  static final ServiceKey<CordisTool> ECHO_KEY = ServiceKey.of(CordisTool.class, "echo");

  private static ToolExecutionRequest request(String arguments) {
    return ToolExecutionRequest.builder().name("echo").arguments(arguments).build();
  }

  @Test
  @DisplayName("T25 绑定出现即工具可见；绑定撤回即消失；重新提供即替换（生命周期）")
  void lifecycleFollowsBindings() {
    Context session = Contexts.create();
    CordisToolRegistry registry = CordisToolRegistry.create(session);
    List<String> trace = new ArrayList<>();
    registry.onChange(() -> trace.add(registry.tools().size() + ""));
    registry.declare(ECHO_KEY);
    assertEquals(List.of(), trace, "未提供时不得激活，不得通知");

    Disposable provider =
        session.plugin(
            ctx -> {
              ctx.provide(ECHO_KEY, new EchoTool("echo", "v1"));
              return Disposables.none();
            });
    assertEquals(List.of("1"), trace, "绑定出现必须激活并通知一次");
    assertEquals("echo", registry.tools().get(0).specification().name());

    provider.dispose(); // withdrawal unloads the tool reactively
    assertEquals(List.of("1", "0"), trace, "绑定撤回必须卸载并通知");
    assertTrue(registry.tools().isEmpty());
    assertEquals(Optional.empty(), registry.tool("echo"));

    session.plugin(
        ctx -> {
          ctx.provide(ECHO_KEY, new EchoTool("echo", "v2"));
          return Disposables.none();
        });
    assertEquals(List.of("1", "0", "1"), trace, "重新提供必须重新激活");
    assertEquals("v2:{}", registry.tool("echo").orElseThrow().executor().apply(request("{}")));
  }

  @Test
  @DisplayName("T25 句柄是活视图：换绑后执行新实现；已卸载时执行抛 IllegalStateException")
  void handlesExecuteTheCurrentBinding() {
    Context session = Contexts.create();
    CordisToolRegistry registry = CordisToolRegistry.create(session);
    registry.declare(ECHO_KEY);

    Disposable provider =
        session.plugin(
            ctx -> {
              ctx.provide(ECHO_KEY, new EchoTool("echo", "v1"));
              return Disposables.none();
            });
    CordisToolHandle handle = registry.tool("echo").orElseThrow();
    assertEquals("v1:{\"a\":1}", handle.executor().apply(request("{\"a\":1}")), "参数必须原样传给工具");

    provider.dispose();
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> handle.executor().apply(request("{}")));
    assertTrue(failure.getMessage().contains("not active"), () -> failure.getMessage());

    session.plugin(
        ctx -> {
          ctx.provide(ECHO_KEY, new EchoTool("echo", "v2"));
          return Disposables.none();
        });
    assertEquals("v2:{}", handle.executor().apply(request("{}")), "旧句柄按名执行当前绑定");
  }

  @Test
  @DisplayName("T25 declare 句柄 dispose 永久退役；重复 declare 与非法参数 fail-fast")
  void retirementAndFailFast() {
    Context session = Contexts.create();
    CordisToolRegistry registry = CordisToolRegistry.create(session);
    Disposable declaration = registry.declare(ECHO_KEY);
    assertThrows(
        IllegalArgumentException.class, () -> registry.declare(ECHO_KEY), "重复 declare 必须拒绝");

    Disposable provider =
        session.plugin(
            ctx -> {
              ctx.provide(ECHO_KEY, new EchoTool("echo", "v1"));
              return Disposables.none();
            });
    assertEquals(1, registry.tools().size());
    declaration.dispose();
    assertTrue(registry.tools().isEmpty(), "退役必须卸载当前激活的工具");
    provider.dispose(); // free the key so a successor could provide it again
    session.plugin(
        ctx -> {
          ctx.provide(ECHO_KEY, new EchoTool("echo", "v1"));
          return Disposables.none();
        });
    assertTrue(registry.tools().isEmpty(), "退役的声明永不重激活（T11 语义）");

    assertThrows(NullPointerException.class, () -> CordisToolRegistry.create(null));
    assertThrows(NullPointerException.class, () -> registry.declare(null));
    assertThrows(NullPointerException.class, () -> registry.onChange(null));
    assertThrows(NullPointerException.class, () -> registry.tool(null));
  }

  @Test
  @DisplayName("T25 监听器异常被隔离：不影响激活、不影响其他监听器")
  void listenerFailureIsIsolated() {
    Context session = Contexts.create();
    CordisToolRegistry registry = CordisToolRegistry.create(session);
    AtomicInteger healthy = new AtomicInteger();
    registry.onChange(
        () -> {
          throw new IllegalStateException("listener bug");
        });
    registry.onChange(healthy::incrementAndGet);
    registry.declare(ECHO_KEY);

    Disposable provider =
        session.plugin(
            ctx -> {
              ctx.provide(ECHO_KEY, new EchoTool("echo", "v1"));
              return Disposables.none();
            });
    assertEquals(1, registry.tools().size(), "抛异常的监听器不得失败激活");
    assertEquals(1, healthy.get(), "其他监听器必须照常收到通知");
    provider.dispose();
    assertEquals(2, healthy.get(), "卸载通知同样到达");
  }

  @Test
  @DisplayName("T25 规格构建失败的激活被路由（D14）：不传播、工具缺席、永不重试")
  void brokenSpecificationIsRouted() {
    Context session = Contexts.create();
    CordisToolRegistry registry = CordisToolRegistry.create(session);
    registry.declare(ECHO_KEY);

    Disposable first =
        session.plugin(
            ctx -> {
              ctx.provide(ECHO_KEY, new BrokenSpecTool());
              return Disposables.none();
            });
    assertTrue(registry.tools().isEmpty(), "失败的激活不得暴露工具");
    first.dispose();

    session.plugin(
        ctx -> {
          ctx.provide(ECHO_KEY, new BrokenSpecTool());
          return Disposables.none();
        });
    assertTrue(registry.tools().isEmpty(), "失败冻结的声明不得重试（D14）");
  }

  @Test
  @DisplayName("T25 与注解式注入组合（D21）：工具的依赖字段随会话数据库增减")
  void composesWithAnnotationInjection() {
    Context session = Contexts.create();
    CordisToolRegistry registry = CordisToolRegistry.create(session);
    registry.declare(ServiceKey.of(CordisTool.class, "injected"));
    InjectedTool tool = new InjectedTool();
    session.plugin(
        ctx -> {
          Injects.injectFields(ctx, tool);
          ctx.provide(ServiceKey.of(CordisTool.class, "injected"), tool);
          return Disposables.none();
        });
    assertEquals(
        "no dependency", registry.tool("injected").orElseThrow().executor().apply(request("{}")));

    Disposable database =
        session.plugin(
            ctx -> {
              ctx.provide(new Record("session-data"));
              return Disposables.none();
            });
    assertEquals(
        "session-data",
        registry.tool("injected").orElseThrow().executor().apply(request("{}")),
        "依赖到位后注入字段必须填充");

    database.dispose(); // the snapshot clears, the tool itself stays active
    assertEquals(
        "no dependency",
        registry.tool("injected").orElseThrow().executor().apply(request("{}")),
        "依赖撤回后注入字段必须清空");
    assertEquals(1, registry.tools().size(), "工具的自身绑定未动，工具仍在工具集里");
  }

  @Test
  @DisplayName("T25 registry dispose 退役全部声明、静默监听器且幂等；session 不受影响")
  void registryDisposeRetiresEverything() {
    Context session = Contexts.create();
    CordisToolRegistry registry = CordisToolRegistry.create(session);
    AtomicInteger notified = new AtomicInteger();
    registry.onChange(notified::incrementAndGet);
    registry.declare(ECHO_KEY);
    session.plugin(
        ctx -> {
          ctx.provide(ECHO_KEY, new EchoTool("echo", "v1"));
          return Disposables.none();
        });
    assertEquals(1, registry.tools().size());
    assertEquals(1, notified.get());

    registry.dispose();
    assertTrue(registry.tools().isEmpty(), "dispose 必须清空工具集");
    registry.dispose(); // idempotent
    assertEquals(1, notified.get(), "dispose 静默监听器，不再通知");
    assertThrows(
        IllegalStateException.class, () -> registry.declare(ECHO_KEY), "dispose 后 declare 必须拒绝");
    assertEquals("v1:{}", session.get(ECHO_KEY).execute("{}"), "session 的绑定不受 registry dispose 影响");
    assertFalse(registry.tool("echo").isPresent());
  }

  @Test
  @DisplayName("T59 declare 与 dispose 竞态：声明被就地兜底退役，key 不泄漏进 session")
  void racedDeclareDisposeDoesNotLeakDeclarations() throws Exception {
    Context session = Contexts.create();
    int rounds = 200;
    List<WeakReference<ServiceKey<CordisTool>>> keys = new ArrayList<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    for (int i = 0; i < rounds; i++) {
      CordisToolRegistry registry = CordisToolRegistry.create(session);
      ServiceKey<CordisTool> key = ServiceKey.of(CordisTool.class, "k" + i);
      keys.add(new WeakReference<>(key));
      CountDownLatch declaring = new CountDownLatch(1);
      Thread declarer =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      declaring.countDown();
                      try {
                        registry.declare(key);
                      } catch (IllegalStateException alreadyClosed) {
                        // lost the race cleanly: the registry closed before the declaration began
                      }
                    } catch (Throwable thrown) {
                      failure.compareAndSet(null, thrown);
                    }
                  });
      declaring.await(1, TimeUnit.SECONDS);
      registry.dispose(); // races the declare's second synchronized block
      declarer.join(TimeUnit.SECONDS.toMillis(2));
      assertFalse(declarer.isAlive(), "declarer 线程必须在超时内完成");
    }
    assertNull(failure.get(), "压力循环不得产生异常");

    for (WeakReference<ServiceKey<CordisTool>> ref : keys) {
      assertTrue(settle(ref), "竞态窗口里的声明必须被就地兜底退役，fiber 与 key 不得驻留 session");
    }
  }

  static class FailingStopTool implements CordisTool, io.cordis4j.core.Service {
    @Override
    public ToolSpecification toolSpecification() {
      return ToolSpecification.builder().name("failing").description("failing").build();
    }

    @Override
    public String execute(String arguments) {
      return "unused";
    }

    @Override
    public void stop() {
      throw new IllegalStateException("teardown failed");
    }
  }

  @Test
  @DisplayName("T59 retireAll 异常隔离：一条清理失败不中断其余退役，失败聚合抛出")
  void retireAllAggregatesFailuresAndStillRetiresTheRest() {
    Context session = Contexts.create();
    CordisToolRegistry registry = CordisToolRegistry.create(session);
    registry.declare(ECHO_KEY);
    // The first declaration's fiber tracks a failing-stop binding through its activation
    // listener (listeners run inside the activating fiber's domain), so retiring it throws.
    ServiceKey<CordisTool> failingKey = ServiceKey.of(CordisTool.class, "failing");
    AtomicBoolean trapped = new AtomicBoolean();
    registry.onChange(
        () -> {
          if (trapped.compareAndSet(false, true)) {
            session.provide(failingKey, new FailingStopTool()); // tracked by the fiber domain
          }
        });
    registry.declare(failingKey);
    session.provide(ECHO_KEY, new EchoTool("echo", "v1")); // activates the first declaration
    assertEquals(2, registry.tools().size(), "测试前提：两个工具都激活");

    assertThrows(DisposeException.class, registry::dispose, "聚合的清理失败必须抛出");
    assertTrue(registry.tools().isEmpty(), "先失败的一条不得中断其余声明的退役");
  }

  private static boolean settle(WeakReference<?> ref) throws InterruptedException {
    for (int i = 0; i < 150 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    return ref.get() == null;
  }
}
