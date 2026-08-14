/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package demo;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.Plugin;

/**
 * 端到端垂直切片演示（P1 验收）：装配 → fork 会话 → 会话内事件与服务 → dispose 逆序撤销。
 *
 * <p>运行：{@code mvn -pl cordis4j-demo exec:java}
 */
public final class QuickStart {

  public static void main(String[] args) {
    Context root = Contexts.create();
    root.plugin(new TimerPlugin()); // 根级插件：提供 now() 服务
    Context session = root.fork(); // 会话隔离域
    session.plugin(
        ctx -> { // 会话级插件：问候逻辑
          ctx.on(Message.class, m -> ctx.emit(new Reply(m.from(), "hello, " + m.text())));
          return Disposables.none();
        });
    session.on(Reply.class, r -> System.out.println(r.from() + ": " + r.text()));
    session.emit(new Message("alice", "hi")); // → 打印 alice: hello, hi
    session.dispose(); // 会话内插件/监听全部卸载
    root.emit(new Message("bob", "hi")); // → 无输出（会话监听已卸载）
    System.out.println("root timer = " + root.get(TimerService.class).now());
  }

  record Message(String from, String text) {}

  record Reply(String from, String text) {}

  static final class TimerPlugin implements Plugin {
    @Override
    public Disposable apply(Context ctx) {
      ctx.provide(new TimerService());
      return Disposables.none();
    }
  }

  static final class TimerService {
    long now() {
      return System.currentTimeMillis();
    }
  }

  private QuickStart() {}
}
