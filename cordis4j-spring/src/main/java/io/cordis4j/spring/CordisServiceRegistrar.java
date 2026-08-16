/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.spring;

import io.cordis4j.core.Context;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.ServiceKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Provides {@link CordisService}-annotated beans into the container's cordis4j {@link Context}
 * (paper, Section 6.4 in the ecosystem form): a bean becomes a resolvable service after its
 * initialization - the spatial dimension over Spring's DI - and the binding is withdrawn when the
 * container closes - the temporal dimension.
 *
 * <p>Annotated beans are keyed by their <em>ultimate target class</em>, never {@code
 * bean.getClass()}: an AOP proxy ({@code @Transactional}, {@code @Async}, ... hides the annotation
 * and carries a generated class, so proxy-blind keying would silently skip the service or key it so
 * nobody could resolve it. The proxied bean itself is provided, keeping its advised behavior.
 *
 * <p>Shutdown ordering: the {@link SmartLifecycle} stop phase runs before any bean is destroyed, so
 * withdrawals drain dependents while the cordis4j context is still alive (core boundary semantics
 * 13/14); the later destroy-phase re-run of the withdrawal is a no-op (empty handle table), and
 * each core removal is itself token-guarded, so double disposal stays safe. A failing teardown does
 * not interrupt the remaining withdrawals: failures are aggregated into a {@link
 * io.cordis4j.core.DisposeException} with the rest as suppressed exceptions.
 *
 * <p>Stop/start cycles: the registrar remembers what it provided and replays the bindings when the
 * lifecycle starts again, so a restarted container serves the same services without a fresh
 * refresh. The context bean is resolved lazily from the container on first use; when the container
 * defines no {@link Context} bean, annotated beans are simply left untouched. The registrar depends
 * on the context bean it resolves, so Spring destroys the registrar (withdrawing the bindings)
 * before the context itself is disposed.
 */
public final class CordisServiceRegistrar
    implements BeanPostProcessor, BeanFactoryAware, DisposableBean, SmartLifecycle {

  private static final Logger LOG = Logger.getLogger(CordisServiceRegistrar.class.getName());

  /** One provided binding: the replay log for lifecycle restarts. */
  private record Provided(ServiceKey<Object> key, Object service) {}

  private BeanFactory beanFactory;
  private Context context;
  private boolean contextResolved;
  private final Map<ServiceKey<Object>, Provided> provided = new LinkedHashMap<>();
  private final Map<ServiceKey<Object>, Disposable> removals = new LinkedHashMap<>();

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
    Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
    CordisService annotation =
        AnnotatedElementUtils.findMergedAnnotation(targetClass, CordisService.class);
    if (annotation == null) {
      return bean;
    }
    Context ctx = context();
    if (ctx == null) {
      return bean; // no resolvable cordis4j context in this container: leave the bean untouched
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    ServiceKey<Object> key = ServiceKey.of((Class) targetClass, annotation.qualifier());
    Disposable removal = ctx.provide(key, bean);
    // One live entry per key: a prototype rebinding overwrites the previous handle, whose
    // removal has been a no-op token-wise since the new provide replaced the binding.
    provided.put(key, new Provided(key, bean));
    removals.put(key, removal);
    return bean;
  }

  /** Withdraws every provided binding in reverse provisioning order, aggregating failures. */
  @Override
  public void destroy() {
    withdraw();
  }

  /**
   * Withdraws the bindings during the container's stop phase, which runs before any bean is
   * destroyed: the withdrawal drain therefore happens while the cordis4j context is still alive, so
   * dependents' teardowns resolve the withdrawn bindings (core boundary semantics 13/14). A
   * teardown that throws is aggregated with the others instead of abandoning the rest.
   */
  @Override
  public void stop() {
    withdraw();
  }

  /**
   * Replays the recorded bindings after a lifecycle restart; only reached when a container that
   * called {@link #stop()} starts again without a fresh refresh.
   */
  @Override
  public void start() {
    Context ctx = context();
    if (ctx == null) {
      return;
    }
    for (Provided entry : provided.values()) {
      if (!removals.containsKey(entry.key())) {
        removals.put(entry.key(), ctx.provide(entry.key(), entry.service()));
      }
    }
  }

  private void withdraw() {
    if (removals.isEmpty()) {
      return;
    }
    Disposable[] live = removals.values().toArray(Disposable[]::new);
    Disposable[] reverse = new Disposable[live.length];
    for (int i = 0; i < live.length; i++) {
      reverse[i] = live[live.length - 1 - i]; // reverse provisioning order, LIFO
    }
    removals.clear(); // the provided log stays for a later start() replay
    Disposables.composite(reverse).dispose();
  }

  @Override
  public synchronized boolean isRunning() {
    return !removals.isEmpty(); // running while bindings are live: stop() withdraws them
  }

  private Context context() {
    if (!contextResolved && beanFactory != null) {
      try {
        context = beanFactory.getBean(Context.class);
      } catch (NoSuchBeanDefinitionException absent) {
        context = null; // no cordis4j context bean: integration stays dormant
      } catch (BeansException unavailable) {
        LOG.warning(
            () ->
                "Cordis4j integration is dormant: the Context bean of this container could not be"
                    + " created ("
                    + unavailable
                    + ")");
        context = null;
      }
      contextResolved = true; // terminal: later beans skip the lookup instead of re-throwing it
    }
    return context;
  }
}
