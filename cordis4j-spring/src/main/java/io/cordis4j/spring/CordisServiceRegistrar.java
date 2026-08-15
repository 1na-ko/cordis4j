/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.spring;

import io.cordis4j.core.Context;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;

/**
 * Provides {@link CordisService}-annotated beans into the container's cordis4j {@link Context}
 * (paper, Section 6.4 in the ecosystem form): a bean becomes a resolvable service after its
 * initialization - the spatial dimension over Spring's DI - and the binding is withdrawn when the
 * container closes - the temporal dimension. Withdrawals run in reverse provisioning order.
 *
 * <p>The context bean is resolved lazily from the container on first use; when the container
 * defines no {@link Context} bean, annotated beans are simply left untouched. The registrar depends
 * on the context bean it resolves, so Spring destroys the registrar (withdrawing the bindings)
 * before the context itself is disposed.
 */
public final class CordisServiceRegistrar
    implements BeanPostProcessor, BeanFactoryAware, DisposableBean, SmartLifecycle {

  private BeanFactory beanFactory;
  private Context context;
  private final List<Disposable> removals = new ArrayList<>();

  /**
   * Creates a registrar; register it as a bean (or a {@link BeanPostProcessor}) of the container.
   */
  public CordisServiceRegistrar() {}

  @Override
  public void setBeanFactory(BeanFactory factory) throws BeansException {
    this.beanFactory = factory;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    CordisService annotation = bean.getClass().getAnnotation(CordisService.class);
    if (annotation == null) {
      return bean;
    }
    Context ctx = context();
    if (ctx == null) {
      return bean; // no cordis4j context in this container: leave the bean untouched
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    ServiceKey<Object> key = ServiceKey.of((Class) bean.getClass(), annotation.qualifier());
    removals.add(ctx.provide(key, bean));
    return bean;
  }

  /** Withdraws every provided binding in reverse provisioning order. */
  @Override
  public void destroy() {
    withdraw();
  }

  /**
   * Withdraws the bindings during the container's stop phase, which runs before any bean is
   * destroyed: the withdrawal drain therefore happens while the cordis4j context is still alive, so
   * dependents' teardowns resolve the withdrawn bindings (core boundary semantics 13/14).
   */
  @Override
  public void stop() {
    withdraw();
  }

  private void withdraw() {
    for (int i = removals.size() - 1; i >= 0; i--) {
      removals.get(i).dispose();
    }
    removals.clear();
  }

  @Override
  public synchronized boolean isRunning() {
    return !removals.isEmpty(); // running while bindings are live: stop() withdraws them
  }

  @Override
  public void start() {
    // nothing to start: bindings appear as beans initialize
  }

  private Context context() {
    if (context == null && beanFactory != null) {
      try {
        context = beanFactory.getBean(Context.class);
      } catch (BeansException absent) {
        return null; // no cordis4j context bean: integration stays dormant
      }
    }
    return context;
  }
}
