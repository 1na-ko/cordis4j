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

/**
 * The end-to-end vertical-slice demo: assemble, fork a session, use session events and services,
 * then dispose to revert everything in LIFO order.
 *
 * <p>Run: {@code mvn -pl cordis4j-demo exec:java}
 */
public final class QuickStart {

  public static void main(String[] args) {
    Context root = Contexts.create();
    root.plugin(new TimerPlugin()); // root plugin: provides the now() service
    Context session = root.fork(); // isolated session domain
    session.plugin(
        ctx -> { // session plugin: the greeting logic
          ctx.on(Message.class, m -> ctx.emit(new Reply(m.from(), "hello, " + m.text())));
          return Disposables.none();
        });
    session.on(Reply.class, r -> System.out.println(r.from() + ": " + r.text()));
    session.emit(new Message("alice", "hi")); // -> prints alice: hello, hi
    session.dispose(); // session plugins and listeners are all unloaded
    root.emit(new Message("bob", "hi")); // -> no output (session listener already unloaded)
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
