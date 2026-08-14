/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.Context;
import io.cordis4j.core.ServiceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T27: the Spring integration - @CordisService beans become cordis4j services while the container
 * runs (the spatial dimension over Spring DI) and the context bean disposes the whole session when
 * the container closes (the temporal dimension).
 */
class SpringIntegrationTest {

  static class Clock {
    final String name;

    Clock(String name) {
      this.name = name;
    }
  }

  @CordisService
  static class DefaultClock extends Clock {
    DefaultClock() {
      super("default");
    }
  }

  @CordisService(qualifier = "backup")
  static class BackupClock extends Clock {
    BackupClock() {
      super("backup");
    }
  }

  @Configuration
  static class App {

    @Bean
    static ContextFactoryBean contextFactoryBean() {
      return new ContextFactoryBean();
    }

    @Bean
    static CordisServiceRegistrar cordisServiceRegistrar() {
      return new CordisServiceRegistrar();
    }

    @Bean
    static DefaultClock defaultClock() {
      return new DefaultClock();
    }

    @Bean
    static BackupClock backupClock() {
      return new BackupClock();
    }
  }

  @Configuration
  static class NoContextApp {

    @Bean
    static CordisServiceRegistrar cordisServiceRegistrar() {
      return new CordisServiceRegistrar();
    }

    @Bean
    static DefaultClock defaultClock() {
      return new DefaultClock();
    }
  }

  @Test
  @DisplayName("T27 服务 bean 上线即服务可见（含 qualifier）；容器关闭撤回并 dispose 会话")
  void servicesProvidedAndRevertedWithTheContainer() {
    try (AnnotationConfigApplicationContext container =
        new AnnotationConfigApplicationContext(App.class)) {
      Context ctx = container.getBean(Context.class);
      assertSame("default", ctx.get(DefaultClock.class).name, "默认 realm 的绑定必须可见");
      assertSame(
          "backup",
          ctx.get(ServiceKey.of(BackupClock.class, "backup")).name,
          "qualifier 限定的绑定必须可见");
      assertTrue(ctx.find(BackupClock.class).isEmpty(), "默认 realm 不得意外解析 qualifier 绑定的类型");

      container.close();
      assertThrows(
          IllegalStateException.class, () -> ctx.find(DefaultClock.class), "容器关闭必须 dispose 会话");
    }
  }

  @Test
  @DisplayName("T27 容器无 Context bean 时集成休眠：启动与关闭均不报错")
  void dormantWithoutContextBean() {
    try (AnnotationConfigApplicationContext container =
        new AnnotationConfigApplicationContext(NoContextApp.class)) {
      DefaultClock clock = container.getBean(DefaultClock.class);
      assertEquals("default", clock.name);
      container.close(); // no cordis4j state to revert; must not throw
    }
  }

  @Test
  @DisplayName("T27 ContextFactoryBean 包装已有 context；destroy 即 dispose")
  void factoryBeanWrapsExistingContext() {
    Context session = io.cordis4j.core.Contexts.create();
    ContextFactoryBean factory = new ContextFactoryBean(session);
    assertSame(session, factory.getObject(), "getObject 必须返回包装的 context");
    assertEquals(Context.class, factory.getObjectType());

    factory.destroy();
    assertThrows(
        IllegalStateException.class,
        () -> session.find(DefaultClock.class),
        "destroy 必须 dispose context");
  }
}
